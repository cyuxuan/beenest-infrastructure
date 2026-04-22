package club.beenest.payment.withdraw.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提现策略工厂
 * 管理所有提现策略，根据平台获取对应的策略
 * 
 * <p>使用Spring自动注入所有WithdrawStrategy实现类。</p>
 * 
 * @author System
 * @since 2026-01-27
 */
@Component
@Slf4j
public class WithdrawStrategyFactory {
    
    private final Map<String, WithdrawStrategy> strategyMap = new ConcurrentHashMap<>();
    
    /**
     * 构造函数，Spring自动注入所有WithdrawStrategy实现
     */
    public WithdrawStrategyFactory(List<WithdrawStrategy> strategies) {
        log.info("初始化提现策略工厂 - 策略数量: {}", strategies.size());
        
        for (WithdrawStrategy strategy : strategies) {
            strategyMap.put(strategy.getPlatform(), strategy);
            log.info("注册提现策略 - platform: {}, name: {}, enabled: {}", 
                    strategy.getPlatform(), 
                    strategy.getPlatformName(), 
                    strategy.isEnabled());
        }
        
        log.info("提现策略工厂初始化完成 - 已注册策略: {}", strategyMap.keySet());
    }
    
    /**
     * 根据平台获取提现策略
     * 
     * @param platform 平台标识（WECHAT、ALIPAY、DOUYIN等）
     * @return 提现策略
     * @throws IllegalArgumentException 如果平台不支持
     */
    public WithdrawStrategy getStrategy(String platform) {
        if (platform == null || platform.isEmpty()) {
            throw new IllegalArgumentException("平台标识不能为空");
        }
        
        WithdrawStrategy strategy = strategyMap.get(platform.toUpperCase());
        
        if (strategy == null) {
            log.error("不支持的提现平台 - platform: {}", platform);
            throw new IllegalArgumentException("不支持的提现平台: " + platform);
        }
        
        if (!strategy.isEnabled()) {
            log.warn("提现平台未启用 - platform: {}", platform);
            throw new IllegalStateException("提现平台未启用: " + strategy.getPlatformName());
        }
        
        return strategy;
    }
    
    /**
     * 获取所有已启用的提现策略
     * 
     * @return 已启用的提现策略列表
     */
    public Map<String, WithdrawStrategy> getEnabledStrategies() {
        Map<String, WithdrawStrategy> enabledStrategies = new HashMap<>();
        
        for (Map.Entry<String, WithdrawStrategy> entry : strategyMap.entrySet()) {
            if (entry.getValue().isEnabled()) {
                enabledStrategies.put(entry.getKey(), entry.getValue());
            }
        }
        
        return enabledStrategies;
    }
    
    /**
     * 检查平台是否支持
     * 
     * @param platform 平台标识
     * @return true表示支持，false表示不支持
     */
    public boolean isSupported(String platform) {
        return platform != null && strategyMap.containsKey(platform.toUpperCase());
    }
    
    /**
     * 检查平台是否启用
     * 
     * @param platform 平台标识
     * @return true表示启用，false表示未启用
     */
    public boolean isEnabled(String platform) {
        if (!isSupported(platform)) {
            return false;
        }
        
        WithdrawStrategy strategy = strategyMap.get(platform.toUpperCase());
        return strategy.isEnabled();
    }
}
