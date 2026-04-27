package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.beenest.service.SmsService;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.sms.AliyunSmsSender;
import org.apereo.cas.notifications.sms.SmsSender;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Beenest 业务服务注册。
 * <p>
 * 显式注册服务 bean，避免依赖组件扫描顺序。
 */
@AutoConfiguration
public class BeenestServiceConfiguration {

    @Bean
    public AppAccessService appAccessService() {
        return new AppAccessService();
    }

    @Bean
    public UserIdentityService userIdentityService(final UnifiedUserMapper userMapper) {
        return new UserIdentityService(userMapper);
    }

    @Bean
    public SmsService smsService(final SmsProperties smsProperties,
                                 final StringRedisTemplate redisTemplate) {
        return new SmsService(smsProperties, redisTemplate);
    }

    /**
     * CAS 原生 SMS Sender Bean（阿里云实现）。
     * <p>
     * CAS 密码重置、MFA、通知等模块通过此 Bean 发送短信。
     * Bean 名称必须是 "smsSender"，这是 CAS 框架约定。
     */
    @Bean(SmsSender.BEAN_NAME)
    public SmsSender smsSender(final SmsProperties smsProperties) {
        return new AliyunSmsSender(
            smsProperties.getAccessKey(),
            smsProperties.getSecretKey(),
            smsProperties.getSignName(),
            smsProperties.getTemplateCode());
    }
}
