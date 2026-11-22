package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.persistence.entity.ApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 刷新令牌应用闸门测试。
 * <p>核心约束:授权被收回后,刷新令牌不能再换出新令牌;仍有访问权时必须放行给
 * 默认 Provider,闸门不能把正常刷新拦掉。</p>
 */
class ApplicationAccessRefreshProviderTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final Long CLIENT_ID = 100L;
    private static final String CLIENT_DB_ID = "100";

    private final OAuth2AuthorizationService authorizationService = mock(OAuth2AuthorizationService.class);
    private final RegisteredClientRepository clientRepository = mock(RegisteredClientRepository.class);
    private final ApplicationVisibilityService visibilityService = mock(ApplicationVisibilityService.class);
    private final IamClientSessionService clientSessionService = mock(IamClientSessionService.class);
    private final SessionRevocationService revocationService = mock(SessionRevocationService.class);

    private ApplicationAccessRefreshProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ApplicationAccessRefreshProvider(authorizationService, clientRepository,
                visibilityService, clientSessionService, revocationService, true);
    }

    @Test
    @DisplayName("授权已收回时以 invalid_grant 拒绝刷新")
    void rejectsRefreshAfterGrantRevoked() {
        ApplicationService app = app("ROLE");
        givenAuthorization(app, true);

        assertThatThrownBy(() -> provider.authenticate(refreshToken()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(error -> assertThat(((OAuth2AuthenticationException) error).getError().getErrorCode())
                        .isEqualTo(OAuth2ErrorCodes.INVALID_GRANT));
    }

    @Test
    @DisplayName("仍有访问权时返回 null,交给默认刷新 Provider 正常换票")
    void passesThroughWhenStillEntitled() {
        ApplicationService app = app("ROLE");
        givenAuthorization(app, false);

        assertThat(provider.authenticate(refreshToken())).isNull();
        verify(authorizationService, never()).remove(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("闸门关闭时不做任何查询,保持旧行为")
    void skipsEverythingWhenDisabled() {
        ApplicationAccessRefreshProvider disabled = new ApplicationAccessRefreshProvider(
                authorizationService, clientRepository, visibilityService, clientSessionService,
                revocationService, false);

        assertThat(disabled.authenticate(refreshToken())).isNull();
        verifyNoInteractions(authorizationService, clientRepository, visibilityService);
    }

    @Test
    @DisplayName("刷新令牌查不到授权记录时交给默认 Provider 报标准错误")
    void passesThroughWhenAuthorizationMissing() {
        when(authorizationService.findByToken("refresh-token", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(null);

        assertThat(provider.authenticate(refreshToken())).isNull();
        verifyNoInteractions(clientRepository, visibilityService);
    }

    @Test
    @DisplayName("客户端未绑定应用时不做可见性判定")
    void passesThroughWhenClientUnbound() {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken("refresh-token", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(authorization);
        when(authorization.getRegisteredClientId()).thenReturn(CLIENT_DB_ID);
        when(authorization.getAttribute(Principal.class.getName())).thenReturn(authentication());
        RegisteredClient client = registeredClient();
        when(clientRepository.findById(CLIENT_DB_ID)).thenReturn(client);
        when(visibilityService.boundApplication(REALM_ID, client)).thenReturn(null);

        assertThat(provider.authenticate(refreshToken())).isNull();
        verify(visibilityService, never()).canAccessByUserId(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("授权记录不含用户主体(机器对机器)时不介入")
    void passesThroughWithoutUserPrincipal() {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken("refresh-token", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(authorization);
        when(authorization.getAttribute(Principal.class.getName())).thenReturn(null);

        assertThat(provider.authenticate(refreshToken())).isNull();
        verifyNoInteractions(clientRepository, visibilityService);
    }

    @Test
    @DisplayName("只支持刷新令牌认证")
    void supportsOnlyRefreshToken() {
        assertThat(provider.supports(OAuth2RefreshTokenAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
    }

    private void givenAuthorization(ApplicationService app, boolean rejected) {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorizationService.findByToken("refresh-token", OAuth2TokenType.REFRESH_TOKEN))
                .thenReturn(authorization);
        when(authorization.getRegisteredClientId()).thenReturn(CLIENT_DB_ID);
        when(authorization.getAttribute(Principal.class.getName())).thenReturn(authentication());
        RegisteredClient client = registeredClient();
        when(clientRepository.findById(CLIENT_DB_ID)).thenReturn(client);
        when(visibilityService.boundApplication(REALM_ID, client)).thenReturn(app);
        when(visibilityService.canAccessByUserId(REALM_ID, USER_ID, app)).thenReturn(!rejected);
    }

    private static OAuth2RefreshTokenAuthenticationToken refreshToken() {
        // 客户端认证主体由客户端身份认证环节提供,这里只需一个非空占位
        Authentication clientPrincipal =
                UsernamePasswordAuthenticationToken.authenticated("drone", null, List.of());
        return new OAuth2RefreshTokenAuthenticationToken("refresh-token", clientPrincipal, Set.of(), Map.of());
    }

    private static Authentication authentication() {
        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, REALM_ID, "default", "zhang", "张三",
                0, List.of(), Map.of(), 1L, "PASSWORD");
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private static RegisteredClient registeredClient() {
        RegisteredClient client = mock(RegisteredClient.class);
        when(client.getId()).thenReturn(CLIENT_DB_ID);
        when(client.getClientId()).thenReturn("drone");
        return client;
    }

    private static ApplicationService app(String visibility) {
        ApplicationService app = new ApplicationService();
        app.setId(10L);
        app.setRealmId(REALM_ID);
        app.setAppCode("drone");
        app.setVisibility(visibility);
        app.setEnabled(Boolean.TRUE);
        return app;
    }
}
