package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.accesscontrol.CasAccessControlDeniedHandler;
import org.apereo.cas.beenest.client.accesscontrol.CasAccessControlManager;
import org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationFilter;
import org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationProvider;
import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.session.CasLoginSuccessHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationEntryPoint;
import org.springframework.security.cas.web.CasAuthenticationFilter;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.util.StringUtils;

/**
 * CAS Filter Chain 配置器装配。
 * <p>
 * 无论当前服务运行在登录网关模式还是资源服务模式，
 * 都统一由该配置类装配 {@link CasAuthenticationConfigurer}。
 * <p>
 * 当 Web SSO 相关 Bean 不存在时，仅启用 Bearer Token 认证链路。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
public class CasAuthenticationConfigurerConfiguration {

    private final CasSecurityProperties properties;

    /**
     * 创建 CAS Security DSL 配置器。
     *
     * @param serviceProperties         ServiceProperties（仅登录网关模式存在）
     * @param casProviderProvider       CAS 票据认证 Provider（仅登录网关模式存在）
     * @param entryPointProvider        CAS 登录入口（仅登录网关模式存在）
     * @param activeSessionRegistryProvider 活跃会话注册表（仅登录网关 + SLO 场景存在）
     * @param bearerTokenFilterProvider Bearer Token 过滤器（可选）
     * @param bearerTokenProviderProvider Bearer Token Provider（可选）
     * @return DSL 配置器
     */
    @Bean
    public CasAuthenticationConfigurer casAuthenticationConfigurer(
            ObjectProvider<ServiceProperties> serviceProperties,
            ObjectProvider<CasAuthenticationProvider> casProviderProvider,
            ObjectProvider<CasAuthenticationEntryPoint> entryPointProvider,
            ObjectProvider<ActiveSessionRegistry> activeSessionRegistryProvider,
            ObjectProvider<CasBearerTokenAuthenticationFilter> bearerTokenFilterProvider,
            ObjectProvider<CasBearerTokenAuthenticationProvider> bearerTokenProviderProvider,
            ObjectProvider<CasAccessControlManager> accessControlManagerProvider,
            ObjectProvider<CasAccessControlDeniedHandler> deniedHandlerProvider) {
        ServiceProperties sp = serviceProperties.getIfAvailable();
        CasAuthenticationFilter casFilter = null;
        if (sp != null) {
            casFilter = new CasAuthenticationFilter();
            casFilter.setFilterProcessesUrl(properties.getLoginPath());
            casFilter.setServiceProperties(sp);
            ActiveSessionRegistry activeSessionRegistry = activeSessionRegistryProvider.getIfAvailable();
            if (activeSessionRegistry != null) {
                // 前后端分离场景：CAS 回调后端 /login/cas 验证 ST，
                // 认证成功后应重定向到 clientHostUrl（前端地址），而非后端自身地址
                SavedRequestAwareAuthenticationSuccessHandler successHandler =
                    new SavedRequestAwareAuthenticationSuccessHandler();
                String clientHostUrl = properties.getClientHostUrl();
                if (StringUtils.hasText(clientHostUrl)) {
                    successHandler.setDefaultTargetUrl(clientHostUrl);
                    log.info("CAS 登录成功后默认重定向到前端地址: {}", clientHostUrl);
                }
                casFilter.setAuthenticationSuccessHandler(new CasLoginSuccessHandler(
                    activeSessionRegistry,
                    successHandler,
                    accessControlManagerProvider.getIfAvailable(),
                    deniedHandlerProvider.getIfAvailable()));
            }
        }
        return new CasAuthenticationConfigurer(
                casFilter,
                casProviderProvider.getIfAvailable(),
                entryPointProvider.getIfAvailable(),
                bearerTokenFilterProvider.getIfAvailable(),
                bearerTokenProviderProvider.getIfAvailable(),
                properties);
    }
}
