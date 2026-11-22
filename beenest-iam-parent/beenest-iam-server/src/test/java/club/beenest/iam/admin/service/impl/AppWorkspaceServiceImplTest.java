package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.AppWorkspaceResponse;
import club.beenest.iam.admin.dto.MappingSourceResponse;
import club.beenest.iam.admin.dto.MenuCreateRequest;
import club.beenest.iam.admin.dto.MenuUpdateRequest;
import club.beenest.iam.admin.dto.PermissionCreateRequest;
import club.beenest.iam.admin.dto.PermissionUpdateRequest;
import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.service.ApplicationServiceService;
import club.beenest.iam.admin.service.MenuService;
import club.beenest.iam.admin.service.PermissionService;
import club.beenest.iam.admin.service.RoleService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用工作台作用域测试。
 * <p>工作台的授权边界是路径中的 appId,请求体与资源主键都不可信。
 * 这里锁定三类契约:创建时强制归属、跨应用主键按不存在处理、概览只统计当前应用。</p>
 */
class AppWorkspaceServiceImplTest {

    private static final Long REALM_ID = 1L;
    private static final Long OTHER_REALM_ID = 2L;
    private static final Long APP_ID = 7L;
    private static final Long OTHER_APP_ID = 8L;
    private static final Long USER_ID = 9L;
    private static final Long MENU_ID = 70L;
    private static final Long ROLE_ID = 71L;
    private static final Long PERMISSION_ID = 72L;

    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamMenuManager menuManager = mock(IamMenuManager.class);
    private final IamRoleManager roleManager = mock(IamRoleManager.class);
    private final IamPermissionManager permissionManager = mock(IamPermissionManager.class);
    private final IamGroupManager groupManager = mock(IamGroupManager.class);
    private final IamAppAdminManager appAdminManager = mock(IamAppAdminManager.class);
    private final IamAppRoleMappingManager appRoleMappingManager = mock(IamAppRoleMappingManager.class);
    private final ApplicationServiceService applicationServiceService = mock(ApplicationServiceService.class);
    private final MenuService menuService = mock(MenuService.class);
    private final RoleService roleService = mock(RoleService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final RoleDtoConverter roleDtoConverter = mock(RoleDtoConverter.class);

    private final AppWorkspaceServiceImpl service = new AppWorkspaceServiceImpl(
            appServiceManager, menuManager, roleManager, permissionManager, groupManager,
            appAdminManager, appRoleMappingManager, applicationServiceService,
            menuService, roleService, permissionService, roleDtoConverter);

    @BeforeEach
    void setUp() {
        login(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("创建菜单时忽略请求体应用,强制归属路径应用")
    void createMenuBindsPathApp() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));

        service.createMenu(APP_ID, new MenuCreateRequest(OTHER_APP_ID, null, "expense.list",
                "我的报销", "MENU", "/expense/list", "wallet", PERMISSION_ID, 1, true));

        verify(menuService).create(argThat(request ->
                APP_ID.equals(request.appServiceId())
                        && "expense.list".equals(request.menuCode())
                        && PERMISSION_ID.equals(request.permissionId())));
    }

