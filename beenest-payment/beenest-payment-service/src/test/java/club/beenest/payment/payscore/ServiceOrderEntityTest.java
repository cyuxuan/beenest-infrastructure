package club.beenest.payment.payscore;

import club.beenest.payment.payscore.domain.entity.ServiceOrder;
import club.beenest.payment.payscore.domain.enums.ServiceOrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 服务订单实体领域方法测试
 * 验证状态转换方法和业务规则
 *
 * @author System
 * @since 2026-06-15
 */
class ServiceOrderEntityTest {

    private ServiceOrder order;

    @BeforeEach
    void setUp() {
        order = new ServiceOrder();
        order.setOrderNo("SO202606151234567890");
        order.setCustomerNo("U123456");
        order.setPlatform("WECHAT_PAYSCORE");
        order.setDepositAmount(100000L); // 1000元
        order.setStatusEnum(ServiceOrderStatus.PENDING_AUTH);
    }

    @Test
    @DisplayName("markAsAuthorized 正确转换状态")
    void markAsAuthorized() {
        order.markAsAuthorized(100000L, "WX_ORDER_123");
        assertEquals(ServiceOrderStatus.AUTHORIZED.getCode(), order.getStatus());
        assertEquals(100000L, order.getFrozenAmount());
        assertEquals("WX_ORDER_123", order.getThirdPartyOrderNo());
        assertNotNull(order.getAuthTime());
    }

    @Test
    @DisplayName("markAsAuthorized 在非PENDING_AUTH状态抛异常")
    void markAsAuthorizedInvalidState() {
        // 从COMPLETING状态调用应抛异常（不允许COMPLETING→AUTHORIZED）
        order.setStatusEnum(ServiceOrderStatus.COMPLETING);
        assertThrows(IllegalStateException.class, () -> order.markAsAuthorized(100000L, "WX_123"));
    }

    @Test
    @DisplayName("markAsCompleting 正确转换状态并校验金额")
    void markAsCompleting() {
        order.setStatusEnum(ServiceOrderStatus.SERVICE_ACTIVE);
        order.setFrozenAmount(100000L);
        order.markAsCompleting(50000L); // 扣款500元
        assertEquals(ServiceOrderStatus.COMPLETING.getCode(), order.getStatus());
        assertEquals(50000L, order.getActualAmount());
    }

    @Test
    @DisplayName("markAsCompleting 实际扣款超过冻结金额抛异常")
    void markAsCompletingAmountExceedsFrozen() {
        order.setStatusEnum(ServiceOrderStatus.SERVICE_ACTIVE);
        order.setFrozenAmount(100000L);
        assertThrows(IllegalArgumentException.class, () -> order.markAsCompleting(200000L));
    }

    @Test
    @DisplayName("markAsCompleting actualAmount=0 表示全额解冻")
    void markAsCompletingZeroAmount() {
        order.setStatusEnum(ServiceOrderStatus.SERVICE_ACTIVE);
        order.setFrozenAmount(100000L);
        order.markAsCompleting(0L);
        assertEquals(0L, order.getActualAmount());
    }

    @Test
    @DisplayName("markAsCompleted 正确转换状态")
    void markAsCompleted() {
        order.setStatusEnum(ServiceOrderStatus.COMPLETING);
        order.markAsCompleted("{\"result\":\"success\"}");
        assertEquals(ServiceOrderStatus.COMPLETED.getCode(), order.getStatus());
        assertNotNull(order.getCompleteTime());
    }

    @Test
    @DisplayName("markAsCancelled 正确转换状态")
    void markAsCancelled() {
        // 从PENDING_AUTH取消
        order.markAsCancelled();
        assertEquals(ServiceOrderStatus.CANCELLED.getCode(), order.getStatus());
    }

    @Test
    @DisplayName("markAsExpired 正确转换状态")
    void markAsExpired() {
        order.markAsExpired();
        assertEquals(ServiceOrderStatus.EXPIRED.getCode(), order.getStatus());
    }

    @Test
    @DisplayName("markAsFailed 正确转换状态")
    void markAsFailed() {
        order.markAsFailed();
        assertEquals(ServiceOrderStatus.FAILED.getCode(), order.getStatus());
    }

    @Test
    @DisplayName("getDepositAmountInYuan 正确转换分到元")
    void getDepositAmountInYuan() {
        order.setDepositAmount(100000L);
        assertEquals("1000.00", order.getDepositAmountInYuan().toString());
    }

    @Test
    @DisplayName("getPlatformDisplayName 正确映射")
    void getPlatformDisplayName() {
        order.setPlatform("WECHAT_PAYSCORE");
        assertEquals("微信支付分", order.getPlatformDisplayName());
        order.setPlatform("ALIPAY_ZHIMA");
        assertEquals("支付宝芝麻信用", order.getPlatformDisplayName());
    }

    @Test
    @DisplayName("isTerminal 终态判断正确")
    void isTerminal() {
        assertFalse(order.isTerminal()); // PENDING_AUTH
        order.setStatusEnum(ServiceOrderStatus.COMPLETED);
        assertTrue(order.isTerminal());
    }
}
