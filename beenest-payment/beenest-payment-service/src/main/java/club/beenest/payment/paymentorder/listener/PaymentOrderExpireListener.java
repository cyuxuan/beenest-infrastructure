package club.beenest.payment.paymentorder.listener;

import club.beenest.payment.common.constant.PaymentRedisKeyConstants;
import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.enums.PaymentOrderStatus;
import club.beenest.payment.paymentorder.strategy.PaymentStrategy;
import club.beenest.payment.paymentorder.strategy.PaymentStrategyFactory;
import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.constant.PaymentConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 支付订单过期监听器
 *
 * <p>
 * 监听Redis Key过期事件，当支付订单的过期Key被Redis自动删除时，
 * 自动将对应的PENDING订单标记为EXPIRED，并通知第三方支付平台关闭订单。
 * </p>
 *
 * <h3>工作原理：</h3>
 * <ol>
 * <li>创建支付订单时，写入Redis Key：payment:expire:{orderNo}，TTL=订单过期时间</li>
 * <li>Key过期后，Redis触发Keyspace Notification</li>
 * <li>本监听器收到事件，解析orderNo，执行订单过期处理</li>
 * </ol>
 *
 * <h3>优势（相比@Scheduled轮询）：</h3>
 * <ul>
 * <li>精确到秒级触发，无轮询延迟</li>
 * <li>无需额外线程持续轮询数据库</li>
 * <li>事件驱动，资源消耗低</li>
 * </ul>
 *
 * <h3>⚠️ 可靠性说明：</h3>
 * <p>
 * Redis Keyspace Notification 不保证100%投递（Redis重启、网络分区等场景可能丢失事件）。
 * 因此本监听器仅作为实时处理的快速路径，最终一致性由 {@code PaymentStatusCompensationScheduler}
 * 定时扫描补偿机制兜底。
 * </p>
 *
 * @author System
 * @since 2026-03-04
 */
@Slf4j
@Component
public class PaymentOrderExpireListener extends KeyExpirationEventMessageListener {

    @Autowired
    private PaymentOrderMapper paymentOrderMapper;

    @Autowired
    private PaymentStrategyFactory paymentStrategyFactory;

    @Autowired
    private PaymentEventProducer paymentEventProducer;

    public PaymentOrderExpireListener(RedisMessageListenerContainer listenerContainer) {
        super(listenerContainer);
    }

    @Override
    public void onMessage(@NonNull Message message, @Nullable byte[] pattern) {
        String expiredKey = message.toString();

        // 只处理支付订单过期Key
        if (!expiredKey.startsWith(PaymentRedisKeyConstants.PAYMENT_ORDER_EXPIRE_PREFIX)) {
            return;
        }

        String orderNo = expiredKey.substring(PaymentRedisKeyConstants.PAYMENT_ORDER_EXPIRE_PREFIX.length());
        log.info("收到支付订单过期事件 - orderNo: {}", orderNo);

        try {
            handleOrderExpire(orderNo);
        } catch (Exception e) {
            log.error("处理支付订单过期失败 - orderNo: {}, error: {}", orderNo, e.getMessage(), e);
        }
    }

    /**
     * 处理订单过期
     *
     * <p><b>安全关键</b>：使用行锁 + CAS 更新，防止与支付回调并发导致覆盖已支付状态。
     * 标记过期前会向第三方查询是否已扣款，如果已扣款则补偿为 PAID 而非 EXPIRED。</p>
     */
    private void handleOrderExpire(String orderNo) {
        // 1. 加行锁查询订单，防止与回调并发
        PaymentOrder order = paymentOrderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            log.warn("过期订单不存在 - orderNo: {}", orderNo);
            return;
        }

        // 2. 只处理PENDING状态的订单（已支付/已取消的忽略）
        if (!order.isPending()) {
            log.info("订单非待支付状态，跳过过期处理 - orderNo: {}, status: {}", orderNo, order.getStatus());
            return;
        }

