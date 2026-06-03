package org.apereo.cas.beenest.client.accesscontrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * CasAccessControlManager 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CasAccessControlManagerTest {

    @Mock
    private CasUserAccessControlService accessControlService;

    private CasAccessControlProperties properties;

    private CasAccessControlManager manager;

    @BeforeEach
    void setUp() {
        properties = new CasAccessControlProperties();
        properties.setEnabled(true);
        properties.setAutoCreateOnGrant(true);
        properties.setAutoDisableOnRevoke(true);
        when(accessControlService.getRequiredRole()).thenReturn("ROLE_DRONE_SYSTEM");
        manager = new CasAccessControlManager(accessControlService, properties);
    }

    // --- 场景 1: 有权限 + 无本地用户 → 自动创建 ---

    @Test
    void onAuthentication_hasAccessNoLocalUser_shouldCreateLocalUser() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);
        when(accessControlService.createLocalUser(eq("user1"), anySet(), anyMap()))
            .thenReturn("local-1");

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"),
            Map.of("nickname", "张三")
        );

        assertTrue(result.granted());
        assertEquals("local-1", result.userId());
        verify(accessControlService).createLocalUser(
            eq("user1"),
            eq(Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT")),
            eq(Map.of("nickname", "张三"))
        );
    }

    @Test
    void onAuthentication_hasAccessNoLocalUser_createFailed_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);
        when(accessControlService.createLocalUser(eq("user1"), anySet(), anyMap()))
            .thenReturn(null);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("本地用户创建失败", result.reason());
    }

    @Test
    void onAuthentication_hasAccessNoLocalUser_autoCreateDisabled_shouldDeny() {
        properties.setAutoCreateOnGrant(false);
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("本地用户不存在", result.reason());
        verify(accessControlService, never()).createLocalUser(any(), anySet(), anyMap());
    }

    // --- 场景 2: 无权限 + 有本地用户 → 禁用 ---

    @Test
    void onAuthentication_noAccessHasLocalUser_shouldDisable() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("访问权限已撤销", result.reason());
        verify(accessControlService).disableLocalUser("user1", Set.of("ROLE_PAYMENT"));
    }

    @Test
    void onAuthentication_noAccessHasLocalUser_autoDisableOff_shouldDenyWithoutDisabling() {
        properties.setAutoDisableOnRevoke(false);
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("无访问权限", result.reason());
        verify(accessControlService, never()).disableLocalUser(any(), anySet());
    }

    // --- 场景 3: 无权限 + 无本地用户 → 拒绝（兜底） ---

    @Test
    void onAuthentication_noAccessNoLocalUser_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("无访问权限", result.reason());
    }

    // --- 场景 4: 有权限 + 有本地用户 → 更新 ---

    @Test
    void onAuthentication_hasAccessHasLocalUser_shouldUpdate() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"),
            Map.of("nickname", "张三")
        );

        assertTrue(result.granted());
        assertEquals("user1", result.userId());
        verify(accessControlService).updateLocalUser(
            eq("user1"),
            eq(Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT")),
            eq(Map.of("nickname", "张三"))
        );
    }

    // --- 配置属性优先级 ---

    @Test
    void onAuthentication_configuredRoleOverridesSpi_shouldUseConfiguredRole() {
        properties.setRequiredRole("ROLE_CUSTOM");
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM"),
            Map.of()
        );

        assertFalse(result.granted());
        verify(accessControlService).disableLocalUser("user1", Set.of("ROLE_DRONE_SYSTEM"));
    }

    // --- SPI 异常处理 ---

    @Test
    void onAuthentication_spiThrowsException_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);
        doThrow(new RuntimeException("数据库异常"))
            .when(accessControlService).disableLocalUser(any(), anySet());

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        // 异常后降级为拒绝，不阻断认证流程
    }
}