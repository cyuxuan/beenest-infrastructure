package club.beenest.payment.paymentorder.scheduler;

import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.paymentorder.service.IPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款状态自动同步任务。
 *
 * <p>用于兜底渠道异步退款结果，避免退款单长期停留在 PROCESSING 状态。</p>
 */
@Component
@RequiredArgsConstructor
public class RefundStatusSyncScheduler {

    private final IPaymentService paymentService;
    private final PaymentConfig paymentConfig;

    @Scheduled(
            fixedDelayString = "${payment.common.refund-sync-delay-ms:30000}",
            initialDelayString = "${payment.common.refund-sync-initial-delay-ms:15000}"
    )
    public void syncProcessingRefunds() {
        if (!paymentConfig.getCommon().isRefundSyncEnabled()) {
            return;
        }

        int batchSize = Math.max(paymentConfig.getCommon().getRefundSyncBatchSize(), 1);
        paymentService.syncProcessingRefunds(batchSize);
    }
}
