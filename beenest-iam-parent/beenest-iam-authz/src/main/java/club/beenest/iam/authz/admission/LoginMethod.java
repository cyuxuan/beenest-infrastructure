package club.beenest.iam.authz.admission;

/**
 * 第一因素登录方式词表。
 * <p>枚举名即存储码:直接落库、落登录事件、落会话主体,三处同源,
 * 避免枚举名与存储码不一致的错配。</p>
 * <p>TOTP 不在此列,它是第二因素,与用什么方式证明身份分属两个开关。</p>
 */
public enum LoginMethod {

    /** 本地口令凭证 */
    PASSWORD("PASSWORD"),
    /** 目录(LDAP/AD)兜底认证 */
    LDAP("LDAP"),
    /** 手机号 + 短信验证码 */
    SMS("SMS"),
    /** Passkey(WebAuthn):设备本地密钥 + 用户验证,可单独作为登录凭据 */
    WEBAUTHN("WEBAUTHN"),
    /** 微信(小程序登录 / 开放平台扫码) */
    WECHAT("WECHAT"),
    /** 外部 OIDC 提供方 */
    OIDC("OIDC"),
    /** 外部 SAML IdP(平台作 SP) */
    SAML("SAML");

    /** 存储码 */
    private final String code;

    LoginMethod(String code) {
        this.code = code;
    }

    /**
     * 存储码。
     *
     * @return 与枚举名相同的存储码
     */
    public String code() {
        return code;
    }

    /**
     * 按存储码解析枚举值,大小写与首尾空格不敏感。
     *
     * @param code 存储码
     * @return 对应枚举值;null、空白或非法值返回 null,由调用方决定拒绝或忽略
     */
    public static LoginMethod fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (LoginMethod method : values()) {
            if (method.code.equalsIgnoreCase(code.trim())) {
                return method;
            }
        }
        return null;
    }
}
