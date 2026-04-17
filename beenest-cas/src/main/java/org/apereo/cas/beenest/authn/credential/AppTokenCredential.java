package org.apereo.cas.beenest.authn.credential;

import org.apereo.cas.authentication.credential.AbstractCredential;
import lombok.Getter;
import lombok.Setter;

/**
 * APP 登录凭证
 * <p>
 * 支持两种 APP 登录方式：
 * 1. 用户名/手机号 + 密码
 * 2. 手机号 + 短信验证码
 * 3. refreshToken 续期
 */
@Getter
@Setter
public class AppTokenCredential extends AbstractCredential {

    private static final long serialVersionUID = 1L;

    /** 用户名或手机号 */
    private String principal;

    /** 密码（用户名登录时使用） */
    private String password;

    /** 短信验证码（手机号登录时使用） */
    private String otpCode;

    /** refreshToken（续期时使用） */
    private String refreshToken;

    /** 设备标识 */
    private String deviceId;

    /** 是否记住我（30天长 TGT） */
    private boolean rememberMe;

    /** 登录方式: password / sms / refresh */
    private String loginMethod;

    /**
     * Controller 预验证的用户 ID
     * <p>
     * 在 refreshToken 轮换场景中，Controller 通过 Redis getAndDelete 原子操作
     * 验证并删除旧 refreshToken 后获取 userId，设置到此字段。
     * Handler 检测到此字段非空时跳过 Redis 查询，直接使用此 userId。
     */
    private String preValidatedUserId;

    public AppTokenCredential() {}

    @Override
    public String getId() {
        if (refreshToken != null) {
            return refreshToken;
        }
        return principal != null ? principal : "unknown";
    }
}
