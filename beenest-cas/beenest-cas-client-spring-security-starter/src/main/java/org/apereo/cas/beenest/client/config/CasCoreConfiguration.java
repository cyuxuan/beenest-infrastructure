package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.CasUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.client.validation.Cas20ServiceTicketValidator;
import org.apereo.cas.client.validation.TicketValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationEntryPoint;

/**
 * CAS 核心组件配置
 * <p>
 * 注册 Spring Security CAS 原生组件：
 * <ul>
 *   <li>{@link ServiceProperties} — 定义 CAS service URL</li>
 *   <li>{@link CasAuthenticationEntryPoint} — 未认证时重定向到 CAS</li>
 *   <li>{@link CasAuthenticationFilter} — 处理 /login/cas 回调</li>
 *   <li>{@link CasAuthenticationProvider} — ST 验证 + 用户加载</li>
 *   <li>{@link TicketValidator} — 默认使用 Cas20ServiceTicketValidator</li>
 * </ul>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "cas.client", name = "mode", havingValue = "login-gateway", matchIfMissing = true)
@RequiredArgsConstructor
public class CasCoreConfiguration {

    private final CasSecurityProperties properties;

    /**
     * ServiceProperties — 定义 CAS service URL
     */
    @Bean
    public ServiceProperties serviceProperties() {
        ServiceProperties sp = new ServiceProperties();
        sp.setService(properties.getClientHostUrl() + properties.getLoginPath());
        sp.setSendRenew(false);
        sp.setAuthenticateAllArtifacts(properties.getProxy().isEnabled());
        return sp;
    }

    /**
     * CasAuthenticationEntryPoint — 未认证请求重定向到 CAS 登录页
     */
    @Bean
    public CasAuthenticationEntryPoint casAuthenticationEntryPoint(ServiceProperties serviceProperties) {
        CasAuthenticationEntryPoint entryPoint = new CasAuthenticationEntryPoint();
        entryPoint.setLoginUrl(properties.getServerUrl() + "/login");
        entryPoint.setServiceProperties(serviceProperties);
        return entryPoint;
    }

    /**
     * TicketValidator — 默认 Cas20ServiceTicketValidator
     * <p>
     * 如果 Proxy 启用，CasProxyConfiguration 会注册 @Primary 的 Cas20ProxyTicketValidator 替换此默认值。
     */
    @Bean
    @ConditionalOnMissingBean(TicketValidator.class)
    public Cas20ServiceTicketValidator cas20ServiceTicketValidator() {
        return new Cas20ServiceTicketValidator(properties.getServerUrl());
    }

    /**
     * CasAuthenticationProvider — ST 验证 + 用户加载
     */
    @Bean
    public CasAuthenticationProvider casAuthenticationProvider(
            ServiceProperties serviceProperties,
            TicketValidator ticketValidator,
            CasUserDetailsService userDetailsService) {
        CasAuthenticationProvider provider = new CasAuthenticationProvider();
        provider.setServiceProperties(serviceProperties);
        provider.setTicketValidator(ticketValidator);
        provider.setKey(properties.getSecurity().getAuthenticationProviderKey());
        provider.setAuthenticationUserDetailsService(
            new CasAssertionUserDetailsService(userDetailsService));
        return provider;
    }

}
