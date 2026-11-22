package club.beenest.iam.authz.audit;

import club.beenest.iam.common.concurrent.IamDistributedLock;
import club.beenest.iam.common.event.AuditLogEvent;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 审计日志留存期清理任务。
 * <p>审计表只增不减,长期积累会拖慢查询。本任务按留存期删除过期记录,默认保留 180 天;
 * 清理动作本身也记一条审计,留下时间、条数与责任主体(系统)。</p>
 * <p>删除受数据库只追加触发器保护:清理前需在事务内开启清理通道,未开启时 DELETE 会被拒绝。</p>
 * <p>多实例下由分布式锁保证同一时刻只有一个实例真正执行,其余实例本轮跳过。</p>
 */
@Component
public class IamAuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(IamAuditRetentionJob.class);

    /** 清理动作的审计编码 */
    private static final String ACTION_PURGE = "AUDIT_PURGE";
    /** 分布式锁键 */
    private static final String LOCK_KEY = "iam:lock:audit-purge";
    /** 锁的自动过期时长:必须大于一次清理的最长耗时 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    /** 单轮清理涉及的身份域数量上限 */
    private static final int REALM_BATCH_LIMIT = 200;

    private final club.beenest.iam.persistence.mapper.AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final IamDistributedLock distributedLock;
    private final RealmProvider realmProvider;

    /** 留存天数;小于等于 0 表示不清理(审计永久保留)。 */
    @Value("${iam.audit.retention-days:180}")
    private int retentionDays;

    public IamAuditRetentionJob(club.beenest.iam.persistence.mapper.AuditLogMapper auditLogMapper,
                                ApplicationEventPublisher eventPublisher,
                                IamDistributedLock distributedLock,
                                RealmProvider realmProvider) {
        this.auditLogMapper = auditLogMapper;
        this.eventPublisher = eventPublisher;
        this.distributedLock = distributedLock;
        this.realmProvider = realmProvider;
    }

    /**
     * 清理超出留存期的审计记录。
     * <p>每天 03:30 触发,cron 可配置;retentionDays 小于等于 0 时不清理;抢不到分布式锁
     * 时本轮跳过。按域分别清理并在同一事务内留一条 AUDIT_PURGE 审计,失败即回滚,
     * 不影响平台运行。</p>
     */
    @Scheduled(cron = "${iam.audit.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        if (retentionDays <= 0) {
            log.info("审计留存期未配置或为非正值,本次不清理");
            return;
        }
        // 多实例下只让抢到锁的那个实例执行
        String token = distributedLock.tryLock(LOCK_KEY, LOCK_TTL);
        if (token == null) {
            log.debug("审计清理任务已被其它实例执行,本次跳过");
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            // 1. 打开事务内清理通道(只追加触发器据此放行 DELETE)
            auditLogMapper.enablePurge();
            // 2. 按域分别清理:清理动作要落进各域自己的审计轨迹,
            //    平台级的一条汇总记录在域内审计视图里看不到,等于没有留痕
            int total = 0;
            for (Long realmId : auditLogMapper.listRealmsWithExpired(cutoff, REALM_BATCH_LIMIT)) {
                long count = auditLogMapper.countExpired(realmId, cutoff);
                int deleted = auditLogMapper.deleteExpired(realmId, cutoff);
                if (deleted <= 0) {
                    continue;
                }
                total += deleted;
                // 3. 清理动作留痕(与删除同一事务:清理失败则连同这条记录一起回滚)
                String realmKey = realmProvider.findById(realmId).map(realm -> realm.realmKey()).orElse(null);
                eventPublisher.publishEvent(new AuditLogEvent(
                        ACTION_PURGE,
                        "清理超过 " + retentionDays + " 天的审计日志,共 " + count + " 条",
                        null, "system", realmKey, "AUDIT", null, null, LocalDateTime.now()));
            }
            if (total > 0) {
                log.info("审计留存期清理完成: 保留 {} 天,清理 {} 条,截止时间 {}", retentionDays, total, cutoff);
            }
        } catch (RuntimeException e) {
            // 清理失败不影响平台运行:审计少几条好过服务起不来
            log.error("审计留存期清理失败: retentionDays={}", retentionDays, e);
        } finally {
            distributedLock.unlock(LOCK_KEY, token);
        }
    }
}
