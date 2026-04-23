package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apereo.cas.beenest.service.SmsService;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.service.UserSyncService;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Beenest 业务服务注册。
 * <p>
 * 显式注册服务 bean，避免依赖组件扫描顺序。
 */
@AutoConfiguration
public class BeenestServiceConfiguration {

    @Bean
    public UserSyncService userSyncService() {
        return new UserSyncService();
    }

    @Bean
    public AppAccessService appAccessService() {
        return new AppAccessService();
    }

    @Bean
    public UserIdentityService userIdentityService(final UnifiedUserMapper userMapper,
                                                   final UserSyncService userSyncService,
                                                   @Value("${beenest.user.auto-grant-service-ids:10001}") final String autoGrantServiceIds,
                                                   final AppAccessService appAccessService) {
        return new UserIdentityService(userMapper, userSyncService, appAccessService, autoGrantServiceIds);
    }

    @Bean
    public AuthAuditService authAuditService() {
        return new AuthAuditService();
    }

    @Bean
    public SmsService smsService(final SmsProperties smsProperties,
                                 final StringRedisTemplate redisTemplate) {
        return new SmsService(smsProperties, redisTemplate);
    }
}
