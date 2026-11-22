package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.authz.web.IamRedisSecurityContextRepository;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 准入过滤器的请求级行为。
 * <p>判定规则本身由 {@link AdmissionDecisionTest} 覆盖;这里钉的是"请求有没有被拦住":
 * 方法判断、快路径条件、以及回跳地址的组装。这几处错了都不会报错,
 * 只会让闸门默默失效。</p>
 */
class ClientAdmissionFilterTest {

    private static final String AUTHORIZE_PATH = "/realms/default/oauth2/authorize";

    private final RegisteredClientRepository clientRepository = mock(RegisteredClientRepository.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);
    private final AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
    private final MfaPendingService mfaPendingService = mock(MfaPendingService.class);
    private final LoginEventPublisher loginEventPublisher = mock(LoginEventPublisher.class);
    private final FilterChain chain = mock(FilterChain.class);

    private final ClientAdmissionFilter filter = new ClientAdmissionFilter(
            clientRepository, realmProvider, challengeService, mfaPendingService, loginEventPublisher);

    @AfterEach
    void clearRealm() {
        RealmContextHolder.clear();
    }

    @Test
    @DisplayName("配置损坏态不走快路径:GET 直接 403,不签票据、不跳挑战页")
    void denyAllDoesNotTakeFastPath() throws Exception {
        // denyAll 的白名单同样为空。快路径若只判断"白名单为空且未强制 MFA",
        // 就会把这条限制放过去——这个用例就是用来钉住那个条件的
        when(clientRepository.findByClientId("app")).thenReturn(client("TOTP"));
        MockHttpServletRequest request = authorizeRequest("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus(), "配置损坏态必须直接拒绝");
        assertNull(response.getRedirectedUrl(), "配置损坏态不应跳转补验页");
        verifyNoInteractions(challengeService);
        verify(loginEventPublisher).publish(eq("default"), eq(7L), eq("alice"),
                eq(LoginEventEvent.CLIENT_ADMISSION_DENIED), eq("PASSWORD"),
                any(), any(), anyString());
    }

    @Test
    @DisplayName("POST 授权请求同样要拦:只拦 GET 等于留了一条绕过通道")
    void postAuthorizeRequestIsFilteredToo() throws Exception {
        // SAS 的授权端点 RequestMatcher 同时匹配 GET 与 POST:POST 带 response_type/redirect_uri
        // 就是一次完整授权请求。只拦 GET 的话,直接 POST 就能拿到授权码
        when(clientRepository.findByClientId("app")).thenReturn(client("TOTP"));
        MockHttpServletRequest request = authorizeRequest("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus(), "POST 授权请求不能绕过准入闸门");
        verifyNoInteractions(challengeService);
    }

    @Test
    @DisplayName("POST 补验的回跳地址带上表单里的授权参数")
    void postChallengeKeepsAuthorizationParameters() throws Exception {
        when(clientRepository.findByClientId("app")).thenReturn(client("SMS"));
        when(challengeService.issue(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new AdmissionChallenge("sid-1", "app", Set.of("SMS"), false,
                        AUTHORIZE_PATH, "tok", System.currentTimeMillis()));
        MockHttpServletRequest request = authorizeRequest("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // POST 的授权参数在表单体里,getQueryString() 为空;照抄会让回跳丢失 client_id 等必填项
        ArgumentCaptor<String> returnTo = ArgumentCaptor.forClass(String.class);
        verify(challengeService).issue(eq("default"), eq("sid-1"), eq("app"), any(), returnTo.capture());
        assertTrue(returnTo.getValue().startsWith(AUTHORIZE_PATH + "?"), "回跳必须是本域授权端点: " + returnTo.getValue());
        assertTrue(returnTo.getValue().contains("client_id=app"), "回跳应带 client_id: " + returnTo.getValue());
        assertTrue(returnTo.getValue().contains("response_type=code"), "回跳应带 response_type: " + returnTo.getValue());
        assertEquals("/login-challenge?realm=default&token=tok", response.getRedirectedUrl());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("无域上下文的全局授权路径按 default 域回落判定:不能靠猜一条不带域前缀的路径绕过准入")
    void globalPathFallsBackToDefaultRealm() throws Exception {
        when(realmProvider.findByRealmKey("default")).thenReturn(java.util.Optional.of(defaultRealm()));
        when(clientRepository.findByClientId("app")).thenReturn(client("TOTP"));
        MockHttpServletRequest request = authorizeRequest("GET", "/oauth2/authorize");
        RealmContextHolder.clear();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus(), "全局路径同样要过准入判定");
        verify(loginEventPublisher).publish(eq("default"), eq(7L), eq("alice"),
                eq(LoginEventEvent.CLIENT_ADMISSION_DENIED), eq("PASSWORD"), any(), any(), anyString());
    }

    @Test
    @DisplayName("域解析不出来时放行:客户端仓储同样查不到客户端,由协议栈报错")
    void passesWhenNoRealmResolvable() throws Exception {
        when(realmProvider.findByRealmKey("default")).thenReturn(java.util.Optional.empty());
        when(clientRepository.findByClientId("app")).thenReturn(client("SMS"));
        MockHttpServletRequest request = authorizeRequest("GET", "/oauth2/authorize");
        RealmContextHolder.clear();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(chain).doFilter(request, response);
        verifyNoInteractions(challengeService);
    }

    // ===== 内部实现 =====

    /** 一次已登录会话发起的授权请求:GET 走查询串,POST 走表单参数,参数表形态一致。 */
    private MockHttpServletRequest authorizeRequest(String method) {
        return authorizeRequest(method, AUTHORIZE_PATH);
    }

    /** 指定路径的授权请求,用于覆盖不带域前缀的全局端点。 */
    private MockHttpServletRequest authorizeRequest(String method, String path) {
        RealmModel realm = defaultRealm();
        RealmContextHolder.set(new RealmContext(1L, "default", realm));
        IamUserPrincipal principal = new IamUserPrincipal(7L, 1L, "default", "alice", "Alice",
                0, List.of(), Map.of(), 0L, "PASSWORD");
        IamSessionPayload payload = new IamSessionPayload(principal, System.currentTimeMillis(),
                3600, 1800, System.currentTimeMillis(), false);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addParameter("client_id", "app");
        request.addParameter("response_type", "code");
        request.addParameter("redirect_uri", "https://app.example.com/callback");
        request.setAttribute(IamRedisSecurityContextRepository.SESSION_ID_ATTRIBUTE, "sid-1");
        request.setAttribute(IamRedisSecurityContextRepository.SESSION_PAYLOAD_ATTRIBUTE, payload);
        return request;
    }

    /** default 域。 */
    private static RealmModel defaultRealm() {
        return new RealmModel(1L, "default", "默认域", true, true,
                300L, 1800L, 1800L, 36000L, null, null);
    }

    /** 指定白名单的客户端;"TOTP" 不是第一因素词表里的值,用来构造配置损坏态。 */
    private static RegisteredClient client(String allowedTypes) {
        return RegisteredClient.withId("1")
                .clientId("app")
                .redirectUri("https://app.example.com/callback")
                .clientAuthenticationMethods(methods -> methods.add(ClientAuthenticationMethod.NONE))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientSettings(ClientSettings.builder()
                        .setting(IamRegisteredClientRepository.SETTING_ALLOWED_LOGIN_TYPES, allowedTypes)
                        .build())
                .build();
    }
}
