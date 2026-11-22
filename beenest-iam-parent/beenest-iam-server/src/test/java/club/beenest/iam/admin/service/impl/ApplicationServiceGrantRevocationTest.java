package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ApplicationServiceDtoConverter;
import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.persistence.entity.AppServiceGrant;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用授权更新测试。
 * <p>重点锁定"被移除的 USER 直授"口径:交给收回服务的是旧名单减新名单,
 * 反过来会把刚加进来的人踢下线,而真正被移除的人继续保有访问。</p>
 */
class ApplicationServiceGrantRevocationTest {

    private static final Long REALM_ID = 1L;
    private static final Long APP_ID = 10L;

    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamAppAdminManager appAdminManager = mock(IamAppAdminManager.class);
    private final IamAppRoleMappingManager appRoleMappingManager = mock(IamAppRoleMappingManager.class);
    private final IamClientManager clientManager = mock(IamClientManager.class);
    private final IamRoleManager roleManager = mock(IamRoleManager.class);
    private final IamUserManager userManager = mock(IamUserManager.class);
    private final IamGroupManager groupManager = mock(IamGroupManager.class);
    private final IamPermissionManager permissionManager = mock(IamPermissionManager.class);
    private final ApplicationServiceDtoConverter dtoConverter = mock(ApplicationServiceDtoConverter.class);
    private final ApplicationAccessRevocationService revocationService =
            mock(ApplicationAccessRevocationService.class);

    private final ApplicationServiceServiceImpl service = new ApplicationServiceServiceImpl(
            appServiceManager, appAdminManager, appRoleMappingManager, clientManager, roleManager,
            userManager, groupManager, permissionManager, dtoConverter, revocationService);

    @BeforeEach
    void setUp() {
        IamUserPrincipal operator = new IamUserPrincipal(
                2L, REALM_ID, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
        ApplicationService app = new ApplicationService();
        app.setId(APP_ID);
        app.setRealmId(REALM_ID);
        app.setAppCode("drone");
        app.setVisibility("ROLE");
        app.setEnabled(Boolean.TRUE);
        when(appServiceManager.getById(APP_ID)).thenReturn(app);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("移除 USER 直授时只收回旧名单中不再出现的用户")
    void revokesOnlyRemovedUserGrants() {
        when(appServiceManager.listGrants(APP_ID)).thenReturn(List.of(grant(7L), grant(8L)));
        when(userManager.getById(8L)).thenReturn(user(8L));

        service.replaceGrants(APP_ID, "USER", List.of(8L));

        verify(revocationService).revokeOnGrantRemoved(REALM_ID, APP_ID, List.of(7L));
    }

    @Test
    @DisplayName("新增 USER 直授不触发收回")
    void doesNotRevokeNewlyAddedUser() {
        when(appServiceManager.listGrants(APP_ID)).thenReturn(List.of(grant(7L)));
        when(userManager.getById(7L)).thenReturn(user(7L));
        when(userManager.getById(9L)).thenReturn(user(9L));

        service.replaceGrants(APP_ID, "USER", List.of(7L, 9L));

        verify(revocationService, never()).revokeOnGrantRemoved(eq(REALM_ID), eq(APP_ID), anyList());
    }

    @Test
    @DisplayName("角色或组授权更新不触发用户维收回")
    void doesNotRevokeForRoleOrGroup() {
        when(appServiceManager.listGrants(APP_ID)).thenReturn(List.of());

        service.replaceGrants(APP_ID, "ROLE", List.of());

        verify(revocationService, never()).revokeOnGrantRemoved(eq(REALM_ID), eq(APP_ID), anyList());
    }

    private static AppServiceGrant grant(Long subjectId) {
        AppServiceGrant grant = new AppServiceGrant();
        grant.setRealmId(REALM_ID);
        grant.setAppServiceId(APP_ID);
        grant.setSubjectType("USER");
        grant.setSubjectId(subjectId);
        return grant;
    }

    private static club.beenest.iam.persistence.entity.User user(Long id) {
        club.beenest.iam.persistence.entity.User user = new club.beenest.iam.persistence.entity.User();
        user.setId(id);
        user.setRealmId(REALM_ID);
        return user;
    }
}
