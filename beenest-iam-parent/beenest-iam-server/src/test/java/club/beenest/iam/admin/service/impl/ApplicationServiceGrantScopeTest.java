package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ApplicationServiceDtoConverter;
import club.beenest.iam.admin.dto.AppServiceGrantResponse;
import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.AppServiceGrant;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用授权主体作用域测试。
 * <p>应用角色只能授权给所属应用;已删除角色的历史授权行不回读,整表覆盖保存
 * 不再因"主体不存在"被拒绝。</p>
 */
class ApplicationServiceGrantScopeTest {

    private static final Long REALM_ID = 1L;
    private static final Long APP_ID = 10L;
    private static final Long OTHER_APP_ID = 11L;

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
        ApplicationService other = new ApplicationService();
        other.setId(OTHER_APP_ID);
        other.setRealmId(REALM_ID);
        other.setAppCode("crm");
        when(appServiceManager.getById(OTHER_APP_ID)).thenReturn(other);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("本应用的应用角色可以授权")
    void allowsAppRoleOfSameApp() {
        when(roleManager.getById(4L)).thenReturn(role(4L, APP_ID));
        when(appServiceManager.listGrants(APP_ID)).thenReturn(List.of());

        service.replaceGrants(APP_ID, "ROLE", List.of(4L));

        verify(appServiceManager).replaceGrants(REALM_ID, APP_ID, "ROLE", List.of(4L));
    }

    @Test
    @DisplayName("其他应用的应用角色拒绝授权,并指出它属于哪个应用")
    void rejectsAppRoleOfAnotherApp() {
        when(roleManager.getById(4L)).thenReturn(role(4L, OTHER_APP_ID));

        assertThatThrownBy(() -> service.replaceGrants(APP_ID, "ROLE", List.of(4L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能授权给所属应用")
                .hasMessageContaining("crm");

        verify(appServiceManager, never()).replaceGrants(REALM_ID, APP_ID, "ROLE", List.of(4L));
    }

    @Test
    @DisplayName("域角色可以授权给任意应用")
    void allowsRealmRole() {
        when(roleManager.getById(1L)).thenReturn(role(1L, null));
        when(appServiceManager.listGrants(APP_ID)).thenReturn(List.of());

        service.replaceGrants(APP_ID, "ROLE", List.of(1L));

        verify(appServiceManager).replaceGrants(REALM_ID, APP_ID, "ROLE", List.of(1L));
    }

    @Test
    @DisplayName("已删除角色的历史授权行不再回读")
    void hidesGrantsOfDeletedRole() {
        when(appServiceManager.listGrants(APP_ID)).thenReturn(
                List.of(grant(APP_ID, 1L), grant(APP_ID, 99L)));
        when(roleManager.getById(1L)).thenReturn(role(1L, null));
        when(roleManager.getById(99L)).thenReturn(null);

        List<AppServiceGrantResponse> grants = service.listGrants(APP_ID, "ROLE");

        assertThat(grants).singleElement()
                .extracting(AppServiceGrantResponse::subjectId)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("属于其他应用的应用角色授权行不再回读")
    void hidesGrantsOfRoleFromAnotherApp() {
        when(appServiceManager.listGrants(APP_ID)).thenReturn(List.of(grant(APP_ID, 4L)));
        when(roleManager.getById(4L)).thenReturn(role(4L, OTHER_APP_ID));

        assertThat(service.listGrants(APP_ID, "ROLE")).isEmpty();
    }

    private static AppServiceGrant grant(Long appServiceId, Long subjectId) {
        AppServiceGrant grant = new AppServiceGrant();
        grant.setId(subjectId);
        grant.setRealmId(REALM_ID);
        grant.setAppServiceId(appServiceId);
        grant.setSubjectType("ROLE");
        grant.setSubjectId(subjectId);
        return grant;
    }

    private static Role role(Long id, Long appServiceId) {
        Role role = new Role();
        role.setId(id);
        role.setRealmId(REALM_ID);
        role.setContainerType(appServiceId == null ? "REALM" : "APP");
        role.setAppServiceId(appServiceId);
        role.setIsActive(Boolean.TRUE);
        return role;
    }
}
