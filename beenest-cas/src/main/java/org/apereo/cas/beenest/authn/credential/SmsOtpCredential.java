package org.apereo.cas.beenest.authn.credential;

import org.apereo.cas.authentication.credential.AbstractCredential;

import lombok.Getter;
import lombok.Setter;

/**
 * 短信验证码登录凭证
 * <p>
 * 携带手机号和短信验证码。
 */
@Getter
@Setter
public class SmsOtpCredential extends AbstractCredential {

    private static final long serialVersionUID = 1L;

    /** 手机号 */
    private String phone;

    /** 短信验证码 */
    private String otpCode;

    /** 用户类型（可选，注册时使用） */
    private String userType;

    public SmsOtpCredential() {}

    public SmsOtpCredential(String phone, String otpCode) {
        this.phone = phone;
        this.otpCode = otpCode;
    }

    @Override
    public String getId() {
        return phone != null ? phone : "unknown";
    }
}
