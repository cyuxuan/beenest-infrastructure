package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 域级登录策略实体(iam_login_policy,每域一行)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LoginPolicy extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 窗口内最大失败次数,达到后锁定 */
    private Integer maxFailAttempts;
    /** 失败计数窗口(分钟) */
    private Integer failureWindowMinutes;
    /** 锁定时长(分钟) */
    private Integer lockDurationMinutes;
    /** 失败达 N 次后要求验证码(0 关闭) */
    private Integer captchaThreshold;
    /** 强制全员启用 MFA */
    private Boolean forceMfa;
    /**
     * 同一来源 IP 在窗口期内的最大失败次数(0 表示不按 IP 限制)。
     * <p>账号维度锁定无法拦截同一 IP 轮换账号的密码喷洒,此值按来源 IP 计数。</p>
     */
    private Integer ipMaxFailAttempts;
    /** IP 维度锁定时长(分钟) */
    private Integer ipLockDurationMinutes;
    /** 允许短信验证码登录(需另行配置 iam.sms.* 短信通道) */
    private Boolean smsLoginEnabled;
    /** 短信验证码有效期(秒) */
    private Integer smsCodeTtlSeconds;
    /** 同一手机号每小时最多发送条数,0 表示不限制 */
    private Integer smsMaxSendPerHour;
    /** 同一验证码最多校验失败次数,超过即作废 */
    private Integer smsMaxVerifyAttempts;
    /**
     * 同一账号最大并发在线会话数(0 表示不限制)。
     * <p>超限时新登录顶掉最旧会话,被顶下线的会话状态记为 REPLACED。</p>
     */
    private Integer maxConcurrentSessions;
}
