package club.beenest.payment.constant;

/**
 * 支付重试相关常量
 *
 * <p>定义支付系统重试机制的延迟时间和次数配置</p>
 *
 * @author System
 * @since 2026-03-26
 */
public final class PaymentRetryConstants {

    private PaymentRetryConstants() {
        // 防止实例化
    }

    /**
     * 首次重试延迟（秒）
     */
    public static final long FIRST_RETRY_DELAY_SECONDS = 2L;

    /**
     * 第二次重试延迟（秒）
     */
    public static final long SECOND_RETRY_DELAY_SECONDS = 10L;

    /**
     * 默认重试延迟（秒）
     */
    public static final long DEFAULT_RETRY_DELAY_SECONDS = 30L;

    /**
     * 最大重试次数
     */
    public static final int MAX_RETRY_ATTEMPTS = 3;

    /**
     * 订单号生成最大重试次数
     */
    public static final int ORDER_NO_MAX_RETRY = 5;

    /**
     * 根据重试次数获取对应的延迟时间
     *
     * @param attempt 当前重试次数（从1开始）
     * @return 延迟时间（秒）
     */
    public static long getDelaySeconds(int attempt) {
        return switch (attempt) {
            case 1 -> FIRST_RETRY_DELAY_SECONDS;
            case 2 -> SECOND_RETRY_DELAY_SECONDS;
            default -> DEFAULT_RETRY_DELAY_SECONDS;
        };
    }
}
