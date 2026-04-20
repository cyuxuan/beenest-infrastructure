package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.controller.CasBusinessLoginProxyController;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 业务系统登录代理配置。
 * <p>
 * 提供转发 CAS 登录请求所需的 RestTemplate、Service 和 Controller。
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "cas.client", name = "mode", havingValue = "login-gateway", matchIfMissing = true)
public class CasBusinessLoginProxyConfiguration {

    /**
     * 创建带超时的 RestTemplate。
     *
     * @return RestTemplate
     */
    @Bean
    @ConditionalOnProperty(prefix = "cas.client.business-login-proxy", name = "enabled", havingValue = "true")
    public RestTemplate casBusinessLoginProxyRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    /**
     * 创建业务系统登录代理服务。
     *
     * @param properties CAS Starter 配置
     * @param restTemplate HTTP 客户端
     * @return 代理服务
     */
    @Bean
    @ConditionalOnProperty(prefix = "cas.client.business-login-proxy", name = "enabled", havingValue = "true")
    public CasBusinessLoginProxyService casBusinessLoginProxyService(
            CasSecurityProperties properties,
            RestTemplate restTemplate) {
        return new CasBusinessLoginProxyService(properties, restTemplate);
    }

    /**
     * 创建业务系统登录代理控制器。
     *
     * @param proxyService 代理服务
     * @return 控制器
     */
    @Bean
    @ConditionalOnProperty(prefix = "cas.client.business-login-proxy", name = "enabled", havingValue = "true")
    public CasBusinessLoginProxyController casBusinessLoginProxyController(
            CasBusinessLoginProxyService proxyService,
            ObjectProvider<BearerTokenCache> bearerTokenCacheProvider,
            ObjectProvider<BearerTokenRevocationService> bearerTokenRevocationServiceProvider) {
        return new CasBusinessLoginProxyController(
                proxyService,
                bearerTokenCacheProvider,
                bearerTokenRevocationServiceProvider);
    }
}
