package club.beenest.payment.common.constant;

/**
 * 支付平台 Redis Key 常量
 */
public final class PaymentRedisKeyConstants {

    private PaymentRedisKeyConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // ==================== 时间常量 ====================

    public static final long TTL_ONE_MINUTE = 60L;
    public static final long TTL_FIVE_MINUTES = 300L;
    public static final long TTL_TEN_MINUTES = 600L;
    public static final long TTL_THIRTY_MINUTES = 1800L;
    public static final long TTL_ONE_HOUR = 3600L;
    public static final long TTL_ONE_DAY = 86400L;
    public static final long TTL_SEVEN_DAYS = 604800L;

    // ==================== 支付订单过期 ====================

    public static final String PAYMENT_ORDER_EXPIRE_PREFIX = "payment:expire:";

    public static String buildPaymentOrderExpireKey(String orderNo) {
        return PAYMENT_ORDER_EXPIRE_PREFIX + orderNo;
    }

    // ==================== 分布式锁 ====================

    public static final String LOCK_PREFIX = "lock:";

    public static String buildLockKey(String resource) {
        return LOCK_PREFIX + resource;
    }

    // ==================== 提现限额检查 ====================

    public static final String WITHDRAW_DAILY_TOTAL_PREFIX = "withdraw:limit:daily_total:";
    public static final String WITHDRAW_DAILY_USER_PREFIX = "withdraw:limit:daily_user:";

    public static String buildWithdrawDailyTotalKey(String platform, String date) {
        return WITHDRAW_DAILY_TOTAL_PREFIX + platform + ":" + date;
    }

    public static String buildWithdrawDailyUserKey(String platform, String userNo, String date) {
        return WITHDRAW_DAILY_USER_PREFIX + platform + ":" + userNo + ":" + date;
    }

    // ==================== 提现风控检查 ====================

    public static final String WITHDRAW_RISK_PREFIX = "withdraw:risk:";

    public static String buildWithdrawRiskKey(String ruleCode, String userNo, long timestamp) {
        return WITHDRAW_RISK_PREFIX + ruleCode + ":" + userNo + ":" + timestamp;
    }

    // ==================== 限流 ====================

    public static final String RATE_LIMIT_API_PREFIX = "rate_limit:api:";
    public static final String RATE_LIMIT_USER_PREFIX = "rate_limit:user:";

    public static String buildRateLimitApiKey(String apiPath, String identifier) {
        return RATE_LIMIT_API_PREFIX + apiPath + ":" + identifier;
    }

    public static String buildRateLimitUserKey(String userId, String operation) {
        return RATE_LIMIT_USER_PREFIX + userId + ":" + operation;
    }

    // ==================== 缓存 ====================

    public static final String CACHE_PREFIX = "cache:";

    public static String buildCacheKey(String module, String key) {
        return CACHE_PREFIX + module + ":" + key;
    }

    // ==================== 计数器 ====================

    public static final String COUNTER_PREFIX = "counter:";

    public static String buildCounterKey(String type, String identifier) {
        return COUNTER_PREFIX + type + ":" + identifier;
    }
}
