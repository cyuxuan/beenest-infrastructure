package org.apereo.cas.beenest.client.accesscontrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * CasAccessControlManager 单元测试。
 * <p>
 * 验证从 CAS 属性中提取 memberOf 角色后的本地用户同步逻辑。
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
            Map.of("memberOf", List.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"), "nickname", "张三")
        );

        assertTrue(result.granted());
        assertEquals("local-1", result.userId());
        verify(accessControlService).createLocalUser(
            eq("user1"),
            argThat(roles -> roles.contains("ROLE_DRONE_SYSTEM") && roles.contains("ROLE_PAYMENT")),
            anyMap()
        );
    }

    @Test
    void onAuthentication_hasAccessNoLocalUser_createFailed_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);
        when(accessControlService.createLocalUser(eq("user1"), anySet(), anyMap()))
            .thenReturn(null);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("memberOf", List.of("ROLE_DRONE_SYSTEM"))
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
            Map.of("memberOf", List.of("ROLE_DRONE_SYSTEM"))
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
            Map.of("memberOf", List.of("ROLE_PAYMENT"))
        );

        assertFalse(result.granted());
        assertEquals("访问权限已撤销", result.reason());
        verify(accessControlService).disableLocalUser(eq("user1"), anySet());
    }

    @Test
    void onAuthentication_noAccessHasLocalUser_autoDisableOff_shouldDenyWithoutDisabling() {
        properties.setAutoDisableOnRevoke(false);
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("memberOf", List.of("ROLE_PAYMENT"))
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
            Map.of("memberOf", List.of("ROLE_PAYMENT"))
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
            Map.of("memberOf", List.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"), "nickname", "张三")
        );

        assertTrue(result.granted());
        assertEquals("user1", result.userId());
        verify(accessControlService).updateLocalUser(
            eq("user1"),
            argThat(roles -> roles.contains("ROLE_DRONE_SYSTEM") && roles.contains("ROLE_PAYMENT")),
            anyMap()
        );
    }

    // --- memberOf 属性类型兼容性 ---

    @Test
    void onAuthentication_memberOfAsString_shouldParse() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("memberOf", "ROLE_DRONE_SYSTEM")
        );

        assertTrue(result.granted());
    }

    @Test
    void onAuthentication_noMemberOfAttribute_noLocalUser_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("nickname", "张三")
        );

        assertFalse(result.granted());
        assertEquals("无访问权限", result.reason());
    }

    // --- 防御性：memberOf 缺失 + 本地用户活跃 → 信任本地状态 ---

    @Test
    void onAuthentication_memberOfMissing_localUserActive_shouldTrustLocal() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        // 模拟 CAS refresh 端点未返回 memberOf 属性的场景
        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("nickname", "张三", "userId", "user1")
        );

        assertTrue(result.granted());
        assertEquals("user1", result.userId());
        verify(accessControlService).updateLocalUser(eq("user1"), anySet(), anyMap());
        verify(accessControlService, never()).disableLocalUser(any(), anySet());
    }

    @Test
    void onAuthentication_memberOfMissing_localUserNotActive_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);

        // memberOf 缺失 + 本地无用户 → 无法创建，拒绝
        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("nickname", "张三")
        );

        assertFalse(result.granted());
    }

    // --- SPI 异常处理 ---

    @Test
    void onAuthentication_spiThrowsException_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);
        doThrow(new RuntimeException("数据库异常"))
            .when(accessControlService).disableLocalUser(any(), anySet());

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Map.of("memberOf", List.of("ROLE_PAYMENT"))
        );

        assertFalse(result.granted());
        // 异常后降级为拒绝，不阻断认证流程
    }
}
