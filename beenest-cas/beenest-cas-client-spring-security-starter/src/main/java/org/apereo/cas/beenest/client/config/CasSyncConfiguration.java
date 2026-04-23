package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.sync.CasSyncPullScheduler;
import org.apereo.cas.beenest.client.sync.CasSyncWebhookFilter;
import org.apereo.cas.beenest.client.sync.CasUserChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户同步条件配置（已弃用）
 * <p>
 * 当 {@code cas.client.sync.enabled=true} 时激活。
 * <p>
 * <b>已弃用</b>：CAS Server 全面原生化重构后已移除自定义同步端点。
 * 用户属性通过每次 Token 验证自动获取最新值，无需额外同步机制。
 * 此配置类将在下个大版本移除，请尽快移除 {@code cas.client.sync.*} 配置。
 *
 * @deprecated CAS Server 已移除同步端点，此模块不再有实际功能
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "cas.client.sync", name = "enabled", havingValue = "true", matchIfMissing = false)
@Deprecated(since = "2.0", forRemoval = true)
@Slf4j
public class CasSyncConfiguration {

    @Bean
    public FilterRegistrationBean<CasSyncWebhookFilter> casSyncWebhookFilterRegistration(
            CasSecurityProperties properties,
            ActiveSessionRegistry activeSessionRegistry,
            @Autowired(required = false) List<CasUserChangeListener> listeners) {
        assertSignKeyConfigured(properties);
        LOGGER.warn("【已弃用】cas.client.sync.enabled=true — CAS Server 已停止推送 webhook，此过滤器不再收到数据。" +
                "请迁移至 CAS 原生属性发布机制，并移除 sync 配置。");
        FilterRegistrationBean<CasSyncWebhookFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CasSyncWebhookFilter(properties, activeSessionRegistry, listeners));
        registration.addUrlPatterns("/*");
        registration.setOrder(3);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(prefix = "cas.client.sync", name = "pull-enabled", havingValue = "true")
    public CasSyncPullScheduler casSyncPullScheduler(
            CasSecurityProperties properties,
            ActiveSessionRegistry activeSessionRegistry,
            @Autowired(required = false) List<CasUserChangeListener> listeners) {
        assertSignKeyConfigured(properties);
        return new CasSyncPullScheduler(properties, activeSessionRegistry, listeners);
    }

    private void assertSignKeyConfigured(CasSecurityProperties properties) {
        if (!StringUtils.hasText(properties.getSignKey())) {
            throw new IllegalStateException("cas.client.sync.enabled=true 时必须配置 cas.client.sign-key");
        }
    }
}
