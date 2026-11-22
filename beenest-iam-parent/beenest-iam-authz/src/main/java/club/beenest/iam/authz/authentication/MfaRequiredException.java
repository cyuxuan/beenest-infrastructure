package club.beenest.iam.authz.authentication;

import org.springframework.security.core.AuthenticationException;

/**
 * 需要多因子认证异常。
 * <p>口令已通过但账号启用了 TOTP(或域策略强制 MFA)时由认证 Provider 抛出,
 * 登录链路据此进入第二步验证而非判定为登录失败。</p>
 */
public class MfaRequiredException extends AuthenticationException {

    /** 待验证用户主键 */
    private final Long userId;
    /** 待验证登录名 */
    private final String username;
    /** 是否要求先完成绑定(强制 MFA 且未绑定场景) */
    private final boolean enrollmentRequired;
    /** 第一因素认证方式(LoginMethod 存储码),随票据保存,第二步校验时还原登录方式 */
    private final String loginType;

    public MfaRequiredException(Long userId, String username, boolean enrollmentRequired, String loginType) {
        super(enrollmentRequired ? "需要先绑定动态口令" : "需要多因子认证");
        this.userId = userId;
        this.username = username;
        this.enrollmentRequired = enrollmentRequired;
        this.loginType = loginType;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isEnrollmentRequired() {
        return enrollmentRequired;
    }

    /** 第一因素认证方式;未知为 null。 */
    public String getLoginType() {
        return loginType;
    }
}
