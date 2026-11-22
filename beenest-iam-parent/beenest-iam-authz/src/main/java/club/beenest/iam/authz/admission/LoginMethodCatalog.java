package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.sms.SmsChannelRouter;
import club.beenest.iam.authz.webauthn.WebAuthnProperties;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 域级可用登录方式的计算入口。
 * <p>本域可用方式是管理端配置校验、登录页展示过滤、挑战页渲染、运行时准入判定
 * 四处共用的同一份口径。</p>
 * <p>短信方式的可用性取自 {@link SmsChannelRouter},与发送路径同源。</p>
 */
@Component
public class LoginMethodCatalog {

    private final IamSecurityPolicyManager securityPolicyManager;
    private final IamLdapConfigStore ldapConfigStore;
    private final IdentityProviderStore identityProviderStore;
    private final SmsChannelRouter smsChannelRouter;
    private final WebAuthnProperties webAuthnProperties;

    public LoginMethodCatalog(IamSecurityPolicyManager securityPolicyManager,
                              IamLdapConfigStore ldapConfigStore,
                              IdentityProviderStore identityProviderStore,
                              SmsChannelRouter smsChannelRouter,
                              WebAuthnProperties webAuthnProperties) {
        this.securityPolicyManager = securityPolicyManager;
        this.ldapConfigStore = ldapConfigStore;
        this.identityProviderStore = identityProviderStore;
        this.smsChannelRouter = smsChannelRouter;
        this.webAuthnProperties = webAuthnProperties;
    }

    /**
     * 计算本域当前可用的第一因素集合。
     *
     * @param realmId  身份域主键
     * @param realmKey 身份域标识
     * @return 可用登录方式集合,始终包含 {@link LoginMethod#PASSWORD}
     */
    public Set<LoginMethod> availableInRealm(Long realmId, String realmKey) {
        Set<LoginMethod> available = EnumSet.of(LoginMethod.PASSWORD);
        // 短信可用性 = 域级开关开 且 通道可解析,通道判定走 SmsChannelRouter,
        // 与发送路径同源,保证展示与发送的可用性一致
        LoginPolicy policy = securityPolicyManager.getLoginPolicy(realmId, realmKey);
        if (policy != null && Boolean.TRUE.equals(policy.getSmsLoginEnabled())
                && smsChannelRouter.resolve().isPresent()) {
            available.add(LoginMethod.SMS);
        }
        // LDAP:域级配置存在且启用
        LdapConfig ldapConfig = ldapConfigStore.getConfig(realmId, realmKey);
        if (ldapConfig != null && Boolean.TRUE.equals(ldapConfig.getEnabled())) {
            available.add(LoginMethod.LDAP);
        }
        // Passkey:依赖方标识与来源都配置齐全,缺任一项浏览器侧必然失败
        if (webAuthnProperties.isAvailable()) {
            available.add(LoginMethod.WEBAUTHN);
        }
        // 联邦:按域内已启用提供方类型推导
        for (IdentityProviderModel idp : identityProviderStore.listByRealm(realmId)) {
            if (!idp.enabled()) {
                continue;
            }
            LoginMethod method = toLoginMethod(idp.providerId());
            if (method != null) {
                available.add(method);
            }
        }
        return available;
    }

    /**
     * 联邦提供方类型映射为登录方式。
     * <p>微信的小程序与开放平台扫码两种形态归并为 {@link LoginMethod#WECHAT},
     * 避免管理员维护两套白名单。</p>
     *
     * @param providerId 联邦提供方标识,大小写与空格不敏感
     * @return 对应登录方式;未识别的提供方返回 null
     */
    public static LoginMethod toLoginMethod(String providerId) {
        if (providerId == null) {
            return null;
        }
        return switch (providerId.trim().toLowerCase(Locale.ROOT)) {
            case "wechat-mini", "wechat-web" -> LoginMethod.WECHAT;
            case "oidc" -> LoginMethod.OIDC;
            case "saml" -> LoginMethod.SAML;
            default -> null;
        };
    }
}
