package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 登录策略更新请求。
 *
 * @param maxFailAttempts     窗口内最大失败次数(1-100)
 * @param failureWindowMinutes 失败计数窗口(分钟,1-1440)
 * @param lockDurationMinutes 锁定时长(分钟,1-1440)
 * @param captchaThreshold    失败达 N 次后要求验证码(0 关闭,1-100)
 * @param forceMfa            强制全员启用 MFA
 * @param ipMaxFailAttempts   同一来源 IP 在窗口期内的最大失败次数(0 关闭,1-10000)
 * @param ipLockDurationMinutes IP 维度锁定时长(分钟,1-1440)
 * @param smsLoginEnabled     允许短信验证码登录(需另行配置短信通道)
 * @param smsCodeTtlSeconds   短信验证码有效期(秒,60-3600)
 * @param smsMaxSendPerHour   同一手机号每小时最多发送条数(0 不限制,1-100)
 * @param smsMaxVerifyAttempts 同一验证码最多校验失败次数(1-20)
 * @param maxConcurrentSessions 同一账号最大并发在线会话数(0 不限制,1-20)
 */
public record LoginPolicyUpdateRequest(
        @NotNull(message = "最大失败次数不能为空") @Min(1) @Max(100) Integer maxFailAttempts,
        @NotNull(message = "失败窗口不能为空") @Min(1) @Max(1440) Integer failureWindowMinutes,
        @NotNull(message = "锁定时长不能为空") @Min(1) @Max(1440) Integer lockDurationMinutes,
        @NotNull(message = "验证码阈值不能为空") @Min(0) @Max(100) Integer captchaThreshold,
        @NotNull(message = "强制 MFA 开关不能为空") Boolean forceMfa,
        @NotNull(message = "IP 维度最大失败次数不能为空") @Min(0) @Max(10000) Integer ipMaxFailAttempts,
        @NotNull(message = "IP 维度锁定时长不能为空") @Min(1) @Max(1440) Integer ipLockDurationMinutes,
        @NotNull(message = "短信登录开关不能为空") Boolean smsLoginEnabled,
        @NotNull(message = "验证码有效期不能为空") @Min(60) @Max(3600) Integer smsCodeTtlSeconds,
        @NotNull(message = "每小时发送上限不能为空") @Min(0) @Max(100) Integer smsMaxSendPerHour,
        @NotNull(message = "校验失败上限不能为空") @Min(1) @Max(20) Integer smsMaxVerifyAttempts,
        @NotNull(message = "并发会话上限不能为空") @Min(0) @Max(20) Integer maxConcurrentSessions
) {
}
