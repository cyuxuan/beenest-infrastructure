package club.beenest.payment.payscore.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付分策略工厂
 * 使用工厂模式管理支付分策略，自动注册所有 PayScoreStrategy 实现
 *
 * <p>与 PaymentStrategyFactory 平行但独立，管理信用免押策略。</p>
 *
 * @author System
 * @since 2026-06-15
 */
@Component
@Slf4j
public class PayScoreStrategyFactory {

    /**
     * 策略缓存
     * key: 支付分平台标识（如：WECHAT_PAYSCORE、ALIPAY_ZHIMA）
     * value: 支付分策略实例
     */
    private final Map<String, PayScoreStrategy> strategyMap = new ConcurrentHashMap<>();

    /**
     * 构造函数 - Spring自动注入所有 PayScoreStrategy 实现
     *
     * @param strategies 所有支付分策略实现
     */
    public PayScoreStrategyFactory(List<PayScoreStrategy> strategies) {
        log.info("初始化支付分策略工厂 - 策略数量: {}", strategies.size());
        for (PayScoreStrategy strategy : strategies) {
            String platform = strategy.getPlatform();
            strategyMap.put(platform, strategy);
            log.info("注册支付分策略 - platform: {}, name: {}, enabled: {}",
                    platform, strategy.getPlatformName(), strategy.isEnabled());
        }
        log.info("支付分策略工厂初始化完成 - 已注册策略: {}", strategyMap.keySet());
    }

    /**
     * 获取支付分策略
     *
     * @param platform 支付分平台标识，不区分大小写
     * @return 支付分策略实例
     * @throws IllegalArgumentException 如果平台不支持或未启用
     */
    public PayScoreStrategy getStrategy(String platform) {
        if (platform == null || platform.trim().isEmpty()) {
            throw new IllegalArgumentException("支付分平台不能为空");
        }
        String platformKey = platform.toUpperCase();
        PayScoreStrategy strategy = strategyMap.get(platformKey);
        if (strategy == null) {
            log.error("不支持的支付分平台 - platform: {}, 已注册平台: {}", platform, strategyMap.keySet());
            throw new IllegalArgumentException("不支持的支付分平台：" + platform);
        }
        if (!strategy.isEnabled()) {
            log.warn("支付分平台未启用 - platform: {}", platform);
            throw new IllegalArgumentException("支付分平台未启用：" + strategy.getPlatformName());
        }
        return strategy;
    }

    /**
     * 检查支付分平台是否启用
     *
     * @param platform 支付分平台标识
     * @return true表示启用
     */
    public boolean isEnabled(String platform) {
        if (platform == null || platform.trim().isEmpty()) {
            return false;
        }
        String platformKey = platform.toUpperCase();
        PayScoreStrategy strategy = strategyMap.get(platformKey);
        return strategy != null && strategy.isEnabled();
    }

    /**
     * 获取所有启用的支付分平台
     *
     * @return 启用的支付分平台标识列表
     */
    public List<String> getEnabledPlatforms() {
        return strategyMap.values().stream()
                .filter(PayScoreStrategy::isEnabled)
                .map(PayScoreStrategy::getPlatform)
                .toList();
    }
}
