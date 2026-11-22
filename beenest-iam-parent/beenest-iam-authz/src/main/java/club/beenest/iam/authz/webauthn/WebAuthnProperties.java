package club.beenest.iam.authz.webauthn;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Passkey(WebAuthn)配置({@code iam.webauthn.*})。
 * <p>{@code enabled}、{@code rp-id}、{@code origin} 三项缺一即视为未启用:
 * 前端不展示 Passkey 入口,接口返回未启用错误。</p>
 * <p>{@code rp-id} 必须是 {@code origin} 的注册域或其父域:浏览器把 rpId 作为
 * 密钥的绑定范围,配错时认证器会直接拒绝创建凭证,错误发生在浏览器里、平台侧看不到。</p>
 */
@ConfigurationProperties(prefix = "iam.webauthn")
public class WebAuthnProperties {

    /** 是否启用 Passkey 能力 */
    private boolean enabled = false;

    /** 依赖方标识(通常是身份的域名,不含协议与端口),如 {@code id.example.com} */
    private String rpId;

    /** 依赖方展示名(浏览器弹窗里显示给用户) */
    private String rpName = "统一身份认证";

    /** 页面来源,必须与浏览器地址栏的 origin 完全一致,如 {@code https://id.example.com} */
    private String origin;

    /** 认证器交互超时(毫秒),下发给浏览器作为提示值 */
    private int timeoutMillis = 60_000;

    /**
     * 是否要求用户验证(指纹/人脸/PIN)。
     * <p>取 true 时 Passkey 单独就能作为登录凭据;取 false 时仅证明"持有设备",
     * 不足以单独完成登录,只适合作为第二因素。</p>
     */
    private boolean userVerificationRequired = true;

    /** 是否允许无用户名登录(发现式凭证);关闭后登录页必须先填账号 */
    private boolean allowUsernameless = true;

    /** 挑战票据有效期(秒) */
    private int challengeTtlSeconds = 300;

    /** 单用户最多可绑定的凭证数量 */
    private int maxCredentialsPerUser = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRpId() {
        return rpId;
    }

    public void setRpId(String rpId) {
        this.rpId = rpId;
    }

    public String getRpName() {
        return rpName;
    }

    public void setRpName(String rpName) {
        this.rpName = rpName;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public boolean isUserVerificationRequired() {
        return userVerificationRequired;
    }

    public void setUserVerificationRequired(boolean userVerificationRequired) {
        this.userVerificationRequired = userVerificationRequired;
    }

    public boolean isAllowUsernameless() {
        return allowUsernameless;
    }

    public void setAllowUsernameless(boolean allowUsernameless) {
        this.allowUsernameless = allowUsernameless;
    }

    public int getChallengeTtlSeconds() {
        return challengeTtlSeconds;
    }

    public void setChallengeTtlSeconds(int challengeTtlSeconds) {
        this.challengeTtlSeconds = challengeTtlSeconds;
    }

    public int getMaxCredentialsPerUser() {
        return maxCredentialsPerUser;
    }

    public void setMaxCredentialsPerUser(int maxCredentialsPerUser) {
        this.maxCredentialsPerUser = maxCredentialsPerUser;
    }

    /**
     * Passkey 能力是否可用。
     *
     * @return true 表示启用且依赖方标识与来源都已配置
     */
    public boolean isAvailable() {
        return enabled
                && rpId != null && !rpId.isBlank()
                && origin != null && !origin.isBlank();
    }
}
