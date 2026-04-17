package club.beenest.payment.mq;

/**
 * 支付中台 RabbitMQ 常量定义
 * Exchange / Queue / RoutingKey 统一在此定义
 */
public final class PaymentMqConstants {

    private PaymentMqConstants() {
    }

    // ==================== Exchange ====================

    /** 支付事件交换机 */
    public static final String PAYMENT_EXCHANGE = "payment.exchange";

    // ==================== Routing Keys ====================

    /** 支付订单完成 */
    public static final String RK_PAYMENT_ORDER_COMPLETED = "payment.order.completed";
    /** 支付订单取消/超时 */
    public static final String RK_PAYMENT_ORDER_CANCELLED = "payment.order.cancelled";
    /** 退款完成 */
    public static final String RK_REFUND_COMPLETED = "payment.refund.completed";
    /** 提现完成 */
    public static final String RK_WITHDRAW_COMPLETED = "payment.withdraw.completed";
    /** 余额变动 */
    public static final String RK_BALANCE_CHANGED = "payment.balance.changed";
    /** 飞手结算 */
    public static final String RK_PILOT_SETTLEMENT = "payment.pilot.settlement";

    // ==================== Queues ====================

    /** 主业务服务 - 支付订单完成队列 */
    public static final String QUEUE_ORDER_COMPLETED = "payment.order.completed.queue";
    /** 主业务服务 - 支付订单取消队列 */
    public static final String QUEUE_ORDER_CANCELLED = "payment.order.cancelled.queue";
    /** 主业务服务 - 退款完成队列 */
    public static final String QUEUE_REFUND_COMPLETED = "payment.refund.completed.queue";
    /** 主业务服务 - 提现完成队列 */
    public static final String QUEUE_WITHDRAW_COMPLETED = "payment.withdraw.completed.queue";
    /** 主业务服务 - 余额变动队列 */
    public static final String QUEUE_BALANCE_CHANGED = "payment.balance.changed.queue";
    /** 支付中台 - 飞手结算队列 */
    public static final String QUEUE_PILOT_SETTLEMENT = "payment.pilot.settlement.queue";

    // ==================== Dead Letter Queues ====================

    /** 死信队列 - 支付订单完成 */
    public static final String DLQ_ORDER_COMPLETED = "payment.order.completed.dlq";
    /** 死信队列 - 支付订单取消 */
    public static final String DLQ_ORDER_CANCELLED = "payment.order.cancelled.dlq";
    /** 死信队列 - 退款完成 */
    public static final String DLQ_REFUND_COMPLETED = "payment.refund.completed.dlq";
    /** 死信队列 - 提现完成 */
    public static final String DLQ_WITHDRAW_COMPLETED = "payment.withdraw.completed.dlq";
    /** 死信队列 - 余额变动 */
    public static final String DLQ_BALANCE_CHANGED = "payment.balance.changed.dlq";
    /** 死信队列 - 飞手结算 */
    public static final String DLQ_PILOT_SETTLEMENT = "payment.pilot.settlement.dlq";
}
