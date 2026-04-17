package org.apereo.cas.beenest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apereo.cas.beenest.mapper.CasAppAccessMapper;
import org.apereo.cas.beenest.mapper.CasAuthAuditLogMapper;
import org.apereo.cas.beenest.mapper.CasSyncStrategyMapper;
import org.apereo.cas.beenest.mapper.CasUserChangeLogMapper;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.filter.CasServiceCredentialFilter;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apereo.cas.beenest.service.CasServiceAdminService;
import org.apereo.cas.beenest.service.CasServiceCredentialService;
import org.apereo.cas.beenest.service.SmsService;
import org.apereo.cas.beenest.service.SyncStrategyService;
import org.apereo.cas.beenest.service.UserAdminService;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.service.UserSyncPushService;
import org.apereo.cas.beenest.service.UserSyncService;
import org.apereo.cas.services.ServicesManager;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;

/**
 * Beenest 业务服务注册。
 * <p>
 * 显式注册服务 bean，避免依赖组件扫描顺序。
 */
@AutoConfiguration
@EnableConfigurationProperties(CasServiceCredentialProperties.class)
public class BeenestServiceConfiguration {

    @Bean
    public UserSyncPushService userSyncPushService(final CasSyncStrategyMapper syncStrategyMapper,
                                                   final ObjectMapper objectMapper,
                                                   final StringRedisTemplate redisTemplate) {
        return new UserSyncPushService(syncStrategyMapper, objectMapper, redisTemplate);
    }

    @Bean
    public UserSyncService userSyncService(final CasUserChangeLogMapper changeLogMapper,
                                           final ObjectMapper objectMapper,
                                           final UserSyncPushService pushService) {
        return new UserSyncService(changeLogMapper, objectMapper, pushService);
    }

    @Bean
    public AppAccessService appAccessService(final CasAppAccessMapper appAccessMapper,
                                             final UserSyncService userSyncService) {
        return new AppAccessService(appAccessMapper, userSyncService);
    }

    @Bean
    public UserIdentityService userIdentityService(final UnifiedUserMapper userMapper,
                                                   final UserSyncService userSyncService,
                                                   @Value("${beenest.user.auto-grant-service-ids:10001}") final String autoGrantServiceIds,
                                                   final AppAccessService appAccessService) {
        return new UserIdentityService(userMapper, userSyncService, appAccessService, autoGrantServiceIds);
    }

    @Bean
    public UserAdminService userAdminService(final UnifiedUserMapper userMapper,
                                             final UserSyncService userSyncService,
                                             final AppAccessService appAccessService) {
        return new UserAdminService(userMapper, userSyncService, appAccessService);
    }

    @Bean
    public AuthAuditService authAuditService(final CasAuthAuditLogMapper auditLogMapper) {
        return new AuthAuditService(auditLogMapper);
    }

    @Bean
    public CasServiceCredentialService casServiceCredentialService(final org.apereo.cas.beenest.mapper.CasServiceCredentialMapper credentialMapper,
                                                                    final CasServiceCredentialProperties properties) {
        return new CasServiceCredentialService(credentialMapper, properties);
    }

    @Bean
    public FilterRegistrationBean<CasServiceCredentialFilter> casServiceCredentialFilter(
            final CasServiceCredentialService credentialService,
            final CasServiceCredentialProperties properties,
            final StringRedisTemplate redisTemplate) {
        FilterRegistrationBean<CasServiceCredentialFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CasServiceCredentialFilter(credentialService, properties, redisTemplate));
        registration.addUrlPatterns("/app/*", "/miniapp/*", "/miniapp/*/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
        registration.setName("casServiceCredentialFilter");
        return registration;
    }

    @Bean
    public SmsService smsService(final SmsProperties smsProperties,
                                 final StringRedisTemplate redisTemplate) {
        return new SmsService(smsProperties, redisTemplate);
    }

    @Bean
    public SyncStrategyService syncStrategyService(final CasSyncStrategyMapper syncStrategyMapper) {
        return new SyncStrategyService(syncStrategyMapper);
    }

    @Bean
    public CasServiceAdminService casServiceAdminService(final ServicesManager servicesManager,
                                                         final AppAccessService appAccessService,
                                                         final java.util.List<org.apereo.cas.authentication.AuthenticationHandler> authenticationHandlers) {
        return new CasServiceAdminService(servicesManager, appAccessService, authenticationHandlers);
    }
}
