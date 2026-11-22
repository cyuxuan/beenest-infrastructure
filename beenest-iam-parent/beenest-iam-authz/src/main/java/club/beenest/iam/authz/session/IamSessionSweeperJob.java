package club.beenest.iam.authz.session;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.common.concurrent.IamDistributedLock;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 会话巡检任务。
 * <p>会话状态在 Redis,数据库会话行只在会话被显式结束时改写。Redis 键自然过期而
 * 数据库行仍为 ACTIVE 时,在线会话列表与强制下线、审计都会失真。本任务按状态捞出
 * ACTIVE 行,逐条核对 Redis 键:不存在即置为过期并记录会话超时事件。</p>
 * <p>巡检是全表扫描与状态改写,多实例下经分布式锁保证只有一个实例执行。</p>
 */
@Component
public class IamSessionSweeperJob {

    private static final Logger log = LoggerFactory.getLogger(IamSessionSweeperJob.class);

    /** 分布式锁键 */
    private static final String LOCK_KEY = "iam:lock:session-sweep";
    /** 锁的自动过期时长 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    /** 单次巡检取回的会话行数上限 */
    private static final int BATCH_SIZE = 500;

    private final UserSessionStore userSessionStore;
    private final RedisUtils redisUtils;
    private final RealmProvider realmProvider;
    private final UserProvider userProvider;
    private final LoginEventPublisher loginEventPublisher;
    private final IamDistributedLock distributedLock;

    public IamSessionSweeperJob(UserSessionStore userSessionStore,
                                 RedisUtils redisUtils,
                                 RealmProvider realmProvider,
                                 UserProvider userProvider,
                                 LoginEventPublisher loginEventPublisher,
                                 IamDistributedLock distributedLock) {
        this.userSessionStore = userSessionStore;
        this.redisUtils = redisUtils;
        this.realmProvider = realmProvider;
        this.userProvider = userProvider;
        this.loginEventPublisher = loginEventPublisher;
        this.distributedLock = distributedLock;
    }

    /**
     * 巡检并收尾已失效会话。
     * <p>默认每 5 分钟一次,间隔由 iam.session.sweep-interval-ms 调整;
     * 多实例下仅持有分布式锁的实例执行。</p>
     */
    @Scheduled(fixedDelayString = "${iam.session.sweep-interval-ms:300000}",
            initialDelayString = "${iam.session.sweep-initial-delay-ms:60000}")
    public void sweep() {
        String token = distributedLock.tryLock(LOCK_KEY, LOCK_TTL);
        if (token == null) {
            log.debug("会话巡检任务已被其它实例执行,本次跳过");
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // 巡检失败不影响平台运行,仅推迟失效会话的收尾
            log.error("会话巡检失败", e);
        } finally {
            distributedLock.unlock(LOCK_KEY, token);
        }
    }

    /** 巡检一轮:把 Redis 键已消失但数据库行仍为 ACTIVE 的会话收尾。 */
    public void sweepOnce() {
        List<UserSessionModel> sessions = userSessionStore.listActive(BATCH_SIZE);
        if (sessions.isEmpty()) {
            return;
        }
        int expired = 0;
        for (UserSessionModel session : sessions) {
            String sid = session.sid();
            if (sid == null || sid.isBlank()) {
                continue;
            }
            // Redis 键还在:会话仍存活,跳过
            if (redisUtils.hasKey(IamUserSessionService.sessionKey(sid))) {
                continue;
            }
            // 键已消失:判定会话结束;条件更新保证过期只记一次
            if (!userSessionStore.expireIfActive(sid)) {
                continue;
            }
            expired++;
            // 记录会话超时事件,与主动注销分开,便于事后区分来源
            String realmKey = realmProvider.findById(session.realmId())
                    .map(realm -> realm.realmKey()).orElse(null);
            String username = userProvider.findById(session.userId())
                    .map(user -> user.username()).orElse(null);
            loginEventPublisher.publish(realmKey, session.userId(), username,
                    LoginEventEvent.SESSION_TIMEOUT, session.loginType(),
                    session.ip(), session.userAgent(), "会话超时或异常结束");
        }
        if (expired > 0) {
            log.info("会话巡检完成: 收尾 {} 条已失效但仍标记为活跃的会话", expired);
        }
    }
}
