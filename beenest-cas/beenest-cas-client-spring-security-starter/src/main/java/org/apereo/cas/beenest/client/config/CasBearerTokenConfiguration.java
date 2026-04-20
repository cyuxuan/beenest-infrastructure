package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.*;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.CacheManager;

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
     * 但在 Spring Security 6/Boot 3 中不一定以 Bean 形式暴露。
     * 解决方案：优先使用已注册的 AuthenticationManager，
     * 若未暴露，则从 AuthenticationConfiguration 动态获取。
     */
    @Bean
    public CasBearerTokenAuthenticationFilter casBearerTokenAuthenticationFilter(
            AuthenticationConfiguration authenticationConfiguration,
            ObjectProvider<CasBearerTokenAuthenticationProvider> authenticationProviderProvider,
            ObjectProvider<org.springframework.security.authentication.AuthenticationManager> authenticationManagerProvider) {
        return new CasBearerTokenAuthenticationFilter(
            () -> authenticationProviderProvider.getIfAvailable(),
            () -> {
            org.springframework.security.authentication.AuthenticationManager authenticationManager =
                    authenticationManagerProvider.getIfAvailable();
            if (authenticationManager != null) {
                return authenticationManager;
            }
            try {
                return authenticationConfiguration.getAuthenticationManager();
            } catch (Exception e) {
                return null;
            }
            }
        );
    }
}
