package club.beenest.payment.scheduler;

import club.beenest.payment.mapper.PaymentOrderMapper;
import club.beenest.payment.object.entity.PaymentOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付订单过期定时任务
 * 每分钟扫描已过期但仍处于 PENDING 状态的订单，将其标记为 EXPIRED
 *
 * @author System
 * @since 2026-04-08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOrderExpireScheduler {

    private final PaymentOrderMapper paymentOrderMapper;

    private static final int BATCH_SIZE = 100;

    /**
     * 每分钟执行一次过期订单扫描
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expirePendingOrders() {
        LocalDateTime now = LocalDateTime.now();
        try {
            List<PaymentOrder> expiredOrders = paymentOrderMapper.selectExpiredOrders(now, BATCH_SIZE);
            if (expiredOrders.isEmpty()) {
                return;
            }

            log.info("扫描到 {} 个过期待支付订单", expiredOrders.size());

            int updated = paymentOrderMapper.batchUpdateExpiredOrders(now, BATCH_SIZE);
            if (updated > 0) {
                log.info("已将 {} 个过期订单标记为 EXPIRED", updated);
            }
        } catch (Exception e) {
            log.error("扫描过期订单异常", e);
        }
    }
}