    @Test
    @DisplayName("创建角色时强制使用 APP 容器与路径应用")
    void createRoleBindsAppContainer() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));

        service.createRole(APP_ID, new RoleCreateRequest("expense-auditor", "报销审核员",
                "REALM", null, OTHER_APP_ID));

        verify(roleService).create(argThat(request ->
                "APP".equals(request.containerType())
                        && APP_ID.equals(request.appServiceId())
                        && request.clientId() == null));
    }

    @Test
    @DisplayName("创建权限时强制归属路径应用的命名空间")
    void createPermissionBindsAppNamespace() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));

        service.createPermission(APP_ID, new PermissionCreateRequest(null, "expense:export",
                "导出报销", "expense", "export", "允许导出"));

        verify(permissionService).create(argThat(request ->
                APP_ID.equals(request.appServiceId())
                        && "expense:export".equals(request.permissionCode())));
    }

    @Test
    @DisplayName("跨应用菜单主键按不存在处理")
    void rejectsCrossAppMenu() {
        when(menuManager.getById(MENU_ID)).thenReturn(menu(MENU_ID, REALM_ID, OTHER_APP_ID));

        BusinessException error = assertThrows(BusinessException.class, () -> service.updateMenu(
                APP_ID, MENU_ID, new MenuUpdateRequest("越权改名", null, null, null, null, null, null)));

        assertTrue(error.getMessage().contains("菜单不存在"));
        verify(menuService, never()).update(any(), any());
        verify(menuService, never()).delete(any());
    }

    @Test
    @DisplayName("跨应用角色主键不能授权或读取菜单")
    void rejectsCrossAppRole() {
        when(roleManager.getById(ROLE_ID)).thenReturn(role(ROLE_ID, REALM_ID, OTHER_APP_ID));

        assertThrows(BusinessException.class,
                () -> service.assignRolePermissions(APP_ID, ROLE_ID, List.of(PERMISSION_ID)));
        assertThrows(BusinessException.class, () -> service.listRoleMenus(APP_ID, ROLE_ID));

        verify(roleService, never()).assignPermissions(any(), any());
        verify(roleService, never()).listMenus(any());
    }

    @Test
    @DisplayName("跨域权限主键不能修改")
    void rejectsCrossRealmPermission() {
        when(permissionManager.getById(PERMISSION_ID))
                .thenReturn(permission(PERMISSION_ID, OTHER_REALM_ID, APP_ID));

        assertThrows(BusinessException.class, () -> service.updatePermission(APP_ID, PERMISSION_ID,
                new PermissionUpdateRequest("越权改名", "")));

        verify(permissionService, never()).update(any(), any());
    }

    @Test
    @DisplayName("跨域应用整体拒绝且不读取业务数据")
    void rejectsCrossRealmApp() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, OTHER_REALM_ID));

        assertThrows(BusinessException.class, () -> service.listMenus(APP_ID));

        verify(menuService, never()).listByApp(APP_ID);
    }

    @Test
    @DisplayName("概览只统计当前应用且回填全局管理标记")
    void overviewCountsCurrentApp() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));
        when(menuManager.listByAppServiceId(REALM_ID, APP_ID))
                .thenReturn(List.of(menu(1L, REALM_ID, APP_ID), menu(2L, REALM_ID, APP_ID)));
        when(roleManager.listByAppServiceId(REALM_ID, APP_ID))
                .thenReturn(List.of(role(1L, REALM_ID, APP_ID)));
        when(permissionManager.listByAppServiceId(REALM_ID, APP_ID))
                .thenReturn(List.of(
                        permission(1L, REALM_ID, APP_ID),
                        permission(2L, REALM_ID, APP_ID),
                        permission(3L, REALM_ID, APP_ID)));
        when(appRoleMappingManager.listByAppServiceId(APP_ID)).thenReturn(List.of());
        when(appAdminManager.listByAppServiceId(APP_ID)).thenReturn(List.of());

        AppWorkspaceResponse response = service.overview(APP_ID);

        assertEquals(2, response.menuCount());
        assertEquals(1, response.roleCount());
        assertEquals(3, response.permissionCount());
        assertEquals(0, response.mappingCount());
        assertEquals(0, response.adminCount());
        assertFalse(response.globalManage());
    }

    @Test
    @DisplayName("持有全局应用管理权限时概览标记全局管理员")
    void overviewMarksGlobalManager() {
        login(List.of("app-service:update"));
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));
        when(menuManager.listByAppServiceId(REALM_ID, APP_ID)).thenReturn(List.of());
        when(roleManager.listByAppServiceId(REALM_ID, APP_ID)).thenReturn(List.of());
        when(permissionManager.listByAppServiceId(REALM_ID, APP_ID)).thenReturn(List.of());
        when(appRoleMappingManager.listByAppServiceId(APP_ID)).thenReturn(List.of());
        when(appAdminManager.listByAppServiceId(APP_ID)).thenReturn(List.of());

        assertTrue(service.overview(APP_ID).globalManage());
    }

    @Test
    @DisplayName("映射来源按关键字过滤且拒绝未知来源类型")
    void filtersMappingSources() {
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID, REALM_ID));
        when(groupManager.listByRealm(REALM_ID)).thenReturn(List.of(
                group(1L, "finance", "财务部"), group(2L, "engineering", "研发部")));

        List<MappingSourceResponse> groups = service.listMappingSources(APP_ID, "group", "fin");
        assertEquals(1, groups.size());
        assertEquals("finance", groups.get(0).code());

        assertThrows(BusinessException.class,
                () -> service.listMappingSources(APP_ID, "UNKNOWN", null));
    }

    private static void login(List<String> authorities) {
        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, REALM_ID, "default", "alice",
                "Alice", 1, authorities, Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static ApplicationService app(Long id, Long realmId) {
        ApplicationService app = new ApplicationService();
        app.setId(id);
        app.setRealmId(realmId);
        app.setAppCode("expense");
        app.setAppName("报销中心");
        return app;
    }

    private static Menu menu(Long id, Long realmId, Long appServiceId) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setRealmId(realmId);
        menu.setAppServiceId(appServiceId);
        return menu;
    }

    private static Role role(Long id, Long realmId, Long appServiceId) {
        Role role = new Role();
        role.setId(id);
        role.setRealmId(realmId);
        role.setAppServiceId(appServiceId);
        role.setRoleCode("expense-auditor");
        role.setRoleName("报销审核员");
        return role;
    }

    private static Permission permission(Long id, Long realmId, Long appServiceId) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setRealmId(realmId);
        permission.setAppServiceId(appServiceId);
        permission.setPermissionCode("expense:export");
        permission.setPermissionName("导出报销");
        return permission;
    }

    private static Group group(Long id, String code, String name) {
        Group group = new Group();
        group.setId(id);
        group.setGroupCode(code);
        group.setGroupName(name);
        return group;
    }
}
