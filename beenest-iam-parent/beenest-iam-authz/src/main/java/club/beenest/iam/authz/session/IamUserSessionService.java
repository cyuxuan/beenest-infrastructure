package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 用户会话服务。
 * <p>登录成功时建立会话:Redis 保存会话快照(供鉴权与校验),数据库落一条
 * iam_user_session 记录(供审计与会话列表)。会话标识由平台签发(随机令牌)并经
 * Cookie 携带,任意实例都可凭它从 Redis 取回同一份会话。</p>
 * <p>会话存续由身份域策略的两段时长共同决定:空闲超时逐请求滑动续期,超时即失效;
 * 最大存续时长为绝对上限,取域最大时长与刷新令牌有效期的较小者,不因活跃而延长。</p>
 */
@Service
public class IamUserSessionService {

    private static final Logger log = LoggerFactory.getLogger(IamUserSessionService.class);

    /** 域未配置 SSO 最大时长时的兜底值(秒) */
    private static final long DEFAULT_SSO_MAX_SECONDS = 36000L;
    /** 域未配置空闲超时时不下发滑动续期(沿用最大时长) */
    private static final int IDLE_NOT_CONFIGURED = 0;
    /** 会话标识字节数(编码后约 43 字符) */
    private static final int SID_BYTES = 32;
    /** 活跃时间刷新节流间隔(毫秒) */
    private static final long TOUCH_INTERVAL_MILLIS = 60_000L;
    /** 随机源 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserSessionStore userSessionStore;
    private final RedisUtils redisUtils;
    /** 用户读取:登录时取待办事项;为 null 表示不处理待办(测试替身场景) */
    private final UserProvider userProvider;
    /** 登录策略读取:并发会话上限;为 null 表示不限制(测试替身场景) */
    private final IamSecurityPolicyManager securityPolicyManager;

    /** 测试替身用:不读取用户待办事项,也不限制并发会话数。 */
    public IamUserSessionService(UserSessionStore userSessionStore, RedisUtils redisUtils) {
        this(userSessionStore, redisUtils, null, null);
    }

    @Autowired
    public IamUserSessionService(UserSessionStore userSessionStore, RedisUtils redisUtils,
                                 UserProvider userProvider,
                                 IamSecurityPolicyManager securityPolicyManager) {
        this.userSessionStore = userSessionStore;
        this.redisUtils = redisUtils;
        this.userProvider = userProvider;
        this.securityPolicyManager = securityPolicyManager;
    }

    /**
     * 登录成功后建立会话。
     * <p>会话标识由平台签发:它要跨实例可用,不能是容器生成的会话 id。</p>
     *
     * @param principal 登录主体
     * @param loginType 登录方式(PASSWORD / FEDERATION)
     * @param ip        登录来源 IP
     * @param userAgent 浏览器标识
     * @return 会话标识(sid)
     */
    public String startSession(IamUserPrincipal principal, String loginType,
                               String ip, String userAgent) {
        RealmContext context = RealmContextHolder.get();
        RealmModel realm = context == null ? null : context.realm();
        long maxSeconds = resolveMaxSeconds(realm);
        int idleSeconds = resolveIdleSeconds(realm);
        // 并发会话超限时先顶掉最旧会话,再签发新会话
        evictOldestIfLimitReached(realm, principal);
        String sid = newSessionId();
        long now = System.currentTimeMillis();
        // 新会话尚未完成二步验证,完成态置空;待办事项取用户行上的当前值
        IamSessionPayload payload = new IamSessionPayload(
                principal, now, maxSeconds, idleSeconds, now, null, loadRequiredActions(principal));
        // Redis 快照 TTL 取空闲超时与最大时长的较小者;空闲超时逐请求滑动续期,
        // 最大时长由快照中的绝对起算点约束
        redisUtils.setObject(sessionKey(sid), payload, Duration.ofSeconds(ttlSeconds(maxSeconds, idleSeconds)));
        // 数据库会话行状态为活跃
        userSessionStore.insert(new UserSessionModel(
                null, principal.getRealmId(), sid, principal.getUserId(),
                loginType, ip, userAgent, UserSessionModel.STATE_ACTIVE, null, null, null));
        return sid;
    }