        // 3. 安全关键：标记过期前向第三方查询是否已扣款
        //    防止"用户已支付但被标为EXPIRED"的资金安全问题
        if (checkThirdPartyPaid(order)) {
            log.warn("过期处理：发现第三方已扣款，补偿为PAID - orderNo: {}", orderNo);
            compensateToPaid(order);
            return;
        }

        // 4. 通知第三方支付平台关闭订单
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
            strategy.cancelPayment(order);
            log.info("已通知支付平台关闭订单 - orderNo: {}, platform: {}", orderNo, order.getPlatform());
        } catch (Exception e) {
            log.warn("通知支付平台关闭订单失败（不影响本地状态更新） - orderNo: {}, error: {}", orderNo, e.getMessage());
        }

        // 5. CAS 更新订单状态为 EXPIRED（防止覆盖回调已更新的 PAID 状态）
        int result = paymentOrderMapper.updateStatusIfCurrentStatus(
                orderNo,
                PaymentOrderStatus.PENDING.getCode(),
                PaymentOrderStatus.EXPIRED.getCode(),
                null, null);
        if (result == 1) {
            rollbackBizOrderStatus(order);
            log.info("支付订单已过期自动取消 - orderNo: {}", orderNo);
        } else {
            // CAS 失败：订单可能已被回调更新为 PAID
            PaymentOrder latestOrder = paymentOrderMapper.selectByOrderNo(orderNo);
            if (latestOrder != null && latestOrder.isPaid()) {
                log.info("过期处理时发现订单已被支付，跳过 - orderNo: {}", orderNo);
            } else {
                log.warn("过期订单CAS更新失败 - orderNo: {}, currentStatus: {}",
                        orderNo, latestOrder != null ? latestOrder.getStatus() : "unknown");
            }
        }
    }

    /**
     * 向第三方支付平台查询订单是否已扣款
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
            // 查询失败时保守处理：不标记过期，让下一轮 Scheduler 重试
            log.warn("过期处理：查询第三方支付状态失败，保守不标记过期 - orderNo: {}, error: {}",
                    order.getOrderNo(), e.getMessage());
            return false;
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
     * 补偿更新订单为已支付状态（过期处理时发现第三方已扣款）
     */
    private void compensateToPaid(PaymentOrder order) {
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
                    msg.setAppId(order.getAppId() != null ? order.getAppId() : BizTypeConstants.deriveAppId(order.getBizType()));
                    msg.setPaidAt(LocalDateTime.now().toString());
                    paymentEventProducer.sendOrderCompletedToOutbox(msg);
                    log.info("过期处理：补偿PAID后Outbox已写入 - orderNo: {}", order.getOrderNo());
                } catch (Exception e) {
                    log.error("过期处理：补偿PAID后Outbox写入失败 - orderNo: {}, error: {}",
                            order.getOrderNo(), e.getMessage(), e);
                }
            }
        } else {
            log.info("过期处理：补偿PAID的CAS更新失败（可能已被回调更新）- orderNo: {}", order.getOrderNo());
        }
    }

    private void rollbackBizOrderStatus(PaymentOrder order) {
        if (order == null) {
            return;
        }
        String bizNo = order.getBizNo();
        if (!StringUtils.hasText(bizNo)) {
            return;
        }
        // 写入 Outbox：订单过期取消，由业务系统自行回滚计划状态
        try {
            PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
            msg.setOrderNo(order.getOrderNo());
            msg.setBusinessOrderNo(bizNo);
            msg.setCustomerNo(order.getCustomerNo());
            msg.setAmountFen(order.getAmount());
            msg.setPlatform(order.getPlatform());
            msg.setBizType(order.getBizType());
            msg.setAppId(order.getAppId() != null ? order.getAppId() : BizTypeConstants.deriveAppId(order.getBizType()));
            msg.setPaidAt(null); // 未支付
            paymentEventProducer.sendOrderCancelledToOutbox(msg);
            log.info("已写入订单过期取消Outbox消息 - orderNo: {}, bizNo: {}", order.getOrderNo(), bizNo);
        } catch (Exception e) {
            log.error("写入订单过期取消Outbox消息失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage(), e);
        }
    }
}
