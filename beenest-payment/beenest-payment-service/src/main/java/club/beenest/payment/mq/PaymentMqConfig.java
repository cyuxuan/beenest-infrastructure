package club.beenest.payment.mq;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付中台 RabbitMQ 配置
 * 定义 Exchange、Queue、Binding、死信队列
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li>所有业务队列绑定死信交换机，消费失败的消息进入死信队列</li>
 *   <li>死信队列消息由人工处理或补偿任务定期扫描</li>
 *   <li>消息消费端配合 spring.rabbitmq.listener.simple.retry 实现退避重试</li>
 * </ul>
 */
@Configuration
public class PaymentMqConfig {

    @Value("${payment.mq.sign-secret:}")
    private String mqSignSecret;

    @PostConstruct
    public void init() {
        if (mqSignSecret != null && !mqSignSecret.isEmpty()) {
            MessageSignUtil.setSecret(mqSignSecret);
        }
    }

    // ==================== 死信常量 ====================

    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "payment.exchange.dlx";
    /** 死信路由键前缀 */
    private static final String DLX_ROUTING_KEY_PREFIX = "dlx.";
    /** 消息 TTL（毫秒），超过未被消费则进入死信队列。设 24 小时 */
    private static final int MESSAGE_TTL_MS = 24 * 60 * 60 * 1000;

    // ==================== Exchange ====================

    @Bean
    public DirectExchange paymentExchange() {
        return ExchangeBuilder.directExchange(PaymentMqConstants.PAYMENT_EXCHANGE)
                .durable(true)
                .build();
    }

    /** 死信交换机 */
    @Bean
    public DirectExchange paymentDlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    // ==================== 死信队列 ====================

    @Bean
    public Queue dlqOrderCompleted() {
        return QueueBuilder.durable("payment.order.completed.dlq").build();
    }

    @Bean
    public Queue dlqOrderCancelled() {
        return QueueBuilder.durable("payment.order.cancelled.dlq").build();
    }

    @Bean
    public Queue dlqRefundCompleted() {
        return QueueBuilder.durable("payment.refund.completed.dlq").build();
    }

    @Bean
    public Queue dlqWithdrawCompleted() {
        return QueueBuilder.durable("payment.withdraw.completed.dlq").build();
    }

    @Bean
    public Queue dlqBalanceChanged() {
        return QueueBuilder.durable("payment.balance.changed.dlq").build();
    }

    @Bean
    public Queue dlqPilotSettlement() {
        return QueueBuilder.durable(PaymentMqConstants.DLQ_PILOT_SETTLEMENT).build();
    }

    // ==================== 死信绑定 ====================

    @Bean
    public Binding dlqOrderCompletedBinding(Queue dlqOrderCompleted, DirectExchange paymentDlxExchange) {
        return BindingBuilder.bind(dlqOrderCompleted).to(paymentDlxExchange).with(DLX_ROUTING_KEY_PREFIX + "order.completed");
    }

    @Bean
    public Binding dlqOrderCancelledBinding(Queue dlqOrderCancelled, DirectExchange paymentDlxExchange) {
        return BindingBuilder.bind(dlqOrderCancelled).to(paymentDlxExchange).with(DLX_ROUTING_KEY_PREFIX + "order.cancelled");
    }

    @Bean
    public Binding dlqRefundCompletedBinding(Queue dlqRefundCompleted, DirectExchange paymentDlxExchange) {
        return BindingBuilder.bind(dlqRefundCompleted).to(paymentDlxExchange).with(DLX_ROUTING_KEY_PREFIX + "refund.completed");
    }

    @Bean
    public Binding dlqWithdrawCompletedBinding(Queue dlqWithdrawCompleted, DirectExchange paymentDlxExchange) {
        return BindingBuilder.bind(dlqWithdrawCompleted).to(paymentDlxExchange).with(DLX_ROUTING_KEY_PREFIX + "withdraw.completed");
    }

    @Bean
    public Binding dlqBalanceChangedBinding(Queue dlqBalanceChanged, DirectExchange paymentDlxExchange) {
        return BindingBuilder.bind(dlqBalanceChanged).to(paymentDlxExchange).with(DLX_ROUTING_KEY_PREFIX + "balance.changed");
    }

    @Bean
    public Binding dlqPilotSettlementBinding(Queue dlqPilotSettlement, DirectExchange paymentDlxExchange) {
        return BindingBuilder.bind(dlqPilotSettlement).to(paymentDlxExchange).with(DLX_ROUTING_KEY_PREFIX + "pilot.settlement");
    }

    // ==================== 业务队列（带死信配置） ====================

    @Bean
    public Queue orderCompletedQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_ORDER_COMPLETED)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY_PREFIX + "order.completed")
                .build();
    }

    @Bean
    public Queue orderCancelledQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_ORDER_CANCELLED)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY_PREFIX + "order.cancelled")
                .build();
    }

    @Bean
    public Queue refundCompletedQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_REFUND_COMPLETED)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY_PREFIX + "refund.completed")
                .build();
    }

    @Bean
    public Queue withdrawCompletedQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_WITHDRAW_COMPLETED)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY_PREFIX + "withdraw.completed")
                .build();
    }

    @Bean
    public Queue balanceChangedQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_BALANCE_CHANGED)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY_PREFIX + "balance.changed")
                .build();
    }

    @Bean
    public Queue pilotSettlementQueue() {
        return QueueBuilder.durable(PaymentMqConstants.QUEUE_PILOT_SETTLEMENT)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY_PREFIX + "pilot.settlement")
                .build();
    }

    // ==================== 业务绑定 ====================

    @Bean
    public Binding orderCompletedBinding(Queue orderCompletedQueue, DirectExchange paymentExchange) {
        return BindingBuilder.bind(orderCompletedQueue).to(paymentExchange).with(PaymentMqConstants.RK_PAYMENT_ORDER_COMPLETED);
    }

    @Bean
    public Binding orderCancelledBinding(Queue orderCancelledQueue, DirectExchange paymentExchange) {
        return BindingBuilder.bind(orderCancelledQueue).to(paymentExchange).with(PaymentMqConstants.RK_PAYMENT_ORDER_CANCELLED);
    }

    @Bean
    public Binding refundCompletedBinding(Queue refundCompletedQueue, DirectExchange paymentExchange) {
        return BindingBuilder.bind(refundCompletedQueue).to(paymentExchange).with(PaymentMqConstants.RK_REFUND_COMPLETED);
    }

    @Bean
    public Binding withdrawCompletedBinding(Queue withdrawCompletedQueue, DirectExchange paymentExchange) {
        return BindingBuilder.bind(withdrawCompletedQueue).to(paymentExchange).with(PaymentMqConstants.RK_WITHDRAW_COMPLETED);
    }

    @Bean
    public Binding balanceChangedBinding(Queue balanceChangedQueue, DirectExchange paymentExchange) {
        return BindingBuilder.bind(balanceChangedQueue).to(paymentExchange).with(PaymentMqConstants.RK_BALANCE_CHANGED);
    }

    @Bean
    public Binding pilotSettlementBinding(Queue pilotSettlementQueue, DirectExchange paymentExchange) {
        return BindingBuilder.bind(pilotSettlementQueue).to(paymentExchange).with(PaymentMqConstants.RK_PILOT_SETTLEMENT);
    }
}
