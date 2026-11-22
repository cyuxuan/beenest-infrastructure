package club.beenest.iam.authz;

import club.beenest.iam.authz.authorization.AppAuthorizationService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 应用作用域鉴权回归测试。
 * <p>锁定四条契约:全局权限优先、应用管理员按用户或组生效、
 * 非管理员拒绝、跨域应用一律拒绝。</p>
 */
class AppAuthorizationServiceTest {

    private static final Long REALM_ID = 1L;
    private static final Long APP_ID = 7L;
    private static final Long USER_ID = 9L;
    private static final String GLOBAL_MANAGE_AUTHORITY = "app-service:update";

    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamAppAdminManager appAdminManager = mock(IamAppAdminManager.class);
    private final IamEffectiveRoleResolver effectiveRoleResolver = mock(IamEffectiveRoleResolver.class);
    private final AppAuthorizationService service =
            new AppAuthorizationService(appServiceManager, appAdminManager, effectiveRoleResolver);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未登录或应用主键为空时拒绝")
    void deniesWithoutPrincipalOrApp() {
        assertFalse(service.canManage(null));
        assertFalse(service.canManage(APP_ID));
        verifyNoInteractions(appServiceManager, appAdminManager, effectiveRoleResolver);
    }

    @Test
    @DisplayName("跨域应用即使持有全局权限也拒绝")
    void deniesCrossRealmApp() {
        login(List.of(GLOBAL_MANAGE_AUTHORITY));
        when(appServiceManager.getById(APP_ID)).thenReturn(app(2L));

        assertFalse(service.canManage(APP_ID));
        verifyNoInteractions(appAdminManager);
    }

    @Test
    @DisplayName("持有 app-service:update 的全局管理员直接放行")
    void allowsGlobalManager() {
        login(List.of(GLOBAL_MANAGE_AUTHORITY));
        when(appServiceManager.getById(APP_ID)).thenReturn(app(REALM_ID));

        assertTrue(service.canManage(APP_ID));
        verifyNoInteractions(appAdminManager, effectiveRoleResolver);
    }

    @Test
    @DisplayName("应用管理员经所属用户组授权时放行")
    void allowsAppAdminThroughGroup() {
        login(List.of());
        when(appServiceManager.getById(APP_ID)).thenReturn(app(REALM_ID));
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of(5L));
        when(appAdminManager.isAdmin(REALM_ID, APP_ID, USER_ID, List.of(5L))).thenReturn(true);

        assertTrue(service.canManage(APP_ID));
    }

    @Test
    @DisplayName("登录用户不在管理员名单时拒绝")
    void deniesNonAdmin() {
        login(List.of());
        when(appServiceManager.getById(APP_ID)).thenReturn(app(REALM_ID));
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());
        when(appAdminManager.isAdmin(REALM_ID, APP_ID, USER_ID, List.of())).thenReturn(false);

        assertFalse(service.canManage(APP_ID));
    }

    @Test
    @DisplayName("应用不存在时拒绝且不查询管理员名单")
    void deniesMissingApp() {
        login(List.of());
        when(appServiceManager.getById(APP_ID)).thenReturn(null);

        assertFalse(service.canManage(APP_ID));
        verify(appAdminManager, never()).isAdmin(REALM_ID, APP_ID, USER_ID, List.of());
    }

    private static void login(List<String> authorities) {
        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, REALM_ID, "default", "alice", null, 1,
                authorities, Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static ApplicationService app(Long realmId) {
        ApplicationService app = new ApplicationService();
        app.setId(APP_ID);
        app.setRealmId(realmId);
        app.setAppCode("oa");
        app.setAppName("协同办公");
        return app;
    }
}
