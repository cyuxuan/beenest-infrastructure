package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.filter.CasLogoutFilter;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.session.SingleSignOutHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SLO（单点登出）配置
 * <p>
 * 默认启用。仅使用自定义 CasLogoutFilter + ActiveSessionRegistry。
 * 不使用 Apereo 的 SingleSignOutFilter，避免双重 SLO 机制。
 * <p>
 * ActiveSessionRegistry 同时注册为 HttpSessionListener，追踪所有活跃 Session，
 * 维护 ST→SessionId 和 userId→SessionList 映射，供 SLO 和用户同步使用。
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "cas.client", name = "mode", havingValue = "login-gateway", matchIfMissing = true)
@ConditionalOnProperty(prefix = "cas.client.slo", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CasSloConfiguration {

    @Bean
    public ActiveSessionRegistry activeSessionRegistry() {
        return new ActiveSessionRegistry();
    }

    @Bean
    public SingleSignOutHandler singleSignOutHandler() {
        return new SingleSignOutHandler();
    }

    /**
     * 注册 ActiveSessionRegistry 为 HttpSessionListener
     * <p>
     * Spring Boot 会自动注册 HttpSessionListener 类型的 Bean，
     * 但显式注册更清晰且确保顺序。
     */
    @Bean
    public ServletListenerRegistrationBean<ActiveSessionRegistry> activeSessionRegistryListener(
            ActiveSessionRegistry activeSessionRegistry) {
        return new ServletListenerRegistrationBean<>(activeSessionRegistry);
    }

    /**
     * 自定义 SLO 回调 Filter（处理 CAS Server POST 的 SAML LogoutRequest）
     * <p>
     * 注册为 Servlet Filter（Spring Security Filter Chain 之前），
     * 因为 SLO POST 不携带用户 JSESSIONID Cookie，不经过 Spring Security。
     */
    @Bean
    public FilterRegistrationBean<CasLogoutFilter> casLogoutFilterRegistration(
            CasSecurityProperties properties,
            SingleSignOutHandler singleSignOutHandler,
            ActiveSessionRegistry activeSessionRegistry,
            org.springframework.beans.factory.ObjectProvider<BearerTokenCache> bearerTokenCacheProvider) {
        FilterRegistrationBean<CasLogoutFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CasLogoutFilter(
                properties,
                singleSignOutHandler,
                activeSessionRegistry,
                bearerTokenCacheProvider.getIfAvailable()));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
