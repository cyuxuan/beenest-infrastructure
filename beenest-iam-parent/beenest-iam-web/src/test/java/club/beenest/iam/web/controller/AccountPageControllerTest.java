package club.beenest.iam.web.controller;

import club.beenest.iam.authz.mail.MailChannelRouter;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.security.EmailVerificationService;
import club.beenest.iam.authz.webauthn.WebAuthnProperties;
import club.beenest.iam.common.theme.ThemeRegistry;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.web.config.IamThemeProperties;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账户待办页控制器测试。
 * <p>待办为空时页面必须给出出口:改密会递增令牌版本、当前会话随即作废,
 * 刷新与重新登录都会带着 {@code return_to=/required-action} 回到这里;
 * 若此时仍渲染空页面,用户只能再登出登入一次才能继续。</p>
 */
class AccountPageControllerTest {

    private static final long USER_ID = 7L;

    private final RequiredActionService requiredActionService = mock(RequiredActionService.class);
    private final CsrfToken csrfToken = mock(CsrfToken.class);
    private final AccountPageController controller = new AccountPageController(
            new IamThemeSelector(new IamThemeProperties(), new ThemeRegistry()),
            mock(MailChannelRouter.class),
            requiredActionService,
            mock(EmailVerificationService.class),
            new WebAuthnProperties(),
            messageSource());

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, 1L, "default", "alice", "Alice",
                0, List.of(), Map.of(), 0L, "PASSWORD");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("没有待办时直接回门户,不渲染无出口的空页面")
    void redirectsToPortalWhenNothingPending() {
        when(requiredActionService.pending(USER_ID)).thenReturn(Set.of());

        String view = controller.requiredAction(new ExtendedModelMap(), csrfToken);

        assertThat(view).isEqualTo("redirect:" + PortalPageController.PORTAL_PATH);
        verify(requiredActionService).pending(USER_ID);
    }

    @Test
    @DisplayName("有待办时渲染待办页并带上办理入口")
    @SuppressWarnings("unchecked")
    void rendersPendingActions() {
        when(requiredActionService.pending(USER_ID)).thenReturn(Set.of(RequiredAction.UPDATE_PASSWORD));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.requiredAction(model, csrfToken);

        assertThat(view).isEqualTo("nestid/account/required-action");
        assertThat((List<AccountPageController.ActionItem>) model.get("actions"))
                .singleElement()
                .extracting(AccountPageController.ActionItem::code)
                .isEqualTo(RequiredAction.UPDATE_PASSWORD.name());
    }

    /** 文案源:与页面共用同一份资源。 */
    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasenames("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }
}
