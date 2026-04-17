package org.apereo.cas.beenest.authn.credential;

import org.apereo.cas.authentication.credential.AbstractCredential;

import lombok.Getter;
import lombok.Setter;

/**
 * 抖音小程序登录凭证
 * <p>
 * 携带 tt.login 获取的 code。
 */
@Getter
@Setter
public class DouyinMiniCredential extends AbstractCredential {

    private static final long serialVersionUID = 1L;

    /** tt.login 获取的临时登录凭证 */
    private String douyinCode;

    /** 用户类型（可选，注册时使用） */
    private String userType;

    /** 昵称（可选，注册时使用） */
    private String nickname;

    public DouyinMiniCredential() {}

    public DouyinMiniCredential(String douyinCode) {
        this.douyinCode = douyinCode;
    }

    @Override
    public String getId() {
        return douyinCode != null ? douyinCode : "unknown";
    }
}
