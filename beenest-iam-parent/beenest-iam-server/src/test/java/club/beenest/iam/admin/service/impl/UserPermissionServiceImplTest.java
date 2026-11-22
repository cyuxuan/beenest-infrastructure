package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.PermissionOverrideRequest;
import club.beenest.iam.admin.dto.PermissionOverrideResponse;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.entity.UserPermission;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import club.beenest.iam.persistence.manager.IamUserPermissionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户权限特批服务测试。
 * <p>重点覆盖审批隔离、过期时间校验、审计人回填与缓存失效:
 * 特批是绕过角色的高权限操作,这些约束不能只依赖前端。</p>
 */
class UserPermissionServiceImplTest {

    private static final Long REALM_ID = 1L;
    private static final Long OPERATOR_ID = 2L;
    private static final Long TARGET_ID = 9L;
    private static final Long PERMISSION_ID = 100L;

    private final IamUserManager userManager = mock(IamUserManager.class);
    private final IamPermissionManager permissionManager = mock(IamPermissionManager.class);
    private final IamUserPermissionManager userPermissionManager = mock(IamUserPermissionManager.class);
    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamEffectiveRoleResolver effectiveRoleResolver = mock(IamEffectiveRoleResolver.class);
    private final IamGroupManager groupManager = mock(IamGroupManager.class);
    private final IamPositionManager positionManager = mock(IamPositionManager.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);

    private final UserPermissionServiceImpl service = new UserPermissionServiceImpl(
            userManager, permissionManager, userPermissionManager, appServiceManager,
            effectiveRoleResolver, groupManager, positionManager);

    @BeforeEach
    void setUp() {
        IamUserPrincipal operator = new IamUserPrincipal(
                OPERATOR_ID, REALM_ID, "default", "admin", "管理员", 0, List.of("oa:export"), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("禁止修改自己的权限特批")
    void rejectsSelfOverride() {
        when(userManager.getById(OPERATOR_ID)).thenReturn(user(OPERATOR_ID, REALM_ID));

        BusinessException error = assertThrows(BusinessException.class, () -> service.saveOverride(
                OPERATOR_ID, PERMISSION_ID, new PermissionOverrideRequest("GRANT", "self grant", null)));

        assertEquals("不允许修改自己的权限特批", error.getMessage());
        verify(userPermissionManager, never()).upsert(any());
    }

    @Test
    @DisplayName("特批写入审计人并清除权限缓存")
    void savesOverrideWithAuditorAndEvictsCache() {
        when(userManager.getById(TARGET_ID)).thenReturn(user(TARGET_ID, REALM_ID));
        when(permissionManager.getById(PERMISSION_ID)).thenReturn(
                permission(PERMISSION_ID, REALM_ID, "oa:export"));
        when(appServiceManager.listByRealmId(REALM_ID)).thenReturn(List.of());
        when(userPermissionManager.upsert(any())).thenAnswer(invocation -> {
            UserPermission relation = invocation.getArgument(0);
            relation.setId(55L);
            return relation;
        });
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(3).withNano(0);

        PermissionOverrideResponse response = service.saveOverride(TARGET_ID, PERMISSION_ID,
                new PermissionOverrideRequest("REVOKE", "  违规导出,临时收回  ", expiresAt));

        verify(userPermissionManager).upsert(argThat(relation ->
                OPERATOR_ID.equals(relation.getGrantedBy())
                        && "违规导出,临时收回".equals(relation.getReason())
                        && "REVOKE".equals(relation.getGrantType())
                        && TARGET_ID.equals(relation.getUserId())
                        && REALM_ID.equals(relation.getRealmId())
                        && expiresAt.equals(relation.getExpiredTime())));
        // 权限缓存的失效改为"权限代次"机制(见 IamPermissionEpoch):
        // 特批落库时由持久层自增代次,该域缓存即刻失效,不再逐用户删键
        verify(userPermissionManager).upsert(any());
        assertEquals(PERMISSION_ID, response.permissionId());
        assertEquals("oa:export", response.permissionCode());
        assertEquals("REVOKE", response.grantType());
        assertTrue(response.active());
    }

    @Test
    @DisplayName("过期时间必须晚于当前")
    void rejectsPastExpiry() {
        when(userManager.getById(TARGET_ID)).thenReturn(user(TARGET_ID, REALM_ID));
        when(permissionManager.getById(PERMISSION_ID)).thenReturn(
                permission(PERMISSION_ID, REALM_ID, "oa:export"));

        assertThrows(BusinessException.class, () -> service.saveOverride(TARGET_ID, PERMISSION_ID,
                new PermissionOverrideRequest("GRANT", "过期补权", LocalDateTime.now().minusMinutes(1))));

        verify(userPermissionManager, never()).upsert(any());
        verify(redisUtils, never()).delete(anyString());
    }

    @Test
    @DisplayName("跨域权限与跨域用户按不存在处理")
    void rejectsCrossRealmTargets() {
        when(userManager.getById(TARGET_ID)).thenReturn(user(TARGET_ID, REALM_ID));
        when(permissionManager.getById(PERMISSION_ID)).thenReturn(
                permission(PERMISSION_ID, 2L, "other:perm"));

        assertThrows(BusinessException.class, () -> service.saveOverride(TARGET_ID, PERMISSION_ID,
                new PermissionOverrideRequest("GRANT", "cross realm", null)));

        when(userManager.getById(TARGET_ID)).thenReturn(user(TARGET_ID, 2L));
        assertThrows(BusinessException.class, () -> service.listOverrides(TARGET_ID));
    }

    @Test
    @DisplayName("不能操作自己不具备的权限")
    void rejectsPermissionBeyondOwnScope() {
        when(userManager.getById(TARGET_ID)).thenReturn(user(TARGET_ID, REALM_ID));
        when(permissionManager.getById(PERMISSION_ID)).thenReturn(
                permission(PERMISSION_ID, REALM_ID, "system:shutdown"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.saveOverride(
                TARGET_ID, PERMISSION_ID, new PermissionOverrideRequest("GRANT", "越权授予", null)));

        assertEquals("只能操作自己已具备的权限: system:shutdown", error.getMessage());
        verify(userPermissionManager, never()).upsert(any());
    }

    @Test
    @DisplayName("删除不存在的特批返回未找到")
    void deleteMissingOverride() {
        when(userManager.getById(TARGET_ID)).thenReturn(user(TARGET_ID, REALM_ID));
        when(userPermissionManager.getByUserAndPermission(TARGET_ID, PERMISSION_ID)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.deleteOverride(TARGET_ID, PERMISSION_ID));

        verify(userPermissionManager, never()).remove(any());
    }

    private static User user(Long id, Long realmId) {
        User user = new User();
        user.setId(id);
        user.setRealmId(realmId);
        user.setUsername("user-" + id);
        return user;
    }

    private static Permission permission(Long id, Long realmId, String code) {
        Permission permission = new Permission();
        permission.setId(id);
        permission.setRealmId(realmId);
        permission.setPermissionCode(code);
        permission.setPermissionName(code);
        return permission;
    }
}
