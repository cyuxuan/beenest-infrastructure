package org.apereo.cas.beenest.common.constant;

/**
 * CAS 协议相关常量
 * <p>
 * 用户状态编码：1=正常, 2=锁定, 3=禁用, 4=删除
 */
public final class CasConstant {

    private CasConstant() {}

    // ===== 用户状态（与数据库 cas_user.status 字段一致） =====
    public static final int USER_STATUS_ACTIVE = 1;
    public static final int USER_STATUS_LOCKED = 2;
    public static final int USER_STATUS_DISABLED = 3;
    public static final int USER_STATUS_DELETED = 4;

    // ===== 应用访问级别 =====
    public static final String ACCESS_LEVEL_BASIC = "BASIC";
    public static final String ACCESS_LEVEL_ADMIN = "ADMIN";

    // ===== Redis Key 前缀 =====
    public static final String REDIS_SMS_OTP_PREFIX = "cas:sms:otp:";
    public static final String REDIS_SMS_OTP_FAIL_PREFIX = "cas:sms:otp:fail:";
    public static final String REDIS_SMS_LIMIT_PREFIX = "cas:sms:limit:";
    public static final String REDIS_APP_TOKEN_PREFIX = "cas:app:token:";
    public static final String REDIS_SERVICE_NONCE_PREFIX = "cas:service:nonce:";

    /** 小程序 refreshToken -> userId 映射前缀 */
    public static final String REDIS_MINIAPP_TOKEN_PREFIX = "cas:miniapp:token:";

    // ===== 安全参数 =====
    /** 密码登录最大失败次数 */
    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    /** 密码登录锁定时长（分钟） */
    public static final int LOCK_DURATION_MINUTES = 30;
    /** 短信验证码最大尝试次数 */
    public static final int MAX_SMS_OTP_ATTEMPTS = 5;

    // ===== Admin API 认证 =====
    /** Admin API 认证 Token 请求头 */
    public static final String ADMIN_TOKEN_HEADER = "X-CAS-Admin-Token";
    /** Admin API Token Redis 前缀 */
    public static final String REDIS_ADMIN_TOKEN_PREFIX = "cas:admin:token:";

    // ===== 业务系统登录代理 =====
    /** 业务系统 ID 请求头 */
    public static final String BUSINESS_SERVICE_ID_HEADER = "X-BEENEST-SERVICE-ID";
    /** 请求时间戳请求头 */
    public static final String BUSINESS_TIMESTAMP_HEADER = "X-BEENEST-TIMESTAMP";
    /** 防重放随机串请求头 */
    public static final String BUSINESS_NONCE_HEADER = "X-BEENEST-NONCE";
    /** HMAC 签名请求头 */
    public static final String BUSINESS_SIGNATURE_HEADER = "X-BEENEST-SIGNATURE";
    /** 登录代理请求上下文中的 serviceId 属性名 */
    public static final String REQUEST_ATTR_BUSINESS_SERVICE_ID = "beenest.business.serviceId";
    /** 业务系统 nonce Redis 前缀 */
    public static final String REDIS_BUSINESS_NONCE_PREFIX = "cas:business:nonce:";

    // ===== 业务系统登录代理请求头 =====
    /** 业务系统 serviceId 请求头 */
    public static final String SERVICE_ID_HEADER = "X-CAS-Service-Id";
    /** 业务系统请求时间戳请求头 */
    public static final String REQUEST_TIMESTAMP_HEADER = "X-CAS-Timestamp";
    /** 业务系统请求随机串请求头 */
    public static final String REQUEST_NONCE_HEADER = "X-CAS-Nonce";
    /** 业务系统请求签名请求头 */
    public static final String REQUEST_SIGNATURE_HEADER = "X-CAS-Signature";
}
