package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.RoleUpdateRequest;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamClientManager;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色归属变更的校验测试。
 * <p>归属决定角色的授权面,而有效角色解析不按容器过滤:改归属会让角色的权限范围
 * 对所有持有者同时改变。因此只在角色尚未承载任何授权关系时放行。</p>
 */
class RoleServiceImplContainerTest {

    private static final Long REALM_ID = 1L;
    private static final Long ROLE_ID = 50L;
    private static final Long APP_ID = 7L;

    private final IamRoleManager roleManager = mock(IamRoleManager.class);
    private final IamPermissionManager permissionManager = mock(IamPermissionManager.class);
    private final IamMenuManager menuManager = mock(IamMenuManager.class);
    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamClientManager clientManager = mock(IamClientManager.class);
    private final RoleDtoConverter dtoConverter = new RoleDtoConverter();

    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleServiceImpl(roleManager, permissionManager, menuManager,
                appServiceManager, clientManager, dtoConverter);
        IamUserPrincipal operator = new IamUserPrincipal(
                2L, REALM_ID, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未承载授权关系的角色可以把归属从域级改为应用级")
    void cleanRoleCanMoveToApp() {
        Role role = role("REALM", null, null);
        when(roleManager.getById(ROLE_ID)).thenReturn(role);
        when(roleManager.countUsage(ROLE_ID)).thenReturn(0L);
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID));
        when(roleManager.getByScope(REALM_ID, "APP", null, APP_ID, "iam-admin")).thenReturn(null);

        assertDoesNotThrow(() -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "APP", APP_ID, null)));

        verify(roleManager).changeContainer(role, "APP", null, APP_ID);
    }

    @Test
    @DisplayName("已承载授权关系的角色拒绝变更归属")
    void usedRoleCannotMove() {
        when(roleManager.getById(ROLE_ID)).thenReturn(role("REALM", null, null));
        when(roleManager.countUsage(ROLE_ID)).thenReturn(4L);
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "APP", APP_ID, null)));

        assertTrue(error.getMessage().contains("4 条授权关系"));
        verify(roleManager, never()).changeContainer(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("系统内置角色拒绝变更归属")
    void systemRoleCannotMove() {
        Role role = role("REALM", null, null);
        role.setIsSystem(true);
        when(roleManager.getById(ROLE_ID)).thenReturn(role);
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID));

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "APP", APP_ID, null)));

        assertTrue(error.getMessage().contains("系统内置"));
        verify(roleManager, never()).changeContainer(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("目标作用域下角色编码冲突时拒绝变更")
    void moveRejectedOnCodeConflict() {
        when(roleManager.getById(ROLE_ID)).thenReturn(role("REALM", null, null));
        when(roleManager.countUsage(ROLE_ID)).thenReturn(0L);
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID));
        Role conflict = role("APP", null, APP_ID);
        conflict.setId(99L);
        when(roleManager.getByScope(REALM_ID, "APP", null, APP_ID, "iam-admin")).thenReturn(conflict);

        BusinessException error = assertThrows(BusinessException.class, () -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "APP", APP_ID, null)));

        assertTrue(error.getMessage().contains("角色编码已存在"));
        verify(roleManager, never()).changeContainer(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("只改名称时不做归属校验,不触发使用条数检查")
    void renameOnlySkipsContainerCheck() {
        Role role = role("REALM", null, null);
        when(roleManager.getById(ROLE_ID)).thenReturn(role);

        assertDoesNotThrow(() -> service.update(ROLE_ID,
                new RoleUpdateRequest("新名称", null, null, null, null)));

        verify(roleManager, never()).countUsage(anyLong());
        verify(roleManager, never()).changeContainer(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("归属未发生变化时不触发变更")
    void unchangedContainerIsNoop() {
        Role role = role("APP", null, APP_ID);
        when(roleManager.getById(ROLE_ID)).thenReturn(role);
        when(appServiceManager.getById(APP_ID)).thenReturn(app(APP_ID));

        assertDoesNotThrow(() -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "APP", APP_ID, null)));

        verify(roleManager, never()).changeContainer(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("应用角色缺少所属应用时拒绝")
    void appRoleRequiresApp() {
        when(roleManager.getById(ROLE_ID)).thenReturn(role("REALM", null, null));

        assertThrows(BusinessException.class, () -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "APP", null, null)));
    }

    @Test
    @DisplayName("客户端角色按绑定关系推导所属应用")
    void clientRoleDerivesAppFromBinding() {
        Role role = role("REALM", null, null);
        when(roleManager.getById(ROLE_ID)).thenReturn(role);
        when(roleManager.countUsage(ROLE_ID)).thenReturn(0L);
        when(clientManager.getById(21L)).thenReturn(client(21L));
        when(appServiceManager.getBySsoClientPk(REALM_ID, 21L)).thenReturn(app(APP_ID));
        when(roleManager.getByScope(REALM_ID, "CLIENT", 21L, APP_ID, "iam-admin")).thenReturn(null);

        assertDoesNotThrow(() -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "CLIENT", null, 21L)));

        verify(roleManager).changeContainer(role, "CLIENT", 21L, APP_ID);
    }

    @Test
    @DisplayName("跨域客户端按参数非法处理")
    void clientRoleRejectsForeignClient() {
        when(roleManager.getById(ROLE_ID)).thenReturn(role("REALM", null, null));
        Client foreign = client(21L);
        foreign.setRealmId(9L);
        when(clientManager.getById(21L)).thenReturn(foreign);

        assertThrows(BusinessException.class, () -> service.update(ROLE_ID,
                new RoleUpdateRequest(null, null, "CLIENT", null, 21L)));
        verify(roleManager, never()).changeContainer(any(), anyString(), any(), any());
    }

    /**
     * 构造角色。
     *
     * @param containerType 容器类型
     * @param clientId      所属客户端,可为 null
     * @param appServiceId  所属应用,可为 null
     * @return 角色实体
     */
    private static Role role(String containerType, Long clientId, Long appServiceId) {
        Role role = new Role();
        role.setId(ROLE_ID);
        role.setRealmId(REALM_ID);
        role.setRoleCode("iam-admin");
        role.setRoleName("身份管理员");
        role.setContainerType(containerType);
        role.setClientId(clientId);
        role.setAppServiceId(appServiceId);
        role.setIsSystem(false);
        role.setIsActive(true);
        return role;
    }

    /**
     * 构造应用服务。
     *
     * @param id 应用主键
     * @return 应用服务实体
     */
    private static ApplicationService app(Long id) {
        ApplicationService app = new ApplicationService();
        app.setId(id);
        app.setRealmId(REALM_ID);
        return app;
    }

    /**
     * 构造客户端。
     *
     * @param id 客户端主键
     * @return 客户端实体
     */
    private static Client client(Long id) {
        Client client = new Client();
        client.setId(id);
        client.setRealmId(REALM_ID);
        return client;
    }
}
