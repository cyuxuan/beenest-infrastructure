package club.beenest.payment.payscore;

import club.beenest.payment.payscore.domain.enums.ServiceOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务订单状态机转换测试
 * 确保所有状态转换规则正确
 *
 * @author System
 * @since 2026-06-15
 */
class ServiceOrderStatusTest {

    @Test
    @DisplayName("PENDING_AUTH 可以转换为 AUTHORIZED")
    void pendingAuthToAuthorized() {
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.AUTHORIZED));
    }

    @Test
    @DisplayName("PENDING_AUTH 可以转换为 EXPIRED")
    void pendingAuthToExpired() {
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.EXPIRED));
    }

    @Test
    @DisplayName("PENDING_AUTH 可以转换为 CANCELLED")
    void pendingAuthToCancelled() {
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("PENDING_AUTH 可以转换为 FAILED")
    void pendingAuthToFailed() {
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.FAILED));
    }

    @Test
    @DisplayName("PENDING_AUTH 不能直接转换为 COMPLETED")
    void pendingAuthCannotToCompleted() {
        assertFalse(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("AUTHORIZED 可以转换为 SERVICE_ACTIVE")
    void authorizedToServiceActive() {
        assertTrue(ServiceOrderStatus.AUTHORIZED.canTransitionTo(ServiceOrderStatus.SERVICE_ACTIVE));
    }

    @Test
    @DisplayName("AUTHORIZED 可以转换为 CANCELLED")
    void authorizedToCancelled() {
        assertTrue(ServiceOrderStatus.AUTHORIZED.canTransitionTo(ServiceOrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("AUTHORIZED 不能直接转换为 COMPLETED")
    void authorizedCannotToCompleted() {
        assertFalse(ServiceOrderStatus.AUTHORIZED.canTransitionTo(ServiceOrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("SERVICE_ACTIVE 可以转换为 COMPLETING")
    void serviceActiveToCompleting() {
        assertTrue(ServiceOrderStatus.SERVICE_ACTIVE.canTransitionTo(ServiceOrderStatus.COMPLETING));
    }

    @Test
    @DisplayName("COMPLETING 可以转换为 COMPLETED")
    void completingToCompleted() {
        assertTrue(ServiceOrderStatus.COMPLETING.canTransitionTo(ServiceOrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("COMPLETING 可以转换为 FAILED")
    void completingToFailed() {
        assertTrue(ServiceOrderStatus.COMPLETING.canTransitionTo(ServiceOrderStatus.FAILED));
    }

    @Test
    @DisplayName("终态不可流转")
    void terminalStatesCannotTransition() {
        assertFalse(ServiceOrderStatus.COMPLETED.canTransitionTo(ServiceOrderStatus.PENDING_AUTH));
        assertFalse(ServiceOrderStatus.CANCELLED.canTransitionTo(ServiceOrderStatus.AUTHORIZED));
        assertFalse(ServiceOrderStatus.EXPIRED.canTransitionTo(ServiceOrderStatus.AUTHORIZED));
        assertFalse(ServiceOrderStatus.FAILED.canTransitionTo(ServiceOrderStatus.PENDING_AUTH));
    }

    @Test
    @DisplayName("相同状态转换视为允许")
    void sameStateTransitionAllowed() {
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.PENDING_AUTH));
        assertTrue(ServiceOrderStatus.COMPLETED.canTransitionTo(ServiceOrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("isTerminal 终态判断正确")
    void isTerminal() {
        assertTrue(ServiceOrderStatus.COMPLETED.isTerminal());
        assertTrue(ServiceOrderStatus.CANCELLED.isTerminal());
        assertTrue(ServiceOrderStatus.EXPIRED.isTerminal());
        assertTrue(ServiceOrderStatus.FAILED.isTerminal());
        assertFalse(ServiceOrderStatus.PENDING_AUTH.isTerminal());
        assertFalse(ServiceOrderStatus.AUTHORIZED.isTerminal());
        assertFalse(ServiceOrderStatus.SERVICE_ACTIVE.isTerminal());
        assertFalse(ServiceOrderStatus.COMPLETING.isTerminal());
    }

    @Test
    @DisplayName("getByCode 正确映射")
    void getByCode() {
        assertEquals(ServiceOrderStatus.PENDING_AUTH, ServiceOrderStatus.getByCode("PENDING_AUTH"));
        assertEquals(ServiceOrderStatus.AUTHORIZED, ServiceOrderStatus.getByCode("AUTHORIZED"));
        assertNull(ServiceOrderStatus.getByCode("INVALID"));
        assertNull(ServiceOrderStatus.getByCode(null));
    }

    @Test
    @DisplayName("完整的正常生命周期")
    void happyPathLifecycle() {
        // PENDING_AUTH → AUTHORIZED → SERVICE_ACTIVE → COMPLETING → COMPLETED
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.AUTHORIZED));
        assertTrue(ServiceOrderStatus.AUTHORIZED.canTransitionTo(ServiceOrderStatus.SERVICE_ACTIVE));
        assertTrue(ServiceOrderStatus.SERVICE_ACTIVE.canTransitionTo(ServiceOrderStatus.COMPLETING));
        assertTrue(ServiceOrderStatus.COMPLETING.canTransitionTo(ServiceOrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("快速取消路径")
    void quickCancelPath() {
        // PENDING_AUTH → CANCELLED
        assertTrue(ServiceOrderStatus.PENDING_AUTH.canTransitionTo(ServiceOrderStatus.CANCELLED));
        // AUTHORIZED → CANCELLED
        assertTrue(ServiceOrderStatus.AUTHORIZED.canTransitionTo(ServiceOrderStatus.CANCELLED));
    }
}
