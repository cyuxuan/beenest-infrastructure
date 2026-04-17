package club.beenest.payment.constant;

public final class PaymentConstants {

    private PaymentConstants() {
    }

    public static final String ORDER_PLAN_PREFIX = "ORDER_PLAN:";
    public static final String COUPON_PREFIX = "|COUPON:";
    public static final String RECHARGE_PREFIX = "RECHARGE_";

    public static final int MAX_RETRY_COUNT = 3;
    public static final int ORDER_NO_MAX_RETRY = 5;
    public static final int AUTO_REFUND_SYNC_MAX_ATTEMPTS = 3;

    public static final long MIN_RECHARGE_AMOUNT_FEN = 100L;
    public static final long MAX_RECHARGE_AMOUNT_FEN = 10_000_000L;
    public static final long MIN_WITHDRAW_AMOUNT_FEN = 10000L;
    public static final long MAX_WITHDRAW_AMOUNT_FEN = 1_000_000_000L;

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public static final String PLATFORM_WECHAT = "WECHAT";
    public static final String PLATFORM_ALIPAY = "ALIPAY";
    public static final String PLATFORM_DOUYIN = "DOUYIN";

    public static final String METHOD_WECHAT_MINI = "WECHAT_MINI";
    public static final String METHOD_ALIPAY_MINI = "ALIPAY_MINI";
    public static final String METHOD_DOUYIN_MINI = "DOUYIN_MINI";

    public static final String WITHDRAW_TYPE_ALIPAY = "ALIPAY";
    public static final String WITHDRAW_TYPE_BANK_CARD = "BANK_CARD";

    public static final String ACCOUNT_TYPE_PERSONAL = "PERSONAL";
    public static final String ACCOUNT_TYPE_COMPANY = "COMPANY";

    public static final String SOURCE_EXCHANGE = "EXCHANGE";
    public static final String SOURCE_CLAIM = "CLAIM";
    public static final String SOURCE_ADMIN = "ADMIN";
    public static final String SOURCE_SYSTEM = "SYSTEM";

    public static final String CALLBACK_SUCCESS = "SUCCESS";
    public static final String CALLBACK_SOURCE = "CALLBACK";
    public static final String LOCAL_SOURCE = "LOCAL";
    public static final String RESUBMIT_SOURCE = "RESUBMIT";
    public static final String CHANNEL_SOURCE = "CHANNEL";
    public static final String MANUAL_SYNC_SOURCE = "MANUAL_SYNC";

    public static final String COUPON_STATUS_UNUSED = "UNUSED";
    public static final String COUPON_STATUS_ACTIVE = "ACTIVE";
    public static final String COUPON_TYPE_DISCOUNT = "DISCOUNT";

    public static final String PAYMENT_STATUS_PAID = "PAID";
    public static final String PAYMENT_STATUS_PENDING = "PENDING";

    public static final String TRANSACTION_TYPE_RECHARGE = "RECHARGE";

    public static final String REFUND_STATUS_SUCCESS = "SUCCESS";
    public static final String REFUND_STATUS_TRADE_SUCCESS = "TRADE_SUCCESS";
    public static final String REFUND_STATUS_TRADE_FINISHED = "TRADE_FINISHED";
    public static final String REFUND_STATUS_PAY_SUCCESS = "PAY_SUCCESS";
    public static final String REFUND_STATUS_FAILED = "FAILED";
    public static final String REFUND_STATUS_CLOSED = "CLOSED";
    public static final String REFUND_STATUS_ABNORMAL = "ABNORMAL";
    public static final String REFUND_STATUS_REJECTED = "REJECTED";
    public static final String REFUND_STATUS_PROCESSING = "PROCESSING";
    public static final String REFUND_STATUS_REFUND_SUCCESS = "REFUND_SUCCESS";
    public static final String REFUND_STATUS_FINISHED = "FINISHED";
    public static final String REFUND_STATUS_ERROR = "ERROR";
    public static final String REFUND_STATUS_FAIL = "FAIL";

    public static final String REFUND_POLICY_AUTO_REFUND = "AUTO_REFUND";
    public static final String REFUND_POLICY_MANUAL_REVIEW = "MANUAL_REVIEW";

    public static final String REFUND_SOURCE_ADMIN = "ADMIN";
    public static final String REFUND_SOURCE_ADMIN_REVIEW = "ADMIN_REVIEW";
    public static final String REFUND_SOURCE_CUSTOMER_CANCEL = "CUSTOMER_CANCEL";

    public static final int MAX_MESSAGE_LENGTH = 500;

    public static final int YUAN_TO_FEN_MULTIPLIER = 100;

    public static final String CURRENCY_CNY = "CNY";

    public static final String RECONCILIATION_STATUS_PROCESSING = "PROCESSING";
    public static final String RECONCILIATION_STATUS_UNKNOWN = "UNKNOWN";
    public static final String RECONCILIATION_STATUS_NOT_EXIST = "NOT_EXIST";

    public static final String HTTP_METHOD_POST = "POST";

    public static final String WECHAT_SOURCE_MINIAPP = "MINIAPP";

    public static final String REFUND_ROLLBACK_SUFFIX = "_ROLLBACK";

    public static final String WECHAT_ERROR_RESOURCE_NOT_EXISTS = "RESOURCE_NOT_EXISTS";

    public static final String ALIPAY_TRADE_WAIT_BUYER_PAY = "WAIT_BUYER_PAY";
    public static final String ALIPAY_TRADE_CLOSED = "TRADE_CLOSED";
    public static final String ALIPAY_TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";
    public static final String ALIPAY_FUND_CHANGE_YES = "Y";

    public static final String TRANSACTION_TYPE_WITHDRAW = "WITHDRAW";
    public static final String TRANSACTION_TYPE_PAYMENT = "PAYMENT";
    public static final String TRANSACTION_TYPE_REFUND = "REFUND";
    public static final String TRANSACTION_TYPE_RED_PACKET_CONVERT = "RED_PACKET_CONVERT";
    public static final String TRANSACTION_TYPE_FEE = "FEE";
    public static final String TRANSACTION_TYPE_PENALTY = "PENALTY";

    public static String getPaymentMethod(String platform) {
        if (platform == null) {
            return null;
        }
        return switch (platform.toUpperCase()) {
            case PLATFORM_WECHAT -> METHOD_WECHAT_MINI;
            case PLATFORM_ALIPAY -> METHOD_ALIPAY_MINI;
            case PLATFORM_DOUYIN -> METHOD_DOUYIN_MINI;
            default -> platform.toUpperCase() + "_MINI";
        };
    }
}
