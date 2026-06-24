package org.apereo.cas.beenest.client.accesscontrol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * CAS 用户访问控制自动配置。
 * <p>
 * 只有同时满足以下条件才激活：
 * <ul>
 *   <li>{@code cas.client.access-control.enabled=true}</li>
 *   <li>应用提供了 {@link CasUserAccessControlService} Bean</li>
 * </ul>
 * 不满足时完全不影响现有行为。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "cas.client.access-control", name = "enabled",
                        havingValue = "true")
@ConditionalOnBean(CasUserAccessControlService.class)
@EnableConfigurationProperties(CasAccessControlProperties.class)
public class CasAccessControlAutoConfiguration {

    @Bean
    public CasAccessControlManager casAccessControlManager(
            CasUserAccessControlService accessControlService,
            CasAccessControlProperties properties) {
        log.info("CAS 访问控制 SPI 已激活, requiredRole={}", accessControlService.getRequiredRole());
        return new CasAccessControlManager(accessControlService, properties);
    }

    @Bean
    public CasAccessControlDeniedHandler casAccessControlDeniedHandler(
            CasAccessControlProperties properties) {
        return new CasAccessControlDeniedHandler(properties);
    }
}