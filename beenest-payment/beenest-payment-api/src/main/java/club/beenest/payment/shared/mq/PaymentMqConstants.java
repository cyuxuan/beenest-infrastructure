package club.beenest.payment.shared.mq;

import java.util.List;

/**
 * 支付中台 RabbitMQ 常量定义
 *
 * <p>多租户隔离设计：</p>
 * <ul>
 *   <li>Exchange 类型为 Topic，路由键带 appId 后缀实现租户物理隔离</li>
 *   <li>队列名由 {@link #tenantQueueName(String, String)} 统一推导，避免消费方之间命名冲突</li>
 *   <li>新增租户只需 INSERT ds_app_credential，支付中台自动声明队列，零代码改动</li>
 * </ul>
 */
public final class PaymentMqConstants {

    private PaymentMqConstants() {
    }

    // ==================== Exchange ====================

    /** 支付事件交换机（Topic 类型，支持租户路由键通配） */
    public static final String PAYMENT_EXCHANGE = "payment.exchange";

    // ==================== 基础路由键（不含租户后缀） ====================

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
    /** 钱包入账指令（业务系统 → 支付中台） */
    public static final String RK_WALLET_CREDIT = "payment.wallet.credit";

    /** 所有路由键基础名（含出站 + 入站），用于遍历声明队列 */
    public static final List<String> ALL_ROUTING_KEYS = List.of(
            RK_PAYMENT_ORDER_COMPLETED,
            RK_PAYMENT_ORDER_CANCELLED,
            RK_REFUND_COMPLETED,
            RK_WITHDRAW_COMPLETED,
            RK_BALANCE_CHANGED,
            RK_WALLET_CREDIT
    );

    // ==================== 基础队列名（不含租户后缀，仅用于推导） ====================

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
    /** 支付中台 - 钱包入账指令队列 */
    public static final String QUEUE_WALLET_CREDIT = "payment.wallet.credit.queue";

    // ==================== 基础死信队列名（不含租户后缀，仅用于推导） ====================

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
    /** 死信队列 - 钱包入账指令 */
    public static final String DLQ_WALLET_CREDIT = "payment.wallet.credit.dlq";

    // ==================== 租户隔离命名工具方法 ====================

    /**
     * 构建带租户后缀的路由键
     *
     * <p>例：payment.order.completed + DRONE → payment.order.completed.DRONE</p>
     *
     * @param baseRoutingKey 基础路由键
     * @param appId          业务系统标识
     * @return 带租户后缀的路由键
     */
    public static String tenantRoutingKey(String baseRoutingKey, String appId) {
        return baseRoutingKey + "." + appId;
    }

    /**
     * 构建租户专属队列名
     *
     * <p>例：payment.order.completed.queue + DRONE → payment.order.completed.drone.queue</p>
     * <p>appId 在队列名中用小写（RabbitMQ 命名惯例），路由键中保留原始大小写</p>
     *
     * @param baseQueue 基础队列名（以 .queue 结尾）
     * @param appId     业务系统标识
     * @return 租户专属队列名
     */
    public static String tenantQueueName(String baseQueue, String appId) {
        String suffix = ".queue";
        if (baseQueue.endsWith(suffix)) {
            return baseQueue.substring(0, baseQueue.length() - suffix.length())
                    + "." + appId.toLowerCase() + suffix;
        }
        return baseQueue + "." + appId.toLowerCase();
    }

    /**
     * 构建租户专属 DLQ 名
     *
     * <p>例：payment.order.completed.dlq + DRONE → payment.order.completed.drone.dlq</p>
     *
     * @param baseDlq 基础 DLQ 名（以 .dlq 结尾）
     * @param appId   业务系统标识
     * @return 租户专属 DLQ 名
     */
    public static String tenantDlqName(String baseDlq, String appId) {
        String suffix = ".dlq";
        if (baseDlq.endsWith(suffix)) {
            return baseDlq.substring(0, baseDlq.length() - suffix.length())
                    + "." + appId.toLowerCase() + suffix;
        }
        return baseDlq + "." + appId.toLowerCase();
    }

    /**
     * 构建租户专属 DLX 路由键
     *
     * <p>例：dlx.order.completed + DRONE → dlx.order.completed.DRONE</p>
     *
     * @param baseDlxRoutingKey 基础 DLX 路由键
     * @param appId             业务系统标识
     * @return 带租户后缀的 DLX 路由键
     */
    public static String tenantDlxRoutingKey(String baseDlxRoutingKey, String appId) {
        return baseDlxRoutingKey + "." + appId;
    }

    /**
     * 从基础路由键推导基础队列名
     *
     * <p>例：payment.order.completed → payment.order.completed.queue</p>
     *
     * @param baseRoutingKey 基础路由键
     * @return 基础队列名
     */
    public static String routingKeyToQueueName(String baseRoutingKey) {
        return baseRoutingKey + ".queue";
    }

    /**
     * 从基础路由键推导基础 DLQ 名
     *
     * <p>例：payment.order.completed → payment.order.completed.dlq</p>
     *
     * @param baseRoutingKey 基础路由键
     * @return 基础 DLQ 名
     */
    public static String routingKeyToDlqName(String baseRoutingKey) {
        return baseRoutingKey + ".dlq";
    }

    /**
     * 从基础路由键提取 DLX 路由键片段
     *
     * <p>例：payment.order.completed → order.completed</p>
     *
     * @param baseRoutingKey 基础路由键
     * @return DLX 路由键片段
     */
    public static String extractDlxSegment(String baseRoutingKey) {
        return baseRoutingKey.substring("payment.".length());
    }
}
