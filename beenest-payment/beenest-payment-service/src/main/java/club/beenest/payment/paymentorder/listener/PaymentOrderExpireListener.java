package club.beenest.payment.paymentorder.listener;

import club.beenest.payment.common.constant.PaymentRedisKeyConstants;
import club.beenest.payment.paymentorder.mq.PaymentOrderCompletedMessage;
import club.beenest.payment.paymentorder.mq.producer.PaymentEventProducer;
import club.beenest.payment.paymentorder.mapper.PaymentOrderMapper;
import club.beenest.payment.paymentorder.domain.entity.PaymentOrder;
import club.beenest.payment.paymentorder.domain.enums.PaymentOrderStatus;
import club.beenest.payment.paymentorder.strategy.PaymentStrategy;
import club.beenest.payment.paymentorder.strategy.PaymentStrategyFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
     */
    private void handleOrderExpire(String orderNo) {
        // 1. 查询订单
        PaymentOrder order = paymentOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            log.warn("过期订单不存在 - orderNo: {}", orderNo);
            return;
        }

        // 2. 只处理PENDING状态的订单（已支付/已取消的忽略）
        if (!order.isPending()) {
            log.info("订单非待支付状态，跳过过期处理 - orderNo: {}, status: {}", orderNo, order.getStatus());
            return;
        }

        // 3. 通知第三方支付平台关闭订单
        try {
            PaymentStrategy strategy = paymentStrategyFactory.getStrategy(order.getPlatform());
            strategy.cancelPayment(order);
            log.info("已通知支付平台关闭订单 - orderNo: {}, platform: {}", orderNo, order.getPlatform());
        } catch (Exception e) {
            log.warn("通知支付平台关闭订单失败（不影响本地状态更新） - orderNo: {}, error: {}", orderNo, e.getMessage());
        }

        // 4. 更新订单状态为EXPIRED
        int result = paymentOrderMapper.updateStatus(orderNo, PaymentOrderStatus.EXPIRED.getCode(), null, null);
        if (result == 1) {
            rollbackBizOrderStatus(order);
            log.info("支付订单已过期自动取消 - orderNo: {}", orderNo);
        } else {
            log.error("更新过期订单状态失败 - orderNo: {}", orderNo);
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
        // 发布订单取消MQ消息，由业务系统自行回滚计划状态
        try {
            PaymentOrderCompletedMessage msg = new PaymentOrderCompletedMessage();
            msg.setOrderNo(order.getOrderNo());
            msg.setBusinessOrderNo(bizNo);
            msg.setCustomerNo(order.getCustomerNo());
            msg.setAmountFen(order.getAmount());
            msg.setPlatform(order.getPlatform());
            msg.setPaidAt(null); // 未支付
            paymentEventProducer.sendOrderCancelled(msg);
            log.info("已发送订单过期取消MQ消息 - orderNo: {}, bizNo: {}", order.getOrderNo(), bizNo);
        } catch (Exception e) {
            log.error("发送订单过期取消MQ消息失败 - orderNo: {}, error: {}", order.getOrderNo(), e.getMessage(), e);
        }
    }
}
