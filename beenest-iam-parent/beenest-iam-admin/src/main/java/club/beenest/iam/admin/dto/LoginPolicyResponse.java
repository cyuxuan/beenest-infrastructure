package club.beenest.iam.admin.dto;

/**
 * 登录策略响应。
 *
 * @param maxFailAttempts     窗口内最大失败次数
 * @param failureWindowMinutes 失败计数窗口(分钟)
 * @param lockDurationMinutes 锁定时长(分钟)
 * @param captchaThreshold    失败达 N 次后要求验证码(0 关闭)
 * @param forceMfa            强制全员启用 MFA
 * @param ipMaxFailAttempts   同一来源 IP 在窗口期内的最大失败次数(0 关闭)
 * @param ipLockDurationMinutes IP 维度锁定时长(分钟)
 * @param smsLoginEnabled     允许短信验证码登录
 * @param smsCodeTtlSeconds   短信验证码有效期(秒)
 * @param smsMaxSendPerHour   同一手机号每小时最多发送条数(0 不限制)
 * @param smsMaxVerifyAttempts 同一验证码最多校验失败次数
 * @param maxConcurrentSessions 同一账号最大并发在线会话数(0 不限制)
 */
public record LoginPolicyResponse(
        Integer maxFailAttempts,
        Integer failureWindowMinutes,
        Integer lockDurationMinutes,
        Integer captchaThreshold,
        Boolean forceMfa,
        Integer ipMaxFailAttempts,
        Integer ipLockDurationMinutes,
        Boolean smsLoginEnabled,
        Integer smsCodeTtlSeconds,
        Integer smsMaxSendPerHour,
        Integer smsMaxVerifyAttempts,
        Integer maxConcurrentSessions
) {
}
