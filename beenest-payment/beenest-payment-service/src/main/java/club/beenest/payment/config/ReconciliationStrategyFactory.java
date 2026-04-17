package club.beenest.payment.config;

import club.beenest.payment.service.IReconciliationStrategyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对账策略工厂
 * 根据支付平台标识获取对应的对账策略
 *
 * @author System
 * @since 2026-04-08
 */
@Component
@Slf4j
public class ReconciliationStrategyFactory {

    private final Map<String, IReconciliationStrategyService> strategyMap = new ConcurrentHashMap<>();

    public ReconciliationStrategyFactory(List<IReconciliationStrategyService> strategies) {
        for (IReconciliationStrategyService strategy : strategies) {
            strategyMap.put(strategy.getPlatform(), strategy);
            log.info("注册对账策略 - platform: {}", strategy.getPlatform());
        }
        log.info("对账策略工厂初始化完成 - 已注册: {}", strategyMap.keySet());
    }

    public IReconciliationStrategyService getStrategy(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("支付平台不能为空");
        }
        String key = platform.toUpperCase();
        IReconciliationStrategyService strategy = strategyMap.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的对账平台: " + platform);
        }
        return strategy;
    }

    public boolean isSupported(String platform) {
        return platform != null && strategyMap.containsKey(platform.toUpperCase());
    }
}
