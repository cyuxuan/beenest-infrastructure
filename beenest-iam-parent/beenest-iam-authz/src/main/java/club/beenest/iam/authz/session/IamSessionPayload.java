package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.core.model.RequiredAction;

import java.util.Set;

/**
 * 浏览器会话在 Redis 中的快照。
 * <p>会话状态存放于共享 Redis 而非容器内存,会话标识由平台签发,任意实例可凭 sid
 * 加载同一份快照继续服务。</p>
 * <p>快照保存平台自身的主体对象({@link IamUserPrincipal})而非 Spring 的
 * Authentication:框架类型的序列化形态随版本变动,主体为自有稳定模型,
 * 读取后再重建 Authentication。</p>
 *
 * @param principal           登录主体快照
 * @param createdTime         会话创建时刻(毫秒时间戳):绝对最大时长的起算点
 * @param maxLifetimeSeconds  会话最大存续时长(秒):超过即作废,不因活跃而延长
 * @param idleSeconds         空闲超时(秒):超过这么久没有任何请求即视为失效
 * @param lastTouchMillis     最近一次活跃刷新时间(毫秒时间戳):用于写库节流
 * @param mfaVerified         本会话是否已完成二步验证;用包装类型兼容存量快照,null 与 false 同义
 * @param requiredActions     本会话仍需完成的待办事项;可空以兼容存量快照,null 与空集合同义
 */
public record IamSessionPayload(
        IamUserPrincipal principal,
        long createdTime,
        long maxLifetimeSeconds,
        long idleSeconds,
        long lastTouchMillis,
        Boolean mfaVerified,
        Set<RequiredAction> requiredActions
) {

    /** 兼容既有调用点:不带待办事项的会话快照。 */
    public IamSessionPayload(IamUserPrincipal principal, long createdTime, long maxLifetimeSeconds,
                             long idleSeconds, long lastTouchMillis, Boolean mfaVerified) {
        this(principal, createdTime, maxLifetimeSeconds, idleSeconds, lastTouchMillis, mfaVerified, null);
    }

    /**
     * 补验后的新快照。
     * <p>只改认证相关字段,存续起算点与时长原样保留,补验不重置会话寿命。</p>
     *
     * @param loginType   新的第一因素认证方式;null 保留主体原方式,不把已有方式抹成未知
     * @param mfaVerified 二步验证完成态;原样写入新快照
     * @return 更新认证方式与二步验证完成态后的新快照
     */
    public IamSessionPayload withAuthentication(String loginType, Boolean mfaVerified) {
        IamUserPrincipal upgradedPrincipal = principal;
        if (principal != null && loginType != null) {
            upgradedPrincipal = new IamUserPrincipal(
                    principal.getUserId(), principal.getRealmId(), principal.getRealmKey(),
                    principal.getUsername(), principal.getNickname(), principal.getTokenVersion(),
                    principal.getAuthorities(), principal.getAttributes(),
                    principal.getPermissionEpoch(), loginType);
        }
        return new IamSessionPayload(upgradedPrincipal, createdTime, maxLifetimeSeconds,
                idleSeconds, lastTouchMillis, mfaVerified, requiredActions);
    }

    /**
     * 更新待办事项后的新快照,其余字段原样保留。
     *
     * @param actions 最新待办集合;null 或空集合在快照中记为 null
     * @return 新快照
     */
    public IamSessionPayload withRequiredActions(Set<RequiredAction> actions) {
        return new IamSessionPayload(principal, createdTime, maxLifetimeSeconds,
                idleSeconds, lastTouchMillis, mfaVerified,
                actions == null || actions.isEmpty() ? null : Set.copyOf(actions));
    }

    /**
     * 是否还有未完成的待办事项。
     *
     * @return true 表示存在待完成的必需操作
     */
    public boolean hasRequiredActions() {
        return requiredActions != null && !requiredActions.isEmpty();
    }

    /**
     * 是否已超过最大存续时长。
     *
     * @param nowMillis 当前时刻(毫秒时间戳)
     * @return true 表示已超期;最大存续时长未配置时恒为 false
     */
    public boolean exceededMaxLifetime(long nowMillis) {
        if (maxLifetimeSeconds <= 0) {
            return false;
        }
        return (nowMillis - createdTime) / 1000L > maxLifetimeSeconds;
    }

    /**
     * 是否已空闲超时。
     *
     * @param nowMillis 当前时刻(毫秒时间戳)
     * @return true 表示已超时;空闲超时未配置时恒为 false
     */
    public boolean exceededIdleTimeout(long nowMillis) {
        if (idleSeconds <= 0) {
            return false;
        }
        return (nowMillis - lastTouchMillis) / 1000L > idleSeconds;
    }
}
