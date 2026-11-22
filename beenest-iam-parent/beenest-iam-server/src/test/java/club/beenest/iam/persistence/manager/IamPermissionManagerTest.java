package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.RoleMenu;
import club.beenest.iam.persistence.entity.RolePermission;
import club.beenest.iam.persistence.entity.UserPermission;
import club.beenest.iam.persistence.mapper.ApplicationServiceMapper;
import club.beenest.iam.persistence.mapper.MenuMapper;
import club.beenest.iam.persistence.mapper.PermissionMapper;
import club.beenest.iam.persistence.mapper.RoleMenuMapper;
import club.beenest.iam.persistence.mapper.RolePermissionMapper;
import club.beenest.iam.persistence.mapper.UserPermissionMapper;
import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 应用级权限计算测试。
 * <p>应用视图下平台权限与其他应用的权限都不得进入结果集;
 * 菜单派生只接受本应用启用菜单,用户 GRANT/REVOKE 仍在应用范围内结算。</p>
 */
class IamPermissionManagerTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 9L;
    private static final Long ROLE_ID = 10L;
    private static final Long APP_ID = 7L;
    private static final Long OTHER_APP_ID = 8L;

    private final IamEffectiveRoleResolver effectiveRoleResolver = mock(IamEffectiveRoleResolver.class);
    private final RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
    private final RolePermissionMapper rolePermissionMapper = mock(RolePermissionMapper.class);
    private final UserPermissionMapper userPermissionMapper = mock(UserPermissionMapper.class);
    private final MenuMapper menuMapper = mock(MenuMapper.class);
    private final PermissionMapper permissionMapper = mock(PermissionMapper.class);
    private final ApplicationServiceMapper applicationServiceMapper = mock(ApplicationServiceMapper.class);

    /** 代次固定为 0:缓存键稳定,测试只关心求值结果。 */
    private final IamPermissionEpoch permissionEpoch = mock(IamPermissionEpoch.class);
    /** 缓存不可用时退回实时求值,测试断言的是求值结果。 */
    private final RedisUtils redisUtils = mock(RedisUtils.class);

    private final IamPermissionManager manager = new IamPermissionManager(
            effectiveRoleResolver, roleMenuMapper, rolePermissionMapper, userPermissionMapper,
            menuMapper, permissionMapper, applicationServiceMapper, permissionEpoch, redisUtils);

    @Test
    @DisplayName("应用权限排除平台权限与其他应用权限")
    void filtersPermissionsOutsideApplication() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(
                rolePermission(100L), rolePermission(101L), rolePermission(102L)));
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        List<Permission> permissions = List.of(
                permission(100L, APP_ID, "oa:view"),
                permission(101L, null, "platform:admin"),
                permission(102L, OTHER_APP_ID, "crm:view"));
        stubPermissionLookup(permissions);

        Set<String> codes = manager.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID);

        assertEquals(Set.of("oa:view"), codes);
    }

    @Test
    @DisplayName("菜单派生按应用过滤,GRANT/REVOKE 在应用内结算")
    void appliesMenuDerivationAndUserOverridesWithinApplication() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(
                roleMenu(200L), roleMenu(201L)));
        when(menuMapper.selectByIds(any())).thenReturn(List.of(
                menu(200L, APP_ID, 100L, true),
                menu(201L, APP_ID, 104L, false)));
        when(applicationServiceMapper.selectByIds(any())).thenReturn(List.of(app(APP_ID, true)));
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userPermission(100L, "REVOKE"), userPermission(103L, "GRANT")));
        List<Permission> permissions = List.of(
                permission(100L, APP_ID, "oa:view"),
                permission(103L, APP_ID, "oa:export"),
                permission(104L, APP_ID, "oa:disabled"));
        stubPermissionLookup(permissions);

        Set<String> codes = manager.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID);

        assertEquals(Set.of("oa:export"), codes);
    }

    @Test
    @DisplayName("停用应用的菜单不再派生权限")
    void skipsMenuDerivationForDisabledApplication() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(roleMenu(200L)));
        when(menuMapper.selectByIds(any())).thenReturn(List.of(menu(200L, APP_ID, 100L, true)));
        when(applicationServiceMapper.selectByIds(any())).thenReturn(List.of(app(APP_ID, false)));
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        stubPermissionLookup(List.of(permission(100L, APP_ID, "oa:view")));

        assertEquals(Set.of(), manager.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID));
    }

    @Test
    @DisplayName("空权限角色不产生任何兜底权限")
    void yieldsNoPermissionsForRoleWithoutGrants() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of());

        // 角色存在但什么都没授予时结果必须为空:空集合不能被当成"无限制"放行
        assertEquals(Set.of(), manager.computePermissionCodes(REALM_ID, USER_ID));
        assertEquals(Set.of(), manager.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID));
    }

    @Test
    @DisplayName("诊断输出各权限来源,过期与跨域特批不参与结算")
    void inspectExposesSourcesAndHonorsOverrideLifecycle() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(
                rolePermission(100L), rolePermission(101L)));
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(roleMenu(200L)));
        when(menuMapper.selectByIds(any())).thenReturn(List.of(menu(200L, APP_ID, 102L, true)));
        when(applicationServiceMapper.selectByIds(any())).thenReturn(List.of(app(APP_ID, true)));
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userPermission(103L, "GRANT", null),
                userPermission(102L, "REVOKE", null),
                userPermission(100L, "REVOKE", LocalDateTime.now().minusDays(1)),
                userPermission(101L, "GRANT", LocalDateTime.now().minusDays(1)),
                userPermission(104L, "GRANT", null, 999L)));
        stubPermissionLookup(List.of(
                permission(100L, APP_ID, "oa:view"),
                permission(101L, APP_ID, "oa:platform"),
                permission(102L, APP_ID, "oa:menu"),
                permission(103L, APP_ID, "oa:export"),
                permission(104L, APP_ID, "oa:foreign")));

        IamPermissionManager.PermissionInspection inspection = manager.inspect(REALM_ID, USER_ID, null);

        assertEquals(Set.of("oa:view", "oa:platform"), codes(inspection.roleDirectPermissions()));
        assertEquals(Set.of("oa:menu"), codes(inspection.menuDerivedPermissions()));
        assertEquals(Set.of("oa:export"), codes(inspection.userGrantedPermissions()));
        assertEquals(Set.of("oa:menu"), codes(inspection.userRevokedPermissions()));
        assertEquals(Set.of("oa:view", "oa:platform", "oa:export"), codes(inspection.effectivePermissions()));
    }

    @Test
    @DisplayName("应用视图的诊断结果不含平台权限与其他应用权限")
    void inspectionFiltersSourcesByApplication() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(
                rolePermission(100L), rolePermission(101L), rolePermission(102L)));
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userPermission(101L, "GRANT", null),
                userPermission(102L, "REVOKE", null)));
        stubPermissionLookup(List.of(
                permission(100L, APP_ID, "oa:view"),
                permission(101L, null, "platform:admin"),
                permission(102L, OTHER_APP_ID, "crm:view")));

        IamPermissionManager.PermissionInspection inspection = manager.inspect(REALM_ID, USER_ID, APP_ID);

        assertEquals(Set.of("oa:view"), codes(inspection.roleDirectPermissions()));
        assertEquals(Set.of(), codes(inspection.userGrantedPermissions()));
        assertEquals(Set.of(), codes(inspection.userRevokedPermissions()));
        assertEquals(Set.of("oa:view"), codes(inspection.effectivePermissions()));
    }

    @Test
    @DisplayName("过期撤销不扣除角色权限")
    void expiredRevokeDoesNotRemoveRolePermission() {
        stubEffectiveRole();
        when(rolePermissionMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of(rolePermission(100L)));
        when(roleMenuMapper.selectByRoleIds(List.of(ROLE_ID))).thenReturn(List.of());
        when(userPermissionMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userPermission(100L, "REVOKE", LocalDateTime.now().minusMinutes(1))));
        stubPermissionLookup(List.of(permission(100L, APP_ID, "oa:view")));

        IamPermissionManager.PermissionInspection inspection = manager.inspect(REALM_ID, USER_ID, null);

        assertEquals(Set.of(), codes(inspection.userRevokedPermissions()));
        assertEquals(Set.of("oa:view"), codes(inspection.effectivePermissions()));
    }

    private static Set<String> codes(List<Permission> permissions) {
        return permissions.stream().map(Permission::getPermissionCode).collect(java.util.stream.Collectors.toSet());
    }

    private void stubEffectiveRole() {
        when(effectiveRoleResolver.resolveRoleIds(REALM_ID, USER_ID)).thenReturn(List.of(ROLE_ID));
    }

    private void stubPermissionLookup(List<Permission> permissions) {
        when(permissionMapper.selectByIds(any())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return permissions.stream().filter(permission -> ids.contains(permission.getId())).toList();
        });
    }

    private static RolePermission rolePermission(Long permissionId) {
        RolePermission relation = new RolePermission();
        relation.setRealmId(REALM_ID);
        relation.setRoleId(ROLE_ID);
        relation.setPermissionId(permissionId);
        return relation;
    }

    private static RoleMenu roleMenu(Long menuId) {
        RoleMenu relation = new RoleMenu();
        relation.setRealmId(REALM_ID);
        relation.setRoleId(ROLE_ID);
        relation.setMenuId(menuId);
        return relation;
    }

    private static Menu menu(Long id, Long appServiceId, Long permissionId, boolean enabled) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setRealmId(REALM_ID);
        menu.setAppServiceId(appServiceId);
        menu.setPermissionId(permissionId);
        menu.setEnabled(enabled);
        return menu;
    }

    private static Permission permission(Long id, Long appServiceId, String code) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setRealmId(REALM_ID);
        permission.setAppServiceId(appServiceId);
        permission.setPermissionCode(code);
        return permission;
    }

    private static UserPermission userPermission(Long permissionId, String grantType) {
        return userPermission(permissionId, grantType, null, REALM_ID);
    }

    private static UserPermission userPermission(Long permissionId, String grantType, LocalDateTime expiredTime) {
        return userPermission(permissionId, grantType, expiredTime, REALM_ID);
    }

    private static UserPermission userPermission(Long permissionId, String grantType,
                                                 LocalDateTime expiredTime, Long realmId) {
        UserPermission grant = new UserPermission();
        grant.setRealmId(realmId);
        grant.setUserId(USER_ID);
        grant.setPermissionId(permissionId);
        grant.setGrantType(grantType);
        grant.setExpiredTime(expiredTime);
        return grant;
    }

    private static ApplicationService app(Long id, boolean enabled) {
        ApplicationService app = new ApplicationService();
        app.setId(id);
        app.setRealmId(REALM_ID);
        app.setEnabled(enabled);
        return app;
    }
}
