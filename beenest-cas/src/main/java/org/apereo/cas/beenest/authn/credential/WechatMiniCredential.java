package org.apereo.cas.beenest.authn.credential;

import org.apereo.cas.authentication.credential.AbstractCredential;

import lombok.Getter;
import lombok.Setter;

/**
 * 微信小程序登录凭证
 * <p>
 * 携带 wx.login 获取的 code 和可选的 phoneCode（用于获取手机号）。
 */
@Getter
@Setter
public class WechatMiniCredential extends AbstractCredential {

    private static final long serialVersionUID = 1L;

    /** wx.login 获取的临时登录凭证 */
    private String code;

    /** 获取手机号的临时凭证（可选） */
    private String phoneCode;

    /** 用户类型（可选，注册时使用） */
    private String userType;

    /** 昵称（可选，注册时使用） */
    private String nickname;

    public WechatMiniCredential() {}

    public WechatMiniCredential(String code) {
        this.code = code;
    }

    @Override
    public String getId() {
        return code != null ? code : "unknown";
    }
}
