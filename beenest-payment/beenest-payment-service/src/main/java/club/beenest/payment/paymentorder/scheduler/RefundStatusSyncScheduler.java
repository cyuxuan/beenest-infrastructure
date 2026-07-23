package club.beenest.payment.paymentorder.scheduler;

import club.beenest.payment.paymentorder.config.PaymentConfig;
import club.beenest.payment.paymentorder.domain.enums.RefundStatus;
import club.beenest.payment.paymentorder.mapper.RefundMapper;
import club.beenest.payment.paymentorder.service.IPaymentService;
import club.beenest.payment.paymentorder.domain.entity.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 退款状态自动同步任务。
 *
 * <p>用于兜底渠道异步退款结果，避免退款单长期停留在 PENDING/PROCESSING 状态。</p>
 *
 * <h3>补偿场景：</h3>
 * <ul>
 *   <li>PENDING 状态：退款单已创建但可能因 down机 未提交到第三方，重新提交退款</li>
 *   <li>PROCESSING 状态：已提交到第三方但结果未确认，查询第三方结果后更新</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RefundStatusSyncScheduler {

    private final IPaymentService paymentService;
    private final PaymentConfig paymentConfig;
    private final RefundMapper refundMapper;

    @Scheduled(
            fixedDelayString = "${payment.common.refund-sync-delay-ms:30000}",
            initialDelayString = "${payment.common.refund-sync-initial-delay-ms:15000}"
    )
    public void syncProcessingRefunds() {
        if (!paymentConfig.getCommon().isRefundSyncEnabled()) {
            return;
        }

        int batchSize = Math.max(paymentConfig.getCommon().getRefundSyncBatchSize(), 1);

        // 扫描 PROCESSING 状态退款单：查询第三方结果后更新
        paymentService.syncProcessingRefunds(batchSize);

        // 扫描 PENDING 状态退款单：重新提交退款到第三方
        syncPendingRefunds(batchSize);
    }

    /**
     * 补偿 PENDING 状态的退款单
     *
     * <p>场景：退款单已创建但 down机 导致未提交到第三方。
     * 通过 syncRefundStatus 重新提交退款并更新状态。</p>
     */
    private void syncPendingRefunds(int limit) {
        List<Refund> pendingRefunds;
        try {
            pendingRefunds = refundMapper.selectByStatusesForSync(
                    List.of(RefundStatus.PENDING.getCode()), limit);
        } catch (Exception e) {
            log.error("扫描 PENDING 退款单失败", e);
            return;
        }

        if (pendingRefunds.isEmpty()) {
            return;
        }

        log.info("扫描到 {} 个 PENDING 状态退款单，尝试补偿提交", pendingRefunds.size());

        int compensated = 0;
        for (Refund refund : pendingRefunds) {
            try {
                // syncRefundStatus 内部会判断是否需要重新提交退款
                paymentService.syncRefundStatus(refund.getRefundNo());
                compensated++;
            } catch (Exception e) {
                log.warn("补偿提交 PENDING 退款单失败 - refundNo: {}, error: {}",
                        refund.getRefundNo(), e.getMessage());
            }
        }

        if (compensated > 0) {
            log.info("PENDING 退款单补偿完成：本次补偿 {} 笔", compensated);
        }
    }
}
