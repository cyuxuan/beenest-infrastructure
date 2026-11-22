package club.beenest.iam.authz.notification;

import club.beenest.iam.common.concurrent.IamDistributedLock;
import club.beenest.iam.persistence.manager.IamNotificationLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 通知投递记录清理任务。
 * <p>{@code iam_notification_log} 只追加,发信量越大增长越快。管理端的"排障窗口"
 * 只关心最近一段时间,更早的记录只会让索引变深、备份变大。本任务按保留天数
 * 分批物理删除:每批限量,循环到不足一批为止,避免一次大事务长时间持锁。</p>
 * <p>保留天数配成 0 或负数表示永久保留,交由外部归档方案处理。多实例下经
 * 分布式锁保证只有一个实例执行。</p>
 */
@Component
public class NotificationLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationLogCleanupJob.class);

    /** 分布式锁键 */
    private static final String LOCK_KEY = "iam:lock:notification-log-cleanup";
    /** 锁的自动过期时长:需大于一次清理的预期耗时 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    private final IamNotificationLogManager notificationLogManager;
    private final IamDistributedLock distributedLock;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;

    public NotificationLogCleanupJob(IamNotificationLogManager notificationLogManager,
                                     IamDistributedLock distributedLock,
                                     @Value("${iam.notification.retention-days:90}") int retentionDays,
                                     @Value("${iam.notification.cleanup-batch-size:2000}") int batchSize,
                                     @Value("${iam.notification.cleanup-max-batches:50}") int maxBatches) {
        this.notificationLogManager = notificationLogManager;
        this.distributedLock = distributedLock;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatches = Math.max(1, maxBatches);
    }

    /**
     * 按保留期清理投递记录。
     * <p>默认每 6 小时一次,保留 90 天;间隔与首轮延迟由
     * {@code iam.notification.cleanup-interval-ms}、{@code iam.notification.cleanup-initial-delay-ms}
     * 调整。未取得锁时直接跳过。</p>
     */
    @Scheduled(fixedDelayString = "${iam.notification.cleanup-interval-ms:21600000}",
            initialDelayString = "${iam.notification.cleanup-initial-delay-ms:300000}")
    public void cleanup() {
        if (retentionDays <= 0) {
            log.debug("通知投递记录保留期为 {} 天,跳过清理", retentionDays);
            return;
        }
        String token = distributedLock.tryLock(LOCK_KEY, LOCK_TTL);
        if (token == null) {
            log.debug("通知投递记录清理任务已被其它实例执行,本次跳过");
            return;
        }
        try {
            int deleted = cleanupOnce();
            if (deleted > 0) {
                log.info("通知投递记录清理完成: 删除 {} 条保留期({} 天)之前的记录", deleted, retentionDays);
            }
        } catch (RuntimeException e) {
            // 清理失败只影响表体积,不影响发信
            log.error("通知投递记录清理失败", e);
        } finally {
            distributedLock.unlock(LOCK_KEY, token);
        }
    }

    /**
     * 执行一轮分批清理(不加锁,便于测试与手工触发)。
     *
     * @return 实际删除的记录总数
     */
    public int cleanupOnce() {
        if (retentionDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = notificationLogManager.deleteBefore(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
