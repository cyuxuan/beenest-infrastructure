package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.RoleMenu;
import club.beenest.iam.persistence.entity.RolePermission;
import club.beenest.iam.persistence.mapper.ApplicationServiceMapper;
import club.beenest.iam.persistence.mapper.MenuMapper;
import club.beenest.iam.persistence.mapper.PermissionMapper;
import club.beenest.iam.persistence.mapper.RoleMapper;
import club.beenest.iam.persistence.mapper.RoleMenuMapper;
import club.beenest.iam.persistence.mapper.RolePermissionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用权限回填测试。
 * <p>回填的口径是"应用切到 SCOPED 后会缺什么":平台级权限与菜单在应用视图下
 * 会被裁掉,因此必须迁到应用名下。这里锁定三类契约:候选识别、归属迁移只动平台行、
 * 复制到应用时授权引用改指向副本并给域角色追加引用。</p>
 */
class IamAppBackfillManagerTest {

    private static final Long REALM_ID = 1L;
    private static final Long APP_ID = 7L;
    private static final Long OTHER_APP_ID = 8L;
    private static final Long APP_ROLE_ID = 101L;
    private static final Long REALM_ROLE_ID = 102L;
    private static final Long PLATFORM_PERMISSION_ID = 201L;
    private static final Long APP_PERMISSION_ID = 202L;
    private static final Long APP_COPY_PERMISSION_ID = 203L;
    private static final Long PLATFORM_MENU_ID = 301L;

    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final MenuMapper menuMapper = mock(MenuMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private final RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
    private final ApplicationServiceMapper applicationServiceMapper = mock(ApplicationServiceMapper.class);

    private final IamAppBackfillManager manager = new IamAppBackfillManager(permissionMapper, menuMapper,
            roleMapper, rolePermissionMapper, roleMenuMapper, applicationServiceMapper);

    @Test
    @DisplayName("报告列出平台级权限与菜单,已归属本应用的不再出现")
    void reportsPlatformScopedGaps() {
        stubRealm();

        IamAppBackfillManager.BackfillAnalysis analysis = manager.analyze(REALM_ID, APP_ID);

        IamAppBackfillManager.BackfillCandidate permission = analysis.permissions().stream()
                .filter(candidate -> candidate.id().equals(PLATFORM_PERMISSION_ID))
                .findFirst()
                .orElseThrow();
        assertTrue(permission.platform());
        assertTrue(permission.reasons().contains("角色直授"));
        assertEquals(IamAppBackfillManager.STRATEGY_COPY, permission.strategy(),
                "域角色同时持有该权限时不能改归属,只能复制");
        assertTrue(analysis.permissions().stream()
                .noneMatch(candidate -> candidate.id().equals(APP_PERMISSION_ID)), "本应用自有权限不是缺口");

        IamAppBackfillManager.BackfillCandidate menu = analysis.menus().stream()
                .filter(candidate -> candidate.id().equals(PLATFORM_MENU_ID))
                .findFirst()
                .orElseThrow();
        assertTrue(menu.reasons().contains("角色授予"));
    }

    @Test
    @DisplayName("只有本应用角色引用的平台资源建议归属迁移")
    void recommendsMoveWhenOnlyAppRoleHoldsResource() {
        when(roleMapper.selectAllByRealmId(REALM_ID)).thenReturn(List.of(
                role(APP_ROLE_ID, APP_ID, "expense-auditor"),
                role(REALM_ROLE_ID, null, "staff")));
        when(rolePermissionMapper.selectByRoleIds(List.of(APP_ROLE_ID))).thenReturn(List.of(
                rolePermission(500L, APP_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(rolePermissionMapper.selectByRoleIds(List.of(REALM_ROLE_ID))).thenReturn(List.of());
        when(roleMenuMapper.selectByRoleIds(List.of(APP_ROLE_ID))).thenReturn(List.of(
                roleMenu(600L, APP_ROLE_ID, PLATFORM_MENU_ID)));
        when(roleMenuMapper.selectByRoleIds(List.of(REALM_ROLE_ID))).thenReturn(List.of());
        when(permissionMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(
                permission(PLATFORM_PERMISSION_ID, null, "expense:view")));
        when(menuMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(
                menu(PLATFORM_MENU_ID, null, PLATFORM_PERMISSION_ID)));
        when(rolePermissionMapper.selectByPermissionIds(any())).thenReturn(List.of());
        when(roleMenuMapper.selectByMenuIds(any())).thenReturn(List.of());
        when(applicationServiceMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(app(APP_ID, "expense")));

        IamAppBackfillManager.BackfillAnalysis analysis = manager.analyze(REALM_ID, APP_ID);

        assertEquals(IamAppBackfillManager.STRATEGY_MOVE, analysis.permissions().get(0).strategy());
        assertEquals(IamAppBackfillManager.STRATEGY_MOVE, analysis.menus().get(0).strategy());
    }

    @Test
    @DisplayName("域角色授予的资源单列,提示跨应用角色来源")
    void reportsRealmRoleGrants() {
        stubRealm();
        when(rolePermissionMapper.selectByRoleIds(List.of(APP_ROLE_ID))).thenReturn(List.of());
        when(rolePermissionMapper.selectByRoleIds(List.of(REALM_ROLE_ID))).thenReturn(List.of(
                rolePermission(501L, REALM_ROLE_ID, PLATFORM_PERMISSION_ID)));

        IamAppBackfillManager.BackfillAnalysis analysis = manager.analyze(REALM_ID, APP_ID);

        IamAppBackfillManager.BackfillCandidate permission = analysis.permissions().get(0);
        assertTrue(permission.reasons().contains("域角色授予"));
        assertEquals(List.of("staff"), permission.realmRoleCodes());
    }

    @Test
    @DisplayName("归属迁移只跟随选中的菜单及其祖先")
    void moveFollowsSelectedMenuAncestorsOnly() {
        Menu child = menu(302L, null, PLATFORM_PERMISSION_ID);
        child.setParentId(PLATFORM_MENU_ID);
        Menu unrelated = menu(303L, null, null);
        when(menuMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(
                menu(PLATFORM_MENU_ID, null, PLATFORM_PERMISSION_ID), child, unrelated));
        when(menuMapper.updateAppServiceIdByIds(eq(REALM_ID), eq(APP_ID), any())).thenReturn(2);

        IamAppBackfillManager.BackfillOutcome outcome = manager.moveToApp(REALM_ID, APP_ID,
                List.of(), List.of(302L));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(menuMapper).updateAppServiceIdByIds(eq(REALM_ID), eq(APP_ID), captor.capture());
        assertEquals(List.of(302L, PLATFORM_MENU_ID), captor.getValue());
        assertEquals(2, outcome.movedMenus());
    }

    @Test
    @DisplayName("已回填到本应用且相关角色都持有副本的资源不再列为缺口")
    void excludesAlreadyBackfilledResources() {
        stubRealm();
        when(rolePermissionMapper.selectByPermissionIds(List.of(PLATFORM_PERMISSION_ID))).thenReturn(List.of(
                rolePermission(500L, APP_ROLE_ID, PLATFORM_PERMISSION_ID),
                rolePermission(501L, REALM_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(permissionMapper.selectByCondition(eq(REALM_ID), any(Permission.class))).thenReturn(List.of(
                permission(APP_COPY_PERMISSION_ID, APP_ID, "expense:view")));
        when(rolePermissionMapper.selectByPermissionIds(List.of(APP_COPY_PERMISSION_ID))).thenReturn(List.of(
                rolePermission(700L, APP_ROLE_ID, APP_COPY_PERMISSION_ID),
                rolePermission(701L, REALM_ROLE_ID, APP_COPY_PERMISSION_ID)));

        IamAppBackfillManager.BackfillAnalysis analysis = manager.analyze(REALM_ID, APP_ID);

        assertTrue(analysis.permissions().stream()
                .noneMatch(candidate -> candidate.id().equals(PLATFORM_PERMISSION_ID)), "已回填的权限不应再列为缺口");
    }

    @Test
    @DisplayName("副本存在但相关角色未持有时仍是缺口")
    void keepsGapWhenCopyNotGranted() {
        stubRealm();
        when(rolePermissionMapper.selectByPermissionIds(List.of(PLATFORM_PERMISSION_ID))).thenReturn(List.of(
                rolePermission(500L, APP_ROLE_ID, PLATFORM_PERMISSION_ID),
                rolePermission(501L, REALM_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(permissionMapper.selectByCondition(eq(REALM_ID), any(Permission.class))).thenReturn(List.of(
                permission(APP_COPY_PERMISSION_ID, APP_ID, "expense:view")));
        when(rolePermissionMapper.selectByPermissionIds(List.of(APP_COPY_PERMISSION_ID))).thenReturn(List.of(
                rolePermission(701L, REALM_ROLE_ID, APP_COPY_PERMISSION_ID)));

        IamAppBackfillManager.BackfillAnalysis analysis = manager.analyze(REALM_ID, APP_ID);

        assertTrue(analysis.permissions().stream()
                .anyMatch(candidate -> candidate.id().equals(PLATFORM_PERMISSION_ID)), "未持有副本时缺口仍在");
    }

    @Test
    @DisplayName("归属迁移只更新仍处于平台命名空间的行")
    void moveOnlyTouchesPlatformRows() {
        when(permissionMapper.selectByIds(List.of(PLATFORM_PERMISSION_ID))).thenReturn(List.of(
                permission(PLATFORM_PERMISSION_ID, null, "expense:view")));
        when(permissionMapper.selectByScope(REALM_ID, APP_ID, "expense:view")).thenReturn(null);
        when(menuMapper.selectByRealmId(REALM_ID)).thenReturn(List.of());
        when(permissionMapper.updateAppServiceIdByIds(eq(REALM_ID), eq(APP_ID), any())).thenReturn(1);

        IamAppBackfillManager.BackfillOutcome outcome = manager.moveToApp(REALM_ID, APP_ID,
                List.of(PLATFORM_PERMISSION_ID), List.of());

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(permissionMapper).updateAppServiceIdByIds(eq(REALM_ID), eq(APP_ID), captor.capture());
        assertEquals(List.of(PLATFORM_PERMISSION_ID), captor.getValue());
        assertEquals(1, outcome.movedPermissions());
    }

    @Test
    @DisplayName("已归属其它应用的资源不能迁移,只能复制")
    void moveRejectsResourcesOwnedByOtherApp() {
        when(permissionMapper.selectByIds(List.of(PLATFORM_PERMISSION_ID))).thenReturn(List.of(
                permission(PLATFORM_PERMISSION_ID, OTHER_APP_ID, "expense:view")));
        when(menuMapper.selectByRealmId(REALM_ID)).thenReturn(List.of());

        IamAppBackfillManager.BackfillOutcome outcome = manager.moveToApp(REALM_ID, APP_ID,
                List.of(PLATFORM_PERMISSION_ID), List.of());

        assertEquals(0, outcome.movedPermissions());
        assertEquals(1, outcome.conflicts().size());
        verify(permissionMapper, never()).updateAppServiceIdByIds(any(), any(), any());
    }

    @Test
    @DisplayName("域角色持有的平台权限不能改归属,只能复制")
    void moveRejectsRealmHeldResources() {
        when(roleMapper.selectAllByRealmId(REALM_ID)).thenReturn(List.of(
                role(APP_ROLE_ID, APP_ID, "expense-auditor"),
                role(REALM_ROLE_ID, null, "staff")));
        when(rolePermissionMapper.selectByRoleIds(List.of(REALM_ROLE_ID))).thenReturn(List.of(
                rolePermission(501L, REALM_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(permissionMapper.selectByIds(List.of(PLATFORM_PERMISSION_ID))).thenReturn(List.of(
                permission(PLATFORM_PERMISSION_ID, null, "expense:view")));
        when(menuMapper.selectByRealmId(REALM_ID)).thenReturn(List.of());

        IamAppBackfillManager.BackfillOutcome outcome = manager.moveToApp(REALM_ID, APP_ID,
                List.of(PLATFORM_PERMISSION_ID), List.of());

        assertEquals(0, outcome.movedPermissions());
        assertTrue(outcome.conflicts().get(0).contains("域角色"));
        verify(permissionMapper, never()).updateAppServiceIdByIds(any(), any(), any());
    }

    @Test
    @DisplayName("复制到应用时建副本、改指向,并给域角色追加引用")
    void copyCreatesCopiesAndRelinksGrants() {
        stubRealm();
        when(permissionMapper.selectByScope(REALM_ID, APP_ID, "expense:view")).thenReturn(null);
        when(menuMapper.selectByAppServiceId(REALM_ID, APP_ID)).thenReturn(List.of());
        doAnswer(invocation -> {
            Permission inserted = invocation.getArgument(0);
            inserted.setId(900L);
            return 1;
        }).when(permissionMapper).insert(any(Permission.class));
        doAnswer(invocation -> {
            Menu inserted = invocation.getArgument(0);
            inserted.setId(910L);
            return 1;
        }).when(menuMapper).insert(any(Menu.class));
        when(rolePermissionMapper.selectByRoleId(REALM_ROLE_ID)).thenReturn(List.of(
                rolePermission(501L, REALM_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(roleMenuMapper.selectByRoleId(REALM_ROLE_ID)).thenReturn(List.of(
                roleMenu(601L, REALM_ROLE_ID, PLATFORM_MENU_ID)));

        IamAppBackfillManager.BackfillOutcome outcome = manager.copyToApp(REALM_ID, APP_ID,
                List.of(PLATFORM_PERMISSION_ID), List.of(PLATFORM_MENU_ID));

        assertEquals(1, outcome.copiedPermissions());
        assertEquals(1, outcome.copiedMenus());
        verify(rolePermissionMapper).updatePermissionIdById(500L, 900L);
        verify(rolePermissionMapper).insertBatch(REALM_ID, REALM_ROLE_ID, List.of(900L));
        verify(roleMenuMapper).updateMenuIdById(600L, 910L);
        verify(roleMenuMapper).insertBatch(REALM_ID, REALM_ROLE_ID, List.of(910L));
    }

    private void stubRealm() {
        when(roleMapper.selectAllByRealmId(REALM_ID)).thenReturn(List.of(
                role(APP_ROLE_ID, APP_ID, "expense-auditor"),
                role(REALM_ROLE_ID, null, "staff")));
        when(permissionMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(
                permission(PLATFORM_PERMISSION_ID, null, "expense:view"),
                permission(APP_PERMISSION_ID, APP_ID, "expense:audit")));
        when(menuMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(
                menu(PLATFORM_MENU_ID, null, PLATFORM_PERMISSION_ID)));
        when(rolePermissionMapper.selectByRoleIds(List.of(APP_ROLE_ID))).thenReturn(List.of(
                rolePermission(500L, APP_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(rolePermissionMapper.selectByRoleIds(List.of(REALM_ROLE_ID))).thenReturn(List.of(
                rolePermission(501L, REALM_ROLE_ID, PLATFORM_PERMISSION_ID)));
        when(roleMenuMapper.selectByRoleIds(List.of(APP_ROLE_ID))).thenReturn(List.of(
                roleMenu(600L, APP_ROLE_ID, PLATFORM_MENU_ID)));
        when(roleMenuMapper.selectByRoleIds(List.of(REALM_ROLE_ID))).thenReturn(List.of(
                roleMenu(601L, REALM_ROLE_ID, PLATFORM_MENU_ID)));
        when(rolePermissionMapper.selectByPermissionIds(List.of(PLATFORM_PERMISSION_ID))).thenReturn(List.of());
        when(roleMenuMapper.selectByMenuIds(List.of(PLATFORM_MENU_ID))).thenReturn(List.of(
                roleMenu(600L, APP_ROLE_ID, PLATFORM_MENU_ID),
                roleMenu(601L, REALM_ROLE_ID, PLATFORM_MENU_ID)));
        when(applicationServiceMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(
                app(APP_ID, "expense"), app(OTHER_APP_ID, "report")));
    }

    private static Role role(Long id, Long appServiceId, String roleCode) {
        Role role = new Role();
        role.setId(id);
        role.setRealmId(REALM_ID);
        role.setAppServiceId(appServiceId);
        role.setRoleCode(roleCode);
        return role;
    }

    private static Permission permission(Long id, Long appServiceId, String permissionCode) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setRealmId(REALM_ID);
        permission.setAppServiceId(appServiceId);
        permission.setPermissionCode(permissionCode);
        permission.setPermissionName(permissionCode);
        return permission;
    }

    private static Menu menu(Long id, Long appServiceId, Long permissionId) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setRealmId(REALM_ID);
        menu.setAppServiceId(appServiceId);
        menu.setMenuCode("expense.menu");
        menu.setMenuName("报销");
        menu.setPermissionId(permissionId);
        return menu;
    }

    private static RolePermission rolePermission(Long id, Long roleId, Long permissionId) {
        RolePermission grant = new RolePermission();
        grant.setId(id);
        grant.setRealmId(REALM_ID);
        grant.setRoleId(roleId);
        grant.setPermissionId(permissionId);
        return grant;
    }

    private static RoleMenu roleMenu(Long id, Long roleId, Long menuId) {
        RoleMenu grant = new RoleMenu();
        grant.setId(id);
        grant.setRealmId(REALM_ID);
        grant.setRoleId(roleId);
        grant.setMenuId(menuId);
        return grant;
    }

    private static ApplicationService app(Long id, String appCode) {
        ApplicationService app = new ApplicationService();
        app.setId(id);
        app.setRealmId(REALM_ID);
        app.setAppCode(appCode);
        app.setAppName(appCode);
        return app;
    }
}
