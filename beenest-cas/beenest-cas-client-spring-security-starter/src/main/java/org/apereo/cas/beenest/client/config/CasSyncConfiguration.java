package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.sync.CasSyncPullScheduler;
import org.apereo.cas.beenest.client.sync.CasSyncWebhookFilter;
import org.apereo.cas.beenest.client.sync.CasUserChangeListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 用户同步条件配置
 * <p>
 * 当 {@code cas.client.sync.enabled=true} 时激活。
 * 当 {@code cas.client.sync.pull-enabled=true} 时额外激活拉取调度器。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "cas.client.sync", name = "enabled", havingValue = "true", matchIfMissing = false)
public class CasSyncConfiguration {

    @Bean
    public FilterRegistrationBean<CasSyncWebhookFilter> casSyncWebhookFilterRegistration(
            CasSecurityProperties properties,
            ActiveSessionRegistry activeSessionRegistry,
            @Autowired(required = false) List<CasUserChangeListener> listeners) {
        assertSignKeyConfigured(properties);
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
