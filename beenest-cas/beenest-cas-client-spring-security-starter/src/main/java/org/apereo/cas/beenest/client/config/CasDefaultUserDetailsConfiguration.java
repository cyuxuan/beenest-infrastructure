package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.CasUserDetailsService;
import org.apereo.cas.beenest.client.authentication.DefaultCasUserDetailsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 默认 CAS 用户详情加载配置。
 * <p>
 * 当业务应用未提供自定义 {@link CasUserDetailsService} 时，
 * starter 会自动装配一个基于 CAS Assertion 的默认实现，
 * 让业务系统可以直接完成最小可用接入。
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
public class CasDefaultUserDetailsConfiguration {

    @Bean
    @ConditionalOnMissingBean(CasUserDetailsService.class)
    public CasUserDetailsService defaultCasUserDetailsService(
            ObjectProvider<org.apereo.cas.beenest.client.authentication.CasUserRegistrationService> registrationServiceProvider) {
        return new DefaultCasUserDetailsService(registrationServiceProvider);
    }
}
