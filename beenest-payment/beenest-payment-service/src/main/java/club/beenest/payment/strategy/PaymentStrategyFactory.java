package club.beenest.payment.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付策略工厂
 * 使用工厂模式管理支付策略
 * 
 * <p>设计模式：</p>
 * <ul>
 *   <li>工厂模式 - 根据支付平台类型创建对应的支付策略</li>
 *   <li>单例模式 - 策略实例缓存，避免重复创建</li>
 * </ul>
 * 
 * <h3>扩展性：</h3>
 * <ul>
 *   <li>自动注册 - Spring自动注入所有PaymentStrategy实现</li>
 *   <li>易扩展 - 新增支付平台只需实现PaymentStrategy接口</li>
 *   <li>零配置 - 无需修改工厂代码</li>
 * </ul>
 * 
 * <h3>使用示例：</h3>
 * <pre>
 * // 获取微信支付策略
 * PaymentStrategy strategy = paymentStrategyFactory.getStrategy("WECHAT");
 * 
 * // 创建支付订单
 * Map&lt;String, Object&gt; paymentParams = strategy.createPayment(paymentOrder);
 * </pre>
 * 
 * @author System
 * @since 2026-01-26
 */
@Component
@Slf4j
public class PaymentStrategyFactory {
    
    /**
     * 策略缓存
     * key: 支付平台标识（如：WECHAT、ALIPAY、DOUYIN）
     * value: 支付策略实例
     */
    private final Map<String, PaymentStrategy> strategyMap = new ConcurrentHashMap<>();
    
    /**
     * 构造函数
     * Spring自动注入所有PaymentStrategy实现
     * 
     * @param strategies 所有支付策略实现的列表
     */
    public PaymentStrategyFactory(List<PaymentStrategy> strategies) {
        log.info("初始化支付策略工厂 - 策略数量: {}", strategies.size());
        
        // 注册所有支付策略
        for (PaymentStrategy strategy : strategies) {
            String platform = strategy.getPlatform();
            strategyMap.put(platform, strategy);
            log.info("注册支付策略 - platform: {}, name: {}, enabled: {}", 
                    platform, strategy.getPlatformName(), strategy.isEnabled());
        }
        
        log.info("支付策略工厂初始化完成 - 已注册策略: {}", strategyMap.keySet());
    }
    
    /**
     * 获取支付策略
     * 
     * <p>根据支付平台标识获取对应的支付策略实例。</p>
     * 
     * <h4>支持的平台：</h4>
     * <ul>
     *   <li>WECHAT - 微信支付</li>
     *   <li>ALIPAY - 支付宝</li>
     *   <li>DOUYIN - 抖音支付</li>
     * </ul>
     * 
     * @param platform 支付平台标识，不区分大小写
     * @return 支付策略实例
     * @throws IllegalArgumentException 如果平台不支持或未启用
     */
    public PaymentStrategy getStrategy(String platform) {
        if (platform == null || platform.trim().isEmpty()) {
            throw new IllegalArgumentException("支付平台不能为空");
        }
        
        // 转换为大写，统一处理
        String platformKey = platform.toUpperCase();
        
        // 从缓存中获取策略
        PaymentStrategy strategy = strategyMap.get(platformKey);
        
        if (strategy == null) {
            log.error("不支持的支付平台 - platform: {}, 已注册平台: {}", 
                    platform, strategyMap.keySet());
            throw new IllegalArgumentException("不支持的支付平台：" + platform);
        }
        
        // 检查策略是否启用
        if (!strategy.isEnabled()) {
            log.warn("支付平台未启用 - platform: {}", platform);
            throw new IllegalArgumentException("支付平台未启用：" + strategy.getPlatformName());
        }
        
        return strategy;
    }
    
    /**
     * 检查支付平台是否支持
     * 
     * @param platform 支付平台标识
     * @return true表示支持，false表示不支持
     */
    public boolean isSupported(String platform) {
        if (platform == null || platform.trim().isEmpty()) {
            return false;
        }
        
        String platformKey = platform.toUpperCase();
        return strategyMap.containsKey(platformKey);
    }
    
    /**
     * 检查支付平台是否启用
     * 
     * @param platform 支付平台标识
     * @return true表示启用，false表示未启用或不支持
     */
    public boolean isEnabled(String platform) {
        if (!isSupported(platform)) {
            return false;
        }
        
        String platformKey = platform.toUpperCase();
        PaymentStrategy strategy = strategyMap.get(platformKey);
        return strategy != null && strategy.isEnabled();
    }
    
    /**
     * 获取所有支持的支付平台
     * 
     * @return 支付平台标识列表
     */
    public List<String> getSupportedPlatforms() {
        return List.copyOf(strategyMap.keySet());
    }
    
    /**
     * 获取所有启用的支付平台
     * 
     * @return 启用的支付平台标识列表
     */
    public List<String> getEnabledPlatforms() {
        return strategyMap.values().stream()
                .filter(PaymentStrategy::isEnabled)
                .map(PaymentStrategy::getPlatform)
                .toList();
    }
    
    /**
     * 获取支付平台信息
     * 
     * @param platform 支付平台标识
     * @return 支付平台信息Map
     */
    public Map<String, Object> getPlatformInfo(String platform) {
        PaymentStrategy strategy = getStrategy(platform);
        
        Map<String, Object> info = new ConcurrentHashMap<>();
        info.put("platform", strategy.getPlatform());
        info.put("name", strategy.getPlatformName());
        info.put("enabled", strategy.isEnabled());
        
        return info;
    }
    
    /**
     * 获取所有支付平台信息
     * 
     * @return 支付平台信息列表
     */
    public List<Map<String, Object>> getAllPlatformInfo() {
        return strategyMap.values().stream()
                .map(strategy -> {
                    Map<String, Object> info = new ConcurrentHashMap<>();
                    info.put("platform", strategy.getPlatform());
                    info.put("name", strategy.getPlatformName());
                    info.put("enabled", strategy.isEnabled());
                    return info;
                })
                .toList();
    }
}
