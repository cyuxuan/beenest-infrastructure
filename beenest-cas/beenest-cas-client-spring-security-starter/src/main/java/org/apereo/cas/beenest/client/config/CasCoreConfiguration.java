package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.CasUserDetailsService;
import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.session.CasLoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.client.validation.Cas20ServiceTicketValidator;
import org.apereo.cas.client.validation.TicketValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationEntryPoint;
import org.springframework.security.cas.web.CasAuthenticationFilter;

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

    /**
     * CasAuthenticationConfigurer — DSL 配置器
     * <p>
     * 注入所有 CAS 组件，业务系统通过 http.with(configurer, c -> {}) 引入。
     * <p>
     * CasAuthenticationFilter 在此处内部创建，不注册为独立 Bean。
     * 原因：CasAuthenticationFilter.afterPropertiesSet() 要求 authenticationManager 不为 null，
     * 而 AuthenticationManager 只在 SecurityFilterChain 构建时才可用。
     * 如果注册为 Bean，Servlet 容器初始化时会触发 afterPropertiesSet 检查导致启动失败。
     */
    @Bean
    public CasAuthenticationConfigurer casAuthenticationConfigurer(
            ServiceProperties serviceProperties,
            CasAuthenticationProvider casProvider,
            CasAuthenticationEntryPoint entryPoint,
            ObjectProvider<ActiveSessionRegistry> activeSessionRegistryProvider,
            @Autowired(required = false) org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationFilter bearerTokenFilter,
            @Autowired(required = false) org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationProvider bearerTokenProvider) {
        CasAuthenticationFilter casFilter = new CasAuthenticationFilter();
        casFilter.setFilterProcessesUrl(properties.getLoginPath());
        casFilter.setServiceProperties(serviceProperties);

        ActiveSessionRegistry activeSessionRegistry = activeSessionRegistryProvider.getIfAvailable();
        if (activeSessionRegistry != null) {
            casFilter.setAuthenticationSuccessHandler(new CasLoginSuccessHandler(activeSessionRegistry));
        }
        return new CasAuthenticationConfigurer(
            casFilter, casProvider, entryPoint,
            bearerTokenFilter, bearerTokenProvider, properties);
    }
}
