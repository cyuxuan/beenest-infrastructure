package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.sms.SmsChannelRouter;
import club.beenest.iam.authz.webauthn.WebAuthnProperties;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.sms.SmsChannel;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 域级可用方式计算测试。
 * <p>这张表是"管理端校验"与"运行时判定"的共同口径:两处若不一致,
 * 会出现"配置时提示可用,运行时却登录不进去"的错配。</p>
 */
class LoginMethodCatalogTest {

    private static final Long REALM_ID = 1L;
    private static final String REALM_KEY = "default";

    private final IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
    private final IamLdapConfigStore ldapConfigStore = mock(IamLdapConfigStore.class);
    private final IdentityProviderStore idpStore = mock(IdentityProviderStore.class);
    private final SmsChannelRouter channelRouter = mock(SmsChannelRouter.class);
    /** 默认未启用 Passkey:各用例按需覆盖 */
    private final WebAuthnProperties webAuthnProperties = new WebAuthnProperties();
    private final LoginMethodCatalog catalog = new LoginMethodCatalog(
            policyManager, ldapConfigStore, idpStore, channelRouter, webAuthnProperties);

    @BeforeEach
    void setUp() {
        LoginPolicy policy = new LoginPolicy();
        policy.setSmsLoginEnabled(false);
        when(policyManager.getLoginPolicy(REALM_ID, REALM_KEY)).thenReturn(policy);
        when(ldapConfigStore.getConfig(REALM_ID, REALM_KEY)).thenReturn(null);
        when(idpStore.listByRealm(REALM_ID)).thenReturn(List.of());
        // 短信通道已就绪:本用例聚焦域级开关,通道可用性单独验证
        when(channelRouter.resolve()).thenReturn(Optional.of(mock(SmsChannel.class)));
    }

    @Test
    @DisplayName("口令恒可用")
    void passwordAlwaysAvailable() {
        assertTrue(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.PASSWORD));
    }

    @Test
    @DisplayName("域级开关关闭时短信不可用,即使通道已就绪")
    void smsDisabled() {
        // setUp 即"通道已就绪 + 域级开关为假",所以本用例同时盖住了这两个条件
        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.SMS));
    }

    @Test
    @DisplayName("域级开启短信但通道未配置,短信仍不可用")
    void smsEnabledButChannelMissing() {
        LoginPolicy policy = new LoginPolicy();
        policy.setSmsLoginEnabled(true);
        when(policyManager.getLoginPolicy(REALM_ID, REALM_KEY)).thenReturn(policy);

        SmsChannelRouter noChannelRouter = mock(SmsChannelRouter.class);
        when(noChannelRouter.resolve()).thenReturn(Optional.empty());
        LoginMethodCatalog noChannel = new LoginMethodCatalog(
                policyManager, ldapConfigStore, idpStore, noChannelRouter, webAuthnProperties);

        assertFalse(noChannel.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.SMS));
    }

    @Test
    @DisplayName("域级启用 LDAP 时 LDAP 可用")
    void ldapEnabled() {
        LdapConfig config = new LdapConfig();
        config.setEnabled(true);
        when(ldapConfigStore.getConfig(REALM_ID, REALM_KEY)).thenReturn(config);

        assertTrue(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.LDAP));
    }

    @Test
    @DisplayName("注册了微信提供方则微信可用")
    void wechatAvailable() {
        when(idpStore.listByRealm(REALM_ID)).thenReturn(List.of(
                new IdentityProviderModel(1L, REALM_ID, "wechat-mini", "wx", "微信", true, Map.of())));

        assertTrue(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WECHAT));
    }

    @Test
    @DisplayName("提供方类型映射到登录方式")
    void mapsProviderType() {
        assertEquals(LoginMethod.WECHAT, LoginMethodCatalog.toLoginMethod("wechat-mini"));
        assertEquals(LoginMethod.WECHAT, LoginMethodCatalog.toLoginMethod("wechat-web"));
        assertEquals(LoginMethod.OIDC, LoginMethodCatalog.toLoginMethod("oidc"));
        assertEquals(LoginMethod.SAML, LoginMethodCatalog.toLoginMethod("saml"));
    }

    @Test
    @DisplayName("存储码解析:空白与非法值一律返回 null")
    void fromCodeRejectsBlankAndUnknown() {
        assertNull(LoginMethod.fromCode(null));
        assertNull(LoginMethod.fromCode(""));
        assertNull(LoginMethod.fromCode("   "));
        assertNull(LoginMethod.fromCode("TOTP"));
    }

    @Test
    @DisplayName("存储码解析:忽略大小写与首尾空白")
    void fromCodeTrimsAndIgnoresCase() {
        assertEquals(LoginMethod.SMS, LoginMethod.fromCode("  sms  "));
        assertEquals(LoginMethod.WECHAT, LoginMethod.fromCode("wechat"));
    }

    @Test
    @DisplayName("存储码即词表值")
    void codeIsTheCanonicalValue() {
        assertEquals("PASSWORD", LoginMethod.PASSWORD.code());
        assertEquals("WECHAT", LoginMethod.WECHAT.code());
    }

    @Test
    @DisplayName("提供方未启用时不算可用")
    void disabledProviderNotAvailable() {
        when(idpStore.listByRealm(REALM_ID)).thenReturn(List.of(
                new IdentityProviderModel(1L, REALM_ID, "wechat-mini", "wx", "微信", false, Map.of())));

        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WECHAT));
    }

    @Test
    @DisplayName("LDAP 配了但未启用时不算可用")
    void disabledLdapNotAvailable() {
        LdapConfig config = new LdapConfig();
        config.setEnabled(false);
        when(ldapConfigStore.getConfig(REALM_ID, REALM_KEY)).thenReturn(config);

        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.LDAP));
    }

    @Test
    @DisplayName("无法识别的提供方类型不产出登录方式")
    void unknownProviderTypeMapsToNull() {
        assertNull(LoginMethodCatalog.toLoginMethod("unknown-idp"));
    }

    @Test
    @DisplayName("Passkey 未配置依赖方时不可用")
    void passkeyUnavailableWithoutRpConfig() {
        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WEBAUTHN));
    }

    @Test
    @DisplayName("Passkey 依赖方配置完整时可用")
    void passkeyAvailableWhenConfigured() {
        WebAuthnProperties configured = new WebAuthnProperties();
        configured.setEnabled(true);
        configured.setRpId("id.example.com");
        configured.setOrigin("https://id.example.com");
        LoginMethodCatalog withPasskey = new LoginMethodCatalog(
                policyManager, ldapConfigStore, idpStore, channelRouter, configured);

        assertTrue(withPasskey.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WEBAUTHN));
    }

    @Test
    @DisplayName("Passkey 启用但缺来源时不可用:浏览器侧必然失败")
    void passkeyUnavailableWithoutOrigin() {
        WebAuthnProperties noOrigin = new WebAuthnProperties();
        noOrigin.setEnabled(true);
        noOrigin.setRpId("id.example.com");
        LoginMethodCatalog withPasskey = new LoginMethodCatalog(
                policyManager, ldapConfigStore, idpStore, channelRouter, noOrigin);

        assertFalse(withPasskey.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WEBAUTHN));
    }
}
