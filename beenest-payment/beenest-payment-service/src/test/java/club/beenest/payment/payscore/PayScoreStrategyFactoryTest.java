package club.beenest.payment.payscore;

import club.beenest.payment.payscore.strategy.PayScoreStrategy;
import club.beenest.payment.payscore.strategy.PayScoreStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 支付分策略工厂测试
 *
 * @author System
 * @since 2026-06-15
 */
class PayScoreStrategyFactoryTest {

    private PayScoreStrategy wechatStrategy;
    private PayScoreStrategy alipayStrategy;
    private PayScoreStrategyFactory factory;

    @BeforeEach
    void setUp() {
        wechatStrategy = mock(PayScoreStrategy.class);
        when(wechatStrategy.getPlatform()).thenReturn("WECHAT_PAYSCORE");
        when(wechatStrategy.getPlatformName()).thenReturn("微信支付分");
        when(wechatStrategy.isEnabled()).thenReturn(true);

        alipayStrategy = mock(PayScoreStrategy.class);
        when(alipayStrategy.getPlatform()).thenReturn("ALIPAY_ZHIMA");
        when(alipayStrategy.getPlatformName()).thenReturn("支付宝芝麻信用");
        when(alipayStrategy.isEnabled()).thenReturn(false);

        factory = new PayScoreStrategyFactory(List.of(wechatStrategy, alipayStrategy));
    }

    @Test
    @DisplayName("策略工厂正确注册所有策略")
    void strategiesRegistered() {
        assertNotNull(factory.getStrategy("WECHAT_PAYSCORE"));
    }

    @Test
    @DisplayName("getStrategy 平台名不区分大小写")
    void caseInsensitivePlatform() {
        assertNotNull(factory.getStrategy("wechat_payscore"));
        assertNotNull(factory.getStrategy("WECHAT_PAYSCORE"));
    }

    @Test
    @DisplayName("未启用的平台抛出异常")
    void disabledPlatformThrows() {
        assertThrows(IllegalArgumentException.class, () -> factory.getStrategy("ALIPAY_ZHIMA"));
    }

    @Test
    @DisplayName("不支持的平台抛出异常")
    void unsupportedPlatformThrows() {
        assertThrows(IllegalArgumentException.class, () -> factory.getStrategy("UNKNOWN"));
    }

    @Test
    @DisplayName("空平台名抛出异常")
    void nullPlatformThrows() {
        assertThrows(IllegalArgumentException.class, () -> factory.getStrategy(null));
        assertThrows(IllegalArgumentException.class, () -> factory.getStrategy(""));
    }

    @Test
    @DisplayName("isEnabled 正确判断")
    void isEnabledCorrect() {
        assertTrue(factory.isEnabled("WECHAT_PAYSCORE"));
        assertFalse(factory.isEnabled("ALIPAY_ZHIMA"));
        assertFalse(factory.isEnabled("UNKNOWN"));
        assertFalse(factory.isEnabled(null));
    }

    @Test
    @DisplayName("getEnabledPlatforms 只返回启用的平台")
    void getEnabledPlatforms() {
        List<String> enabled = factory.getEnabledPlatforms();
        assertEquals(1, enabled.size());
        assertTrue(enabled.contains("WECHAT_PAYSCORE"));
    }
}
