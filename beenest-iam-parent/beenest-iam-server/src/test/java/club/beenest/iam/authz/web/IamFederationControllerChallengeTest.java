package club.beenest.iam.authz.web;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.federation.IamFederationStateService;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.federation.broker.IdentityBrokerService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 联邦回跳:准入挑战票据的定位与回跳地址的选取。
 * <p>回跳前预取挑战必须与 establish 用同一个会话定位口径(请求 Cookie 里的 sid):
 * 只按令牌定位时,攻击者可以拿自己的会话票据配上他人的挑战令牌,
 * 让升级发生在自己的会话上、却把回跳地址取自令牌所属的挑战(登录 CSRF)。</p>
 */
class IamFederationControllerChallengeTest {

    private static final String REALM = "default";
    private static final String ALIAS = "oidc";
    private static final RealmModel REALM_MODEL = new RealmModel(
            1L, "default", "默认域", true, false, 300L, 1800L, 1800L, 36000L, null, null);
    private static final UserModel USER = new UserModel(
            7L, 1L, "alice", "Alice", null, null, 1, "INTERNAL", true, 0, Map.of());
    private static final IamUserPrincipal PRINCIPAL = new IamUserPrincipal(
            7L, 1L, "default", "alice", "Alice", 0, List.of(), Map.of(), -1L, "OIDC");
    private static final String CHALLENGE_RETURN_TO =
            "/realms/default/oauth2/authorize?client_id=app";

    private IdentityBrokerService brokerService;
    private IamFederationStateService stateService;
    private IamLoginSessionService loginSessionService;
    private RealmProvider realmProvider;
    private LoginEventPublisher loginEventPublisher;
    private IdentityProviderStore identityProviderStore;
    private IamPrincipalFactory principalFactory;
    private AdmissionChallengeService challengeService;

    private IamFederationController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        brokerService = mock(IdentityBrokerService.class);
        stateService = mock(IamFederationStateService.class);
        loginSessionService = mock(IamLoginSessionService.class);
        realmProvider = mock(RealmProvider.class);
        loginEventPublisher = mock(LoginEventPublisher.class);
        identityProviderStore = mock(IdentityProviderStore.class);
        principalFactory = mock(IamPrincipalFactory.class);
        challengeService = mock(AdmissionChallengeService.class);

        when(realmProvider.findByRealmKey(REALM)).thenReturn(Optional.of(REALM_MODEL));
        when(identityProviderStore.findByIdp(1L, ALIAS)).thenReturn(Optional.empty());
        when(principalFactory.build(REALM_MODEL, USER, "FEDERATION")).thenReturn(PRINCIPAL);
        when(brokerService.completeLogin(eq(REALM), eq(ALIAS), any(CallbackContext.class)))
                .thenReturn(USER);

        controller = new IamFederationController(brokerService, stateService, loginSessionService,
                realmProvider, loginEventPublisher, identityProviderStore, principalFactory,
                challengeService, new IamIssuerBaseUrlResolver("https://iam.example.com"),
                mock(IamRateLimiter.class));
        request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "junit");
        response = new MockHttpServletResponse();
    }

    @Test
    @DisplayName("升级成功时回跳到挑战里的授权端点,且按请求会话标识定位挑战")
    void redirectsToChallengeReturnToAfterUpgrade() throws Exception {
        when(stateService.consume("st-1")).thenReturn("default|oidc|tok-1|/portal");
        request.setCookies(new Cookie(IamSessionCookie.COOKIE_NAME, "sid-1"));
        when(challengeService.resolve(REALM, "tok-1", "sid-1")).thenReturn(new AdmissionChallenge(
                "sid-1", "app", Set.of(), true, CHALLENGE_RETURN_TO, "tok-1", 1L));
        when(loginSessionService.establish(any(HttpServletRequest.class), any(HttpServletResponse.class),
                any(IamUserPrincipal.class), eq("FEDERATION"), eq("tok-1"))).thenReturn(true);

        controller.callback(REALM, ALIAS, "code", "st-1", request, response);

        assertEquals(CHALLENGE_RETURN_TO, response.getRedirectedUrl());
        // 预解析若传 null(sid 丢失),这里会失败:令牌与会话错配的口子就此打开
        verify(challengeService).resolve(REALM, "tok-1", "sid-1");
    }

    @Test
    @DisplayName("未走升级路径时回跳到发起时的目标地址")
    void redirectsToOriginalTargetWhenNotUpgraded() throws Exception {
        when(stateService.consume("st-1")).thenReturn("default|oidc|tok-1|/portal");
        when(challengeService.resolve(REALM, "tok-1", null)).thenReturn(null);
        when(loginSessionService.establish(any(HttpServletRequest.class), any(HttpServletResponse.class),
                any(IamUserPrincipal.class), eq("FEDERATION"), eq("tok-1"))).thenReturn(false);

        controller.callback(REALM, ALIAS, "code", "st-1", request, response);

        assertEquals("/portal", response.getRedirectedUrl());
    }

    @Test
    @DisplayName("state 里的协议相对地址与反斜杠地址一律回落到门户")
    void sanitizesOpenRedirectTargetFromState() throws Exception {
        when(loginSessionService.establish(any(HttpServletRequest.class), any(HttpServletResponse.class),
                any(IamUserPrincipal.class), any(), any())).thenReturn(false);

        when(stateService.consume("st-1")).thenReturn("default|oidc||//evil.com");
        controller.callback(REALM, ALIAS, "code", "st-1", request, response);
        assertEquals("/portal", response.getRedirectedUrl());

        when(stateService.consume("st-2")).thenReturn("default|oidc||/\\evil.com");
        MockHttpServletResponse second = new MockHttpServletResponse();
        controller.callback(REALM, ALIAS, "code", "st-2", request, second);
        assertEquals("/portal", second.getRedirectedUrl());
    }

    @Test
    @DisplayName("state 载荷损坏时按未认证处理,而不是服务端错误")
    void rejectsMalformedStatePayload() {
        when(stateService.consume("st-1")).thenReturn("default");

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.callback(REALM, ALIAS, "code", "st-1", request, response));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), error.getCode());
    }

    @Test
    @DisplayName("发起时把挑战令牌编入 state;含分隔符的令牌按无挑战编码")
    void initiationEncodesChallengeToken() throws Exception {
        when(stateService.issue(anyString())).thenReturn("st-1");
        when(brokerService.buildAuthorizationUrl(eq(REALM), eq(ALIAS), anyString(), anyString()))
                .thenReturn("https://idp.example.com/auth");

        controller.login(REALM, ALIAS, "/portal", "tok-1", request, response);
        MockHttpServletResponse second = new MockHttpServletResponse();
        controller.login(REALM, ALIAS, "/portal", "bad|tok", request, second);

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(stateService, times(2)).issue(payloads.capture());
        assertEquals("default|oidc|tok-1|/portal", payloads.getAllValues().get(0));
        assertEquals("default|oidc||/portal", payloads.getAllValues().get(1));
    }
}