    /**
     * 注销会话:删除 Redis 快照,数据库会话行置为已注销。
     *
     * @param sid 会话标识;为空时不做任何处理
     */
    public void endSession(String sid) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        redisUtils.delete(sessionKey(sid));
        userSessionStore.updateState(sid, UserSessionModel.STATE_LOGGED_OUT);
    }

    /**
     * 会话因超时/异常而结束:删除 Redis 快照,数据库会话行置为已过期。
     * <p>过期与主动注销分开记录,保证在线会话列表与登出审计可区分两种来源。</p>
     *
     * @param sid 会话标识;为空时不做任何处理
     */
    public void expireSession(String sid) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        redisUtils.delete(sessionKey(sid));
        userSessionStore.updateState(sid, UserSessionModel.STATE_EXPIRED);
    }

    /**
     * 同账号重新登录时结束旧会话:删除 Redis 快照,数据库会话行置为已被替换。
     * <p>与主动注销区分:用户没有点登出,只是换了浏览器或窗口重新登录。</p>
     *
     * @param sid 会话标识;为空时不做任何处理
     */
    public void replaceSession(String sid) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        redisUtils.delete(sessionKey(sid));
        userSessionStore.updateState(sid, UserSessionModel.STATE_REPLACED);
    }

    /**
     * 订正会话行的在线状态。
     * <p>在线与否以 Redis 快照为准:空闲超时或实例清理后键会消失,而数据库行要等巡检
     * 才改状态。读列表时顺手把"标着活跃、实际已失效"的行订正为已过期,列表才不会
     * 把已经下线的会话显示成在线。</p>
     *
     * @param sid 会话标识
     * @return true 表示会话仍然在线;Redis 不可用时按在线处理,不误伤
     */
    public boolean expireIfStale(String sid) {
        if (sid == null || sid.isBlank()) {
            return false;
        }
        boolean live;
        try {
            live = redisUtils.hasKey(sessionKey(sid));
        } catch (RuntimeException e) {
            // 判断不了就维持原状:误标过期会让用户莫名掉线,显示滞后只是观感问题
            log.warn("会话存活检查失败,按在线处理: error={}", e.getMessage());
            return true;
        }
        if (!live) {
            try {
                userSessionStore.updateState(sid, UserSessionModel.STATE_EXPIRED);
            } catch (RuntimeException e) {
                log.debug("订正会话过期状态失败: error={}", e.getMessage());
            }
        }
        return live;
    }

    /**
     * 结束用户的全部活跃会话(全端下线)。
     *
     * @param realmId 域主键
     * @param userId  用户主键
     * @return 被结束的会话数量
     */
    public int endAllSessions(Long realmId, Long userId) {
        List<UserSessionModel> sessions = userSessionStore.listByUser(realmId, userId);
        int count = 0;
        for (UserSessionModel session : sessions) {
            if (UserSessionModel.STATE_ACTIVE.equals(session.state())) {
                endSession(session.sid());
                count++;
            }
        }
        return count;
    }

    /**
     * 并发会话数超限时结束最旧的会话。
     * <p>存续判断以 Redis 快照为准:数据库行可能因巡检滞后而仍标记 ACTIVE,
     * 据此淘汰会白占名额。被顶下线的会话状态记为 REPLACED,与超时失效区分。</p>
     *
     * @param realm     当前身份域;为 null 时读不到策略
     * @param principal 登录主体
     */
    private void evictOldestIfLimitReached(RealmModel realm, IamUserPrincipal principal) {
        if (securityPolicyManager == null || realm == null || principal.getUserId() == null) {
            return;
        }
        LoginPolicy policy;
        try {
            policy = securityPolicyManager.getLoginPolicy(principal.getRealmId(), realm.realmKey());
        } catch (RuntimeException e) {
            // 策略读取失败不阻断登录:宁可暂时超限,也不能把用户锁在门外
            log.warn("读取并发会话策略失败,跳过会话数限制: userId={}, error={}",
                    principal.getUserId(), e.getMessage());
            return;
        }
        int limit = policy == null || policy.getMaxConcurrentSessions() == null
                ? 0 : policy.getMaxConcurrentSessions();
        if (limit <= 0) {
            return;
        }
        List<UserSessionModel> active = userSessionStore.listActiveByUser(
                principal.getRealmId(), principal.getUserId());
        // 新会话也要占一个名额,旧会话只保留 limit-1 个
        int keep = Math.max(0, limit - 1);
        int live = 0;
        int evicted = 0;
        for (UserSessionModel session : active) {
            if (!redisUtils.hasKey(sessionKey(session.sid()))) {
                continue;
            }
            live++;
            if (live <= keep) {
                continue;
            }
            redisUtils.delete(sessionKey(session.sid()));
            userSessionStore.updateState(session.sid(), UserSessionModel.STATE_REPLACED);
            evicted++;
        }
        if (evicted > 0) {
            log.info("并发会话数超限,已顶下线最旧会话: userId={}, limit={}, evicted={}",
                    principal.getUserId(), limit, evicted);
        }
    }

    /**
     * 刷新会话活跃时间。
     * <p>空闲超时逐请求滑动续期,决定会话何时失效;活跃时间写库按节流间隔执行,
     * 避免会话表成为热点。</p>
     *
     * @param sid 会话标识;为空或会话不存在时不做任何处理
     */
    public void touchSession(String sid) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        IamSessionPayload payload = loadSession(sid);
        if (payload == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 滑动续期:空闲超时起算点随每次请求后移
        if (payload.idleSeconds() > 0) {
            redisUtils.expire(sessionKey(sid),
                    Duration.ofSeconds(ttlSeconds(payload.maxLifetimeSeconds(), (int) payload.idleSeconds())));
        }
        if (now - payload.lastTouchMillis() < TOUCH_INTERVAL_MILLIS) {
            return;
        }
        // 仅更新 lastTouchMillis:二步验证完成态与待办事项原样保留,
        // 置空会使补验状态与未完成待办在活跃刷新时丢失
        IamSessionPayload refreshed = new IamSessionPayload(payload.principal(), payload.createdTime(),
                payload.maxLifetimeSeconds(), payload.idleSeconds(), now,
                payload.mfaVerified(), payload.requiredActions());
        redisUtils.setObject(sessionKey(sid), refreshed,
                Duration.ofSeconds(ttlSeconds(payload.maxLifetimeSeconds(), (int) payload.idleSeconds())));
        try {
            userSessionStore.touch(sid);
        } catch (RuntimeException e) {
            // 活跃时间是观测数据而非控制数据,刷新失败不影响本次请求
            log.debug("刷新会话活跃时间失败: sid={}, error={}", sid, e.getMessage());
        }
    }

    /**
     * 刷新会话上的待办事项。
     * <p>待办事项的权威值在 iam_user 行上;会话里的副本只用于逐请求判定,
     * 用户完成一项后由调用方同时更新两处。</p>
     *
     * @param sid     会话标识
     * @param actions 最新的待办集合;空集合表示没有待办
     */
    public void updateRequiredActions(String sid, Set<RequiredAction> actions) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        IamSessionPayload payload = loadSession(sid);
        if (payload == null) {
            return;
        }
        IamSessionPayload refreshed = payload.withRequiredActions(actions);
        long ttl = ttlSeconds(payload.maxLifetimeSeconds(), (int) payload.idleSeconds());
        redisUtils.setObject(sessionKey(sid), refreshed, Duration.ofSeconds(ttl));
    }

    /**
     * 读取用户当前的待办事项。
     *
     * @param principal 登录主体
     * @return 待办集合;未注入 UserProvider、主体缺失或读取失败时返回空集合
     */
    private Set<RequiredAction> loadRequiredActions(IamUserPrincipal principal) {
        if (userProvider == null || principal == null || principal.getUserId() == null) {
            return Set.of();
        }
        try {
            return userProvider.findById(principal.getUserId())
                    .map(UserModel::requiredActions)
                    .orElse(Set.of());
        } catch (RuntimeException e) {
            // 待办是登录后的引导,读取失败不阻断登录
            log.warn("读取用户待办事项失败,按无待办处理: userId={}, error={}",
                    principal.getUserId(), e.getMessage());
            return Set.of();
        }
    }

    /**
     * 读取会话快照。
     * <p>键不存在、已过期或快照无法反序列化时统一按会话不存在处理。</p>
     *
     * @param sid 会话标识
     * @return 会话快照;会话不存在或读取失败返回 null
     */
    public IamSessionPayload loadSession(String sid) {
        if (sid == null || sid.isBlank()) {
            return null;
        }
        try {
            return redisUtils.getObject(sessionKey(sid), IamSessionPayload.class);
        } catch (RuntimeException e) {
            log.warn("会话快照读取失败,按会话不存在处理: sid={}, error={}", sid, e.getMessage());
            return null;
        }
    }

    /**
     * 补验成功:升级会话的认证方式与二步验证完成态。
     * <p>保留原 sid 与存续起算点,不新建会话;TTL 按剩余时长重算、不按满额重置,
     * 补验不影响会话寿命。数据库会话行的认证方式同步更新。</p>
     *
     * @param sid         会话标识
     * @param loginType   新的第一因素认证方式;null 表示保留原方式(只补第二因素时用)
     * @param mfaVerified 二步验证完成态;null 表示重置为未验证(与 loginType 的 null 语义不同)
     * @return true 表示会话存在并已写入升级结果;false 表示会话不存在(已失效或被并发删除),未做任何变更
     */
    public boolean upgradeAuthentication(String sid, String loginType, Boolean mfaVerified) {
        if (sid == null || sid.isBlank()) {
            return false;
        }
        IamSessionPayload payload = loadSession(sid);
        if (payload == null) {
            return false;
        }
        IamSessionPayload upgraded = payload.withAuthentication(loginType, mfaVerified);
        long now = System.currentTimeMillis();
        long idleSeconds = payload.idleSeconds();
        long maxSeconds = payload.maxLifetimeSeconds();
        // TTL 取剩余空闲时长与剩余最大存续时长的较小者。
        // 时长为 0 表示该维度未配置、不构成约束(与 ttlSeconds / exceededXxx 语义一致),
        // 按无限参与取小;直接相减会得到负数,会话会被压成 1 秒即失效
        long remainingIdle = idleSeconds > 0
                ? idleSeconds - (now - payload.lastTouchMillis()) / 1000L
                : Long.MAX_VALUE;
        long remainingMax = maxSeconds > 0
                ? maxSeconds - (now - payload.createdTime()) / 1000L
                : Long.MAX_VALUE;
        long ttl = Math.max(1L, Math.min(remainingIdle, remainingMax));
        redisUtils.setObject(sessionKey(sid), upgraded, Duration.ofSeconds(ttl));
        // 同步数据库会话行:管理端在线会话读取 iam_user_session 而非 Redis;
        // loginType 为 null 时 updateLoginType 内部跳过,已有方式不受影响
        userSessionStore.updateLoginType(sid, loginType);
        return true;
    }

    /**
     * 读取会话主体快照。
     *
     * @param sid 会话标识
     * @return 主体快照;会话不存在或已过期返回 null
     */
    public IamUserPrincipal getPrincipal(String sid) {
        IamSessionPayload payload = loadSession(sid);
        return payload == null ? null : payload.principal();
    }

    /**
     * 查询用户的全部会话记录(会话管理列表)。
     *
     * @param realmId 域主键
     * @param userId  用户主键
     * @return 会话记录列表
     */
    public List<UserSessionModel> listSessions(Long realmId, Long userId) {
        return userSessionStore.listByUser(realmId, userId);
    }

    /**
     * 按会话标识查询会话记录(注销前的归属校验)。
     *
     * @param sid 会话标识
     * @return 会话记录;不存在返回 null
     */
    public UserSessionModel findBySid(String sid) {
        if (sid == null || sid.isBlank()) {
            return null;
        }
        return userSessionStore.findBySid(sid);
    }

    /**
     * 会话最大存续时长(秒)。
     * <p>取域最大时长与刷新令牌有效期的较小者:刷新令牌到期后无法再续签,
     * 会话不应继续存续。</p>
     *
     * @param realm 身份域;可为 null
     * @return 最大存续秒数
     */
    public long resolveMaxSeconds(RealmModel realm) {
        return resolveMaxSecondsOf(realm);
    }

    /**
     * 会话最大存续时长(秒)。
     * <p>取域最大时长与刷新令牌有效期的较小者;域未配置时取兜底值 36000 秒。</p>
     *
     * @param realm 身份域;可为 null
     * @return 最大存续秒数
     */
    public static long resolveMaxSecondsOf(RealmModel realm) {
        if (realm == null) {
            return DEFAULT_SSO_MAX_SECONDS;
        }
        long ssoMax = realm.ssoSessionMax() > 0 ? realm.ssoSessionMax() : DEFAULT_SSO_MAX_SECONDS;
        long refreshTtl = realm.refreshTokenTtl();
        if (refreshTtl > 0 && refreshTtl < ssoMax) {
            return refreshTtl;
        }
        return ssoMax;
    }

    /**
     * 会话空闲超时(秒)。
     *
     * @param realm 身份域;可为 null
     * @return 空闲超时秒数;0 表示未配置,沿用最大存续时长
     */
    public int resolveIdleSeconds(RealmModel realm) {
        if (realm == null) {
            return IDLE_NOT_CONFIGURED;
        }
        long idle = realm.ssoSessionIdle();
        return idle > 0 ? (int) idle : IDLE_NOT_CONFIGURED;
    }

    /**
     * 会话快照在 Redis 中的键。
     *
     * @param sid 会话标识
     * @return Redis 键
     */
    public static String sessionKey(String sid) {
        return String.format(IamRedisKeyConstants.USER_SESSION, sid);
    }

    /**
     * 快照 TTL:空闲超时与最大存续时长取小;空闲未配置时用最大存续时长。
     *
     * @param maxSeconds  最大存续秒数
     * @param idleSeconds 空闲超时秒数,0 表示未配置
     * @return Redis 键过期秒数,最小为 1
     */
    private static long ttlSeconds(long maxSeconds, int idleSeconds) {
        if (idleSeconds <= 0) {
            return Math.max(1, maxSeconds);
        }
        return Math.max(1, Math.min(maxSeconds, idleSeconds));
    }

    /**
     * 签发会话标识。
     *
     * @return Base64URL 编码的随机令牌,不可从用户信息推导
     */
    private static String newSessionId() {
        byte[] bytes = new byte[SID_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
