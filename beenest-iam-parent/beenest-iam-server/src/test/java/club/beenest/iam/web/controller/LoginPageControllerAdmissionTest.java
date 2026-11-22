package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.webauthn.WebAuthnProperties;
import club.beenest.iam.common.web.IamOneTimeMessageService;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.ui.Model;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录页展示过滤测试。
 * <p>白名单不含 PASSWORD 时必须隐藏口令表单:留着表单让用户输完密码
 * 再被弹走,比一开始就不给入口更糟。</p>
 */
class LoginPageControllerAdmissionTest {

    @Test
    @DisplayName("白名单不含口令时隐藏口令表单")
    void hidesPasswordFormWhenNotAllowed() {
        IamOneTimeMessageService messageService = mock(IamOneTimeMessageService.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        ClientProvider clientProvider = mock(ClientProvider.class);

        RealmModel realm = new RealmModel(1L, "default", "默认域", true, false,
                300L, 1800L, 1800L, 36000L, null, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
        when(themeSelector.view(anyString())).thenReturn("login/login");
        when(catalog.availableInRealm(1L, "default")).thenReturn(Set.of(LoginMethod.SMS));
        when(clientProvider.findByClientKey(1L, "app")).thenReturn(Optional.of(clientWithWhitelist()));

        RegisteredClientRepository repository = new IamRegisteredClientRepository(
                clientProvider, realmProvider);
        LoginPageController controller = new LoginPageController(
                messageService, themeSelector, realmProvider, policyManager, catalog, repository,
                webAuthnProperties());
        Model model = mock(Model.class);

        controller.loginPage("default", null, "app", null, null, null, model, null);

        verify(model).addAttribute("passwordLoginVisible", false);
        // 白名单 ∩ 域级可用方式:域里 SMS 可用,交集只剩 SMS
        verify(model).addAttribute("allowedMethods", Set.of("SMS"));
    }

    @Test
    @DisplayName("白名单为空表示不限制,口令表单照常展示")
    void emptyWhitelistIsUnrestricted() {
        IamOneTimeMessageService messageService = mock(IamOneTimeMessageService.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        ClientProvider clientProvider = mock(ClientProvider.class);

        RealmModel realm = new RealmModel(1L, "default", "默认域", true, false,
                300L, 1800L, 1800L, 36000L, null, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
        when(themeSelector.view(anyString())).thenReturn("login/login");
        when(clientProvider.findByClientKey(1L, "app")).thenReturn(Optional.of(clientWithWhitelist(Set.of())));

        RegisteredClientRepository repository = new IamRegisteredClientRepository(
                clientProvider, realmProvider);
        LoginPageController controller = new LoginPageController(
                messageService, themeSelector, realmProvider, policyManager, catalog, repository,
                webAuthnProperties());
        Model model = mock(Model.class);

        controller.loginPage("default", null, "app", null, null, null, model, null);

        // 空白名单是"未配置限制",不是"什么都不能用"
        verify(model).addAttribute("passwordLoginVisible", true);
        verify(model).addAttribute("allowedMethods", Set.of());
    }

    @Test
    @DisplayName("白名单无法解析时按拒绝一切处理,隐藏口令表单")
    void unparseableWhitelistIsRestricted() {
        IamOneTimeMessageService messageService = mock(IamOneTimeMessageService.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        ClientProvider clientProvider = mock(ClientProvider.class);

        RealmModel realm = new RealmModel(1L, "default", "默认域", true, false,
                300L, 1800L, 1800L, 36000L, null, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
        when(themeSelector.view(anyString())).thenReturn("login/login");
        // denyAll 策略白名单为空,但控制器仍会求交集:域级可用方式不能返回 null
        when(catalog.availableInRealm(1L, "default")).thenReturn(Set.of(LoginMethod.PASSWORD));
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(clientWithWhitelist(Set.of("BOGUS"))));

        RegisteredClientRepository repository = new IamRegisteredClientRepository(
                clientProvider, realmProvider);
        LoginPageController controller = new LoginPageController(
                messageService, themeSelector, realmProvider, policyManager, catalog, repository,
                webAuthnProperties());
        Model model = mock(Model.class);

        controller.loginPage("default", null, "app", null, null, null, model, null);

        // 存了白名单却一个都认不出来:按拒绝一切处理,绝不能落回"不限制"
        verify(model).addAttribute("passwordLoginVisible", false);
        verify(model).addAttribute("allowedMethods", Set.of());
    }

    @Test
    @DisplayName("没有客户端上下文时不过滤,口令表单照常展示")
    void showsPasswordFormWithoutClientContext() {
        IamOneTimeMessageService messageService = mock(IamOneTimeMessageService.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        when(themeSelector.view(anyString())).thenReturn("login/login");

        LoginPageController controller = new LoginPageController(
                messageService, themeSelector, realmProvider, policyManager, catalog,
                mock(RegisteredClientRepository.class), webAuthnProperties());
        Model model = mock(Model.class);

        controller.loginPage("default", null, null, null, null, null, model, null);

        // 门户直达没有 client_id:此时不限制,口令表单是唯一保底入口
        verify(model).addAttribute("passwordLoginVisible", true);
    }

    /** 默认关闭的 Passkey 配置:本测试只关心准入过滤,不涉及 Passkey。 */
    private static WebAuthnProperties webAuthnProperties() {
        return new WebAuthnProperties();
    }

    /** 构造一个白名单只含 SMS 的客户端模型。 */
    private static ClientModel clientWithWhitelist() {
        return clientWithWhitelist(Set.of("SMS"));
    }

    /** 按给定白名单构造客户端模型;空集合即"未配置限制"。 */
    private static ClientModel clientWithWhitelist(Set<String> allowedLoginTypes) {
        return new ClientModel(
                1L, 1L, "app", "应用", ClientModel.ClientType.PUBLIC, null,
                Set.of("https://app.example.com/cb"), Set.of(), null, true,
                Set.of("openid"), true, false, true, null, null, null,
                Set.of("authorization_code"), allowedLoginTypes, false, null);
    }
}
