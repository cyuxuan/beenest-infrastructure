package org.apereo.cas.beenest.client.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * CAS Security Starter 主自动配置入口
 * <p>
 * 激活条件：{@code cas.client.enabled=true}
 * <p>
 * 自动配置内容：
 * <ul>
 *   <li>核心 CAS 组件（ServiceProperties, CasAuthenticationProvider, CasAuthenticationFilter 等）</li>
 *   <li>Proxy Ticket 支持（条件：cas.client.proxy.enabled=true）</li>
 *   <li>Bearer Token 认证（条件：cas.client.token-auth.enabled=true）</li>
 *   <li>SLO 单点登出（默认启用）</li>
 * </ul>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CasSecurityProperties.class)
@Import({
    CasDefaultUserDetailsConfiguration.class,
    CasCoreConfiguration.class,
    CasAuthenticationConfigurerConfiguration.class,
    CasDefaultSecurityConfiguration.class,
    CasBusinessLoginProxyConfiguration.class,
    CasProxyConfiguration.class,
    CasBearerTokenConfiguration.class,
    CasSloConfiguration.class,
})
public class CasSecurityAutoConfiguration {

    public CasSecurityAutoConfiguration() {
        log.info("beenest-cas-client-spring-security-starter 已激活");
    }
}
