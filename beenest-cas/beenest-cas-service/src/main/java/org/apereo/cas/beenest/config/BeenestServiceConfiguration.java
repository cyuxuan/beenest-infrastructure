package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.BeenestAccountRegistrationProvisioner;
import org.apereo.cas.beenest.service.BeenestAccountRegistrationRequestValidator;
import org.apereo.cas.beenest.service.SmsService;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.sms.AliyunSmsSender;
import org.apereo.cas.acct.AccountRegistrationRequestValidator;
import org.apereo.cas.acct.provision.AccountRegistrationProvisionerConfigurer;
import org.apereo.cas.notifications.sms.SmsSender;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Beenest 业务服务注册。
 * <p>
 * 显式注册服务 bean，避免依赖组件扫描顺序。
 */
@AutoConfiguration(beforeName = "org.apereo.cas.config.CasAccountManagementWebflowAutoConfiguration")
@EnableConfigurationProperties(AutoGrantProperties.class)
public class BeenestServiceConfiguration {

    @Bean
    public UserIdentityService userIdentityService(final UnifiedUserMapper userMapper,
                                                    final AutoGrantProperties autoGrantProperties) {
        return new UserIdentityService(userMapper, autoGrantProperties);
    }

    /**
     * 注册 CAS 原生账号开户注册校验器。
     * <p>
     * 该 Bean 名称与 CAS 原生自动配置约定保持一致，用于替换默认 no-op 校验器。
     *
     * @param userMapper 统一用户 Mapper
     * @return 注册请求校验器
     */
    @Bean("accountMgmtRegistrationRequestValidator")
    public AccountRegistrationRequestValidator accountMgmtRegistrationRequestValidator(
            final UnifiedUserMapper userMapper) {
        return new BeenestAccountRegistrationRequestValidator(userMapper, false);
    }

    /**
     * 注册 CAS 原生账号开户注册落库器配置。
     *
     * @param userMapper 统一用户 Mapper
     * @param autoGrantProperties 自动赋权配置
     * @return 原生开户注册器配置
     */
    @Bean
    public AccountRegistrationProvisionerConfigurer beenestAccountRegistrationProvisionerConfigurer(
            final UnifiedUserMapper userMapper,
            final AutoGrantProperties autoGrantProperties) {
        return () -> new BeenestAccountRegistrationProvisioner(userMapper, autoGrantProperties);
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
