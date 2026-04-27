package org.apereo.cas.beenest.client.resource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资源服务 starter 的环境后置处理器。
 * <p>
 * 作用是为资源服务场景补齐最小可用默认值，让业务系统引入 starter 后即可进入
 * 资源服务模式，无需额外手工开启 CAS 基础开关。
 */
public class ResourceServerStarterEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "beenestCasResourceServerStarterDefaults";

    /**
     * 1. 注入资源服务场景默认值。
     *
     * @param environment 当前环境
     * @param application Spring 应用
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        putIfMissing(environment, defaults, "cas.client.enabled", "true");
        putIfMissing(environment, defaults, "cas.client.mode", "resource-server");
        putIfMissing(environment, defaults, "cas.client.token-auth.enabled", "true");
        putIfMissing(environment, defaults, "cas.client.redirect-login", "false");
        putIfMissing(environment, defaults, "cas.client.use-session", "false");
        putIfMissing(environment, defaults, "cas.client.slo.enabled", "false");

        if (!defaults.isEmpty()) {
            PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, defaults);
            environment.getPropertySources().addFirst(propertySource);
        }
    }

    /**
     * 2. 尽早应用默认值，避免被后续条件判断跳过。
     *
     * @return 最高优先级顺序
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 3. 仅在外部未配置时补默认值。
     *
     * @param environment 当前环境
     * @param defaults 默认值容器
     * @param key 配置键
     * @param value 配置值
     */
    private void putIfMissing(ConfigurableEnvironment environment, Map<String, Object> defaults, String key, Object value) {
        if (environment.getProperty(key) == null) {
            defaults.put(key, value);
        }
    }
}
