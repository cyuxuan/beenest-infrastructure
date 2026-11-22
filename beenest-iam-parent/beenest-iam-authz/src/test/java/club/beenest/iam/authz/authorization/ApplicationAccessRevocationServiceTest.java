package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.logout.IamLogoutBroadcastService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.core.model.ClientSessionModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.spi.provider.ClientSessionStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 应用访问收回测试。
 * <p>锁定三条边界:只收回被移除的用户,只收回目标应用,仍可通过其它授权进入的用户不动。</p>
 */
class ApplicationAccessRevocationServiceTest {

    private static final Long REALM_ID = 1L;
    private static final Long APP_ID = 10L;
    private static final Long USER_ID = 7L;
    private static final Long CLIENT_ID = 100L;
    private static final Long OTHER_CLIENT_ID = 200L;
    private static final String REALM_KEY = "default";
    private static final String SID = "sid-revoke-0001";

    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final ApplicationVisibilityService visibilityService = mock(ApplicationVisibilityService.class);
    private final RedisOAuth2AuthorizationService authorizationService =
            mock(RedisOAuth2AuthorizationService.class);
    private final ClientSessionStore clientSessionStore = mock(ClientSessionStore.class);
    private final IamLogoutBroadcastService broadcastService = mock(IamLogoutBroadcastService.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);

    private ApplicationAccessRevocationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationAccessRevocationService(appServiceManager, visibilityService,
                authorizationService, clientSessionStore, broadcastService, realmProvider);
    }

    @Test
    @DisplayName("收回目标用户:只吊销该客户端授权,定向登出并关闭该客户端会话")
    void revokesOnlyTargetClient() {
        ApplicationService app = app("ROLE", CLIENT_ID);
        when(appServiceManager.getById(APP_ID)).thenReturn(app);
        when(visibilityService.canAccessByUserId(REALM_ID, USER_ID, app)).thenReturn(false);
        OAuth2Authorization target = authorization("100", USER_ID);
        OAuth2Authorization other = authorization("200", USER_ID);
        when(authorizationService.listByUser(USER_ID)).thenReturn(List.of(target, other));
        ClientSessionModel session = new ClientSessionModel(1L, REALM_ID, 10L, SID, USER_ID,
                CLIENT_ID, "drone");
        when(clientSessionStore.listByUserAndClient(REALM_ID, USER_ID, CLIENT_ID))
                .thenReturn(List.of(session));
        when(realmProvider.findById(REALM_ID))
                .thenReturn(Optional.of(realm(REALM_ID, REALM_KEY)));

        service.revokeOnGrantRemoved(REALM_ID, APP_ID, List.of(USER_ID));

        // 1. 只吊销目标客户端的授权,其它应用的令牌保持有效
        verify(authorizationService).remove(target);
        verify(authorizationService, never()).remove(other);
        // 2. 只向目标应用广播,其它应用不被踢下线
        verify(broadcastService).broadcastToClient(eq(REALM_ID), eq(REALM_KEY), eq(SID),
                any(IamUserPrincipal.class), eq(CLIENT_ID));
        // 3. 只关闭目标客户端的会话记录
        verify(clientSessionStore).closeByUserAndClient(REALM_ID, USER_ID, CLIENT_ID);
        verify(clientSessionStore, never()).closeBySid(any());
        verify(clientSessionStore, never()).closeByUserAndClient(REALM_ID, USER_ID, OTHER_CLIENT_ID);
    }

    @Test
    @DisplayName("用户仍可通过角色进入时不收回")
    void keepsAccessWhenStillGrantedByRole() {
        ApplicationService app = app("ROLE", CLIENT_ID);
        when(appServiceManager.getById(APP_ID)).thenReturn(app);
        when(visibilityService.canAccessByUserId(REALM_ID, USER_ID, app)).thenReturn(true);

        service.revokeOnGrantRemoved(REALM_ID, APP_ID, List.of(USER_ID));

        verifyNoInteractions(authorizationService, clientSessionStore, broadcastService);
    }

    @Test
    @DisplayName("全员可见与按权限码可见不依赖授权记录,移除直授不收回")
    void skipsVisibilityThatDoesNotUseGrants() {
        ApplicationService publicApp = app("PUBLIC", CLIENT_ID);
        when(appServiceManager.getById(APP_ID)).thenReturn(publicApp);

        service.revokeOnGrantRemoved(REALM_ID, APP_ID, List.of(USER_ID));

        verifyNoInteractions(authorizationService, clientSessionStore, broadcastService);
        verify(visibilityService, never()).canAccessByUserId(any(), any(), any());
    }

    @Test
    @DisplayName("未绑定客户端的应用没有协议授权记录,直接跳过")
    void skipsAppWithoutClient() {
        ApplicationService app = app("ROLE", null);
        when(appServiceManager.getById(APP_ID)).thenReturn(app);

        service.revokeOnGrantRemoved(REALM_ID, APP_ID, List.of(USER_ID));

        verifyNoInteractions(authorizationService, clientSessionStore, broadcastService);
    }

    @Test
    @DisplayName("空名单不触发任何动作")
    void ignoresEmptyUserIds() {
        service.revokeOnGrantRemoved(REALM_ID, APP_ID, List.of());
        service.revokeOnGrantRemoved(REALM_ID, APP_ID, null);

        verifyNoInteractions(appServiceManager, authorizationService, clientSessionStore, broadcastService);
    }

    private static ApplicationService app(String visibility, Long clientPk) {
        ApplicationService app = new ApplicationService();
        app.setId(APP_ID);
        app.setRealmId(REALM_ID);
        app.setAppCode("drone");
        app.setVisibility(visibility);
        app.setSsoClientPk(clientPk);
        app.setEnabled(Boolean.TRUE);
        return app;
    }

    private static OAuth2Authorization authorization(String clientDbId, Long userId) {
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorization.getRegisteredClientId()).thenReturn(clientDbId);
        IamUserPrincipal principal = new IamUserPrincipal(userId, REALM_ID, REALM_KEY, "zhang", "张三",
                0, List.of(), Map.of(), 1L, "PASSWORD");
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        when(authorization.getAttribute(Principal.class.getName())).thenReturn(authentication);
        return authorization;
    }

    private static RealmModel realm(Long id, String realmKey) {
        return new RealmModel(id, realmKey, realmKey, true, true, 300L, 1800L, 1800L, 36000L,
                SubjectMode.LOGIN_NAME, null);
    }
}
