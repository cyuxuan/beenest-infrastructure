package org.apereo.cas.beenest.authn.credential;

import org.apereo.cas.authentication.credential.AbstractCredential;

import lombok.Getter;
import lombok.Setter;

/**
 * 支付宝小程序登录凭证
 * <p>
 * 携带 my.getAuthCode 获取的 authCode。
 */
@Getter
@Setter
public class AlipayMiniCredential extends AbstractCredential {

    private static final long serialVersionUID = 1L;

    /** my.getAuthCode 获取的授权码 */
    private String authCode;

    /** 获取手机号的响应码（通过 my.getPhoneNumber 获取，可选） */
    private String phoneCode;

    /** 用户类型（可选，注册时使用） */
    private String userType;

    /** 昵称（可选，注册时使用） */
    private String nickname;

    public AlipayMiniCredential() {}

    public AlipayMiniCredential(String authCode) {
        this.authCode = authCode;
    }

    @Override
    public String getId() {
        return authCode != null ? authCode : "unknown";
    }
}
