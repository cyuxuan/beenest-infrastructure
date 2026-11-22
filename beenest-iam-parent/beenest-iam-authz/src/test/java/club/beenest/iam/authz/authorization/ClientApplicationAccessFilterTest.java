package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户端应用授权闸门测试。
 * <p>门户的可见性只挡住"从门户点进去",知道 client_id 与回调地址的人仍可直接走授权码流程;
 * 这条链路要么放行(可见、未绑定应用、登录前),要么 403,不能有第三种结果。</p>
 */
class ClientApplicationAccessFilterTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final String CLIENT_ID = "erp-client";

    private final RegisteredClientRepository clientRepository = mock(RegisteredClientRepository.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);
    private final ApplicationVisibilityService visibilityService = mock(ApplicationVisibilityService.class);
    private final LoginEventPublisher loginEventPublisher = mock(LoginEventPublisher.class);

    private final RegisteredClient registeredClient = mock(RegisteredClient.class);
    private final ApplicationService app = app();

    @BeforeEach
    void setUp() {
        RealmContextHolder.set(new RealmContext(REALM_ID, "default", null));
        SecurityContextHolder.clearContext();
        when(registeredClient.getClientId()).thenReturn(CLIENT_ID);
        when(clientRepository.findByClientId(CLIENT_ID)).thenReturn(registeredClient);
    }

    @AfterEach
    void tearDown() {
        RealmContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("开关关闭时直接放行:留出灰度切换的退路")
    void passesThroughWhenDisabled() throws Exception {
        assertThat(doFilter(filter(false), authorizeRequest())).isEqualTo(200);
        verify(visibilityService, never()).canAccess(any(), any());
    }

    @Test
    @DisplayName("非授权端点不介入")
    void ignoresOtherPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realms/default/login");
        request.addParameter("client_id", CLIENT_ID);

        assertThat(doFilter(filter(true), request)).isEqualTo(200);
        verify(visibilityService, never()).boundApplication(any(), any());
    }

    @Test
    @DisplayName("未绑定应用服务的客户端放行:平台自身与机器对机器没有门户可见性概念")
    void passesUnboundClient() throws Exception {
        when(visibilityService.boundApplication(REALM_ID, registeredClient)).thenReturn(null);

        assertThat(doFilter(filter(true), authorizeRequest())).isEqualTo(200);
        verify(visibilityService, never()).canAccess(any(), any());
    }

    @Test
    @DisplayName("未登录时放行:登录完成后的授权请求会再次经过本过滤器")
    void passesAnonymousRequest() throws Exception {
        when(visibilityService.boundApplication(REALM_ID, registeredClient)).thenReturn(app);

        assertThat(doFilter(filter(true), authorizeRequest())).isEqualTo(200);
        verify(visibilityService, never()).canAccess(any(), any());
    }

    @Test
    @DisplayName("用户对目标应用可见时放行")
    void passesWhenVisible() throws Exception {
        when(visibilityService.boundApplication(REALM_ID, registeredClient)).thenReturn(app);
        when(visibilityService.canAccess(any(IamUserPrincipal.class), eq(app))).thenReturn(true);
        login();

        assertThat(doFilter(filter(true), authorizeRequest())).isEqualTo(200);
    }

    @Test
    @DisplayName("用户对目标应用不可见时 403 并留下失败事件")
    void rejectsWhenInvisible() throws Exception {
        when(visibilityService.boundApplication(REALM_ID, registeredClient)).thenReturn(app);
        when(visibilityService.canAccess(any(IamUserPrincipal.class), eq(app))).thenReturn(false);
        login();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(true).doFilter(authorizeRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        // 拒绝必须留痕:否则"谁在什么时候试图直接走协议端点"无从追查
        verify(loginEventPublisher).publish(eq("default"), eq(USER_ID), anyString(),
                eq(LoginEventEvent.APPLICATION_ACCESS_DENIED), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("无域上下文的全局授权路径按 default 域判定:不能靠猜一条不带域前缀的路径绕过闸门")
    void fallsBackToDefaultRealmWithoutContext() throws Exception {
        RealmContextHolder.clear();
        when(realmProvider.findByRealmKey("default")).thenReturn(java.util.Optional.of(defaultRealm()));
        when(visibilityService.boundApplication(REALM_ID, registeredClient)).thenReturn(app);
        when(visibilityService.canAccess(any(IamUserPrincipal.class), eq(app))).thenReturn(false);
        login();

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.addParameter("client_id", CLIENT_ID);

        assertThat(doFilter(filter(true), request)).isEqualTo(403);
        verify(loginEventPublisher).publish(eq("default"), eq(USER_ID), anyString(),
                eq(LoginEventEvent.APPLICATION_ACCESS_DENIED), anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("域解析不出来时放行:客户端仓储同样查不到客户端,由协议栈报错")
    void passesWhenNoRealmResolvable() throws Exception {
        RealmContextHolder.clear();
        when(realmProvider.findByRealmKey("default")).thenReturn(java.util.Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.addParameter("client_id", CLIENT_ID);

        assertThat(doFilter(filter(true), request)).isEqualTo(200);
        verify(visibilityService, never()).boundApplication(any(), any());
    }

    /** 写入已登录主体:过滤器从安全上下文取用户,与真实过滤器链一致。 */
    private static void login() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal(), null, List.of()));
    }

    /** 构造过滤器;enabled 为闸门开关。 */
    private ClientApplicationAccessFilter filter(boolean enabled) {
        return new ClientApplicationAccessFilter(clientRepository, realmProvider, visibilityService,
                loginEventPublisher, enabled);
    }

    /** 执行过滤并返回响应状态。 */
    private static int doFilter(ClientApplicationAccessFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    /** 授权端点请求。 */
    private static MockHttpServletRequest authorizeRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/realms/default/oauth2/authorize");
        request.addParameter("client_id", CLIENT_ID);
        return request;
    }

    /** 登录主体。 */
    private static IamUserPrincipal principal() {
        return new IamUserPrincipal(USER_ID, REALM_ID, "default", "zhang", "张三", 0,
                List.of(), Map.of(), 1L, "PASSWORD");
    }

    /** 绑定到客户端的目标应用。 */
    private static ApplicationService app() {
        ApplicationService service = new ApplicationService();
        service.setId(10L);
        service.setRealmId(REALM_ID);
        service.setAppCode("erp");
        service.setVisibility("PERMISSION");
        service.setRequiredPermissionCode("app:erp:use");
        service.setEnabled(Boolean.TRUE);
        return service;
    }

    /** default 域。 */
    private static RealmModel defaultRealm() {
        return new RealmModel(REALM_ID, "default", "默认域", true, true,
                300L, 1800L, 1800L, 36000L, null, null);
    }
}
