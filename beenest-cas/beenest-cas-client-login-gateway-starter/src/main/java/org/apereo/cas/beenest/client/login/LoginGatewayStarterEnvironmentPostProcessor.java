package org.apereo.cas.beenest.client.login;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录网关 starter 的环境后置处理器。
 * <p>
 * 作用是为消费者补齐最小可用默认值，确保只要引入 starter 就能进入 CAS 登录网关模式，
 * 避免业务系统还要手动补 {@code cas.client.enabled=true} 这类基础开关。
 */
public class LoginGatewayStarterEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "beenestCasLoginGatewayStarterDefaults";

    /**
     * 1. 注入登录网关场景默认值。
     *
     * @param environment 当前环境
     * @param application Spring 应用
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        putIfMissing(environment, defaults, "cas.client.enabled", "true");
        putIfMissing(environment, defaults, "cas.client.mode", "login-gateway");
        putIfMissing(environment, defaults, "cas.client.token-auth.enabled", "true");
        putIfMissing(environment, defaults, "cas.client.redirect-login", "true");
        putIfMissing(environment, defaults, "cas.client.use-session", "true");
        putIfMissing(environment, defaults, "cas.client.slo.enabled", "true");

        if (!defaults.isEmpty()) {
            PropertySource<?> propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, defaults);
            environment.getPropertySources().addFirst(propertySource);
        }
    }

    /**
     * 2. 让默认值尽早生效，避免被后续条件装配错过。
     *
     * @return 最高优先级之前的顺序
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 3. 如果环境中未显式配置，则写入 starter 默认值。
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
