package club.beenest.iam.core.spi.federation;

import java.util.HashMap;
import java.util.Map;

/**
 * 经纪身份上下文:回调换回的外部身份。
 * <p>IdentityProvider 把外部 IdP 的身份信息转换成本对象,
 * 再由 UserFederationLinkService 按 externalId 查找或创建本地用户。</p>
 */
public class BrokeredIdentityContext {

    /** 外部身份唯一标识(IdP 侧 subject) */
    private final String externalId;
    /** 提供方类型(wechat / oidc) */
    private final String providerId;
    /** 提供方别名 */
    private final String alias;
    /** 用户名(外部) */
    private String username;
    /** 邮箱(外部) */
    private String email;
    /** 昵称(外部) */
    private String nickname;
    /** 提供方换回并担保已验证的手机号(如微信手机号快速验证) */
    private String verifiedPhone;
    /** 其他用户属性 */
    private final Map<String, String> attributes = new HashMap<>();

    public BrokeredIdentityContext(String externalId, String providerId, String alias) {
        this.externalId = externalId;
        this.providerId = providerId;
        this.alias = alias;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getAlias() {
        return alias;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 提供方担保已验证的手机号。
     * <p>只由提供方从可信渠道取得(如微信手机号接口),平台据此把手机号写入
     * 本地账号并按手机号归并既有账号;调用方提交的手机号不进入该字段。</p>
     *
     * @return 已验证手机号;提供方未取得时返回 null
     */
    public String getVerifiedPhone() {
        return verifiedPhone;
    }

    public void setVerifiedPhone(String verifiedPhone) {
        this.verifiedPhone = verifiedPhone;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttribute(String key, String value) {
        attributes.put(key, value);
    }
}
