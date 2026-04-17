package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.*;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * Bearer Token 条件配置
 * <p>
 * 当 {@code cas.client.token-auth.enabled=true} 时激活。
 * 注册 CasBearerTokenAuthenticationFilter、Provider、Cache、TgtValidator 和 TokenRefresher。
 * <p>
 * 无感刷新：当 {@code cas.client.token-auth.auto-refresh-enabled=true}（默认）时，
 * accessToken 过期后自动用 refreshToken 调用 CAS Server 刷新端点。
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client.token-auth", name = "enabled", havingValue = "true")
public class CasBearerTokenConfiguration {

    @Bean
    public BearerTokenCache bearerTokenCache(CasSecurityProperties properties) {
        return new BearerTokenCache(
            properties.getTokenAuth().getValidateCacheTtlSeconds(),
            properties.getTokenAuth().getValidateCacheMaxSize());
    }

    @Bean
    public BearerTokenRevocationService bearerTokenRevocationService(CasSecurityProperties properties,
                                                                     ObjectProvider<CacheManager> cacheManagerProvider) {
        return new BearerTokenRevocationService(properties, cacheManagerProvider.getIfAvailable());
    }

    @Bean
    public CasTgtValidator casTgtValidator(CasSecurityProperties properties) {
        return new CasTgtValidator(properties);
    }

    @Bean
    public CasTokenRefresher casTokenRefresher(CasSecurityProperties properties) {
        return new CasTokenRefresher(properties);
    }

    @Bean
    public CasBearerTokenAuthenticationProvider casBearerTokenAuthenticationProvider(
            CasTgtValidator tgtValidator,
            CasSecurityProperties properties,
            BearerTokenCache tokenCache,
            BearerTokenRevocationService revocationService,
            CasUserDetailsService userDetailsService,
            CasTokenRefresher tokenRefresher) {
        return new CasBearerTokenAuthenticationProvider(
            tgtValidator, properties, tokenCache, revocationService, userDetailsService, tokenRefresher);
    }

    /**
     * 注意：CasBearerTokenAuthenticationFilter 需要 AuthenticationManager，
     * 但在配置阶段 AuthenticationManager 尚未构建。
     * 解决方案：通过 ApplicationContext 延迟获取。
     */
    @Bean
    public CasBearerTokenAuthenticationFilter casBearerTokenAuthenticationFilter(
            org.springframework.context.ApplicationContext applicationContext) {
        // 延迟获取 AuthenticationManager，避免循环依赖
        return new CasBearerTokenAuthenticationFilter(
            authentication -> applicationContext.getBean(AuthenticationManager.class).authenticate(authentication)
        );
    }
}
