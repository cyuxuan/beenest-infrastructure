package club.beenest.payment.paymentorder.scheduler;

import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.enums.PaymentOrderStatus;
import club.beenest.payment.paymentorder.strategy.PaymentStrategy;
import club.beenest.payment.paymentorder.strategy.PaymentStrategyFactory;
import club.beenest.payment.shared.constant.PaymentConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 支付订单过期定时任务
 * 每分钟扫描已过期但仍处于 PENDING 状态的订单，将其标记为 EXPIRED
 *
 * <p><b>安全关键</b>：标记过期前会向第三方支付平台查询订单状态，
 * 如果第三方已扣款则不会标记过期，而是补偿更新为 PAID。
 * 这防止了"用户已支付但被标为EXPIRED"的资金安全问题。</p>
 *
 * <p>同时通知业务系统取消关联订单（写入 Outbox，由 Scheduler 补偿发送）。</p>
 *
 * @author System
 * @since 2026-04-08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOrderExpireScheduler {

    private final PaymentOrderMapper paymentOrderMapper;
    private final PaymentEventProducer paymentEventProducer;
    private final PaymentStrategyFactory paymentStrategyFactory;

    private static final int BATCH_SIZE = 100;

    /**
     * 每分钟执行一次过期订单扫描
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expirePendingOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<PaymentOrder> expiredOrders;
        try {
            expiredOrders = paymentOrderMapper.selectExpiredOrders(now, BATCH_SIZE);
            if (expiredOrders.isEmpty()) {
                return;
            }
            log.info("扫描到 {} 个过期待支付订单", expiredOrders.size());
        } catch (Exception e) {
            log.error("扫描过期订单异常", e);
            return;
        }

        // 逐笔处理：先查第三方状态，再决定标记过期还是补偿为已支付
        List<String> toExpire = new ArrayList<>();
        for (PaymentOrder order : expiredOrders) {
            try {
                // 安全关键：标记过期前向第三方查询是否已扣款
                if (checkThirdPartyPaid(order)) {
                    // 第三方已扣款，补偿更新为 PAID
                    compensateToPaid(order);
                    continue;
                }
                // 第三方未扣款，加入待过期列表
                toExpire.add(order.getOrderNo());
            } catch (Exception e) {
                // 查询失败时保守处理：加入待过期列表
                // 但记录告警，因为可能误把已扣款订单标过期
                log.warn("过期处理：查询第三方状态失败，保守标记过期 - orderNo: {}, error: {}",
                        order.getOrderNo(), e.getMessage());
                toExpire.add(order.getOrderNo());
            }
        }

        if (toExpire.isEmpty()) {
            return;
        }

        // 批量更新过期状态（SQL 有 AND status = 'PENDING' 条件保护）
        int updated = paymentOrderMapper.batchUpdateExpiredOrders(toExpire);
        if (updated > 0) {
            log.info("已将 {} 个过期订单标记为 EXPIRED", updated);
            // 对每笔过期订单通知业务系统取消
            // 注意：只通知实际被标记过期的订单（排除已被补偿为PAID的）
            notifyBizOrdersExpired(toExpire);
        }
    }

    /**
     * 向第三方支付平台查询订单是否已扣款
     *
     * @return true 表示第三方已扣款
     */
    private boolean checkThirdPartyPaid(PaymentOrder order) {
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
            Map<String, Object> platformStatus = strategy.queryPayment(order);
            if (platformStatus == null) {
                return false;
            }
            String status = platformStatus.get("platformStatus") == null ? null
                    : platformStatus.get("platformStatus").toString();
            return isPlatformPaid(status);
        } catch (Exception e) {
            log.warn("过期处理：查询第三方支付状态失败 - orderNo: {}, platform: {}, error: {}",
                    order.getOrderNo(), order.getPlatform(), e.getMessage());
            return false;
        }
    }

    /**
     * 补偿更新订单为已支付状态
     *
     * <p>场景：用户已支付但回调丢失，订单还在 PENDING 状态，
     * 过期定时器发现后不标记过期，而是补偿为 PAID。</p>
     */
    private void compensateToPaid(PaymentOrder order) {
        log.warn("过期处理：发现第三方已扣款，补偿为PAID - orderNo: {}, platform: {}",
                order.getOrderNo(), order.getPlatform());

        // CAS 更新：PENDING → PAID
        int result = paymentOrderMapper.updateStatusIfCurrentStatus(
                order.getOrderNo(),
                PaymentOrderStatus.PENDING.getCode(),
                PaymentOrderStatus.PAID.getCode(),
                null, null);

        if (result == 1) {
            log.info("过期处理：补偿更新PAID成功 - orderNo: {}", order.getOrderNo());

            // 补写 Outbox 通知业务系统
            String bizNo = order.getBizNo();
            if (StringUtils.hasText(bizNo)) {
                try {
                    PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
                    msg.setOrderNo(order.getOrderNo());
                    msg.setBusinessOrderNo(bizNo);
                    msg.setCustomerNo(order.getCustomerNo());
                    msg.setAmountFen(order.getAmount());
                    msg.setPlatform(order.getPlatform());
                    msg.setBizType(order.getBizType());
                    msg.setPaidAt(LocalDateTime.now().toString());
                    paymentEventProducer.sendOrderCompletedToOutbox(msg);
                    log.info("过期处理：补偿PAID后Outbox已写入 - orderNo: {}, bizNo: {}", order.getOrderNo(), bizNo);
                } catch (Exception e) {
                    log.error("过期处理：补偿PAID后Outbox写入失败 - orderNo: {}, error: {}",
                            order.getOrderNo(), e.getMessage(), e);
                }
            }
        } else {
            log.info("过期处理：补偿PAID的CAS更新失败（可能已被回调更新）- orderNo: {}", order.getOrderNo());
        }
    }

    /**
     * 判断第三方返回的状态是否为已支付
     */
    private boolean isPlatformPaid(String platformStatus) {
        if (!StringUtils.hasText(platformStatus)) {
            return false;
        }
        String normalized = platformStatus.trim().toUpperCase();
        return PaymentConstants.REFUND_STATUS_SUCCESS.equals(normalized)
                || PaymentConstants.REFUND_STATUS_TRADE_SUCCESS.equals(normalized)
                || PaymentConstants.REFUND_STATUS_TRADE_FINISHED.equals(normalized)
                || PaymentConstants.REFUND_STATUS_PAY_SUCCESS.equals(normalized);
    }

    /**
     * 通知业务系统关联订单已过期
     */
    private void notifyBizOrdersExpired(List<String> expiredOrderNos) {
        for (String orderNo : expiredOrderNos) {
            try {
                // 查询最新状态，确认确实是 EXPIRED 再发通知
                PaymentOrder order = paymentOrderMapper.selectByOrderNo(orderNo);
                if (order == null || !PaymentOrderStatus.EXPIRED.getCode().equals(order.getStatus())) {
                    continue;
                }
                String bizNo = order.getBizNo();
                if (!StringUtils.hasText(bizNo)) {
                    continue;
                }
                PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
                msg.setOrderNo(order.getOrderNo());
                msg.setBusinessOrderNo(bizNo);
                msg.setCustomerNo(order.getCustomerNo());
                msg.setAmountFen(order.getAmount());
                msg.setPlatform(order.getPlatform());
                msg.setBizType(order.getBizType());
                msg.setPaidAt(null);
                paymentEventProducer.sendOrderCancelledToOutbox(msg);
                log.info("过期订单取消消息已写入Outbox - orderNo: {}, bizNo: {}", order.getOrderNo(), bizNo);
            } catch (Exception e) {
                log.error("写入过期订单取消Outbox失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
            }
        }
    }
}
