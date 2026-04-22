package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.authn.handler.AlipayMiniAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.AppTokenAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.DouyinMiniAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.SmsOtpAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.WechatMiniAuthenticationHandler;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.authn.strategy.BeenestAccessStrategy;
import org.apereo.cas.beenest.service.UserIdentityService;
import cn.binarywang.wx.miniapp.api.WxMaService;
import org.apereo.cas.authentication.AuthenticationEventExecutionPlanConfigurer;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Beenest CAS 认证配置
 * <p>
 * 注册所有自定义 AuthenticationHandler 到 Apereo CAS 认证引擎。
 * 每种认证方式对应一个 Handler 和一个 Credential 类型。
 */
@AutoConfiguration
@EnableConfigurationProperties({
        CasConfigurationProperties.class,
        MiniAppProperties.class,
        SmsProperties.class,
        TokenTtlProperties.class
})
public class BeenestCasAuthenticationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BeenestCasAuthenticationConfiguration.class);

    // ===== 微信小程序 Handler =====

    @Bean
    @ConditionalOnMissingBean(name = "wechatMiniAuthenticationHandler")
    @ConditionalOnProperty(prefix = "beenest.miniapp.wechat", name = "appid")
    public AuthenticationHandler wechatMiniAuthenticationHandler(
            final WxMaService wxMaService,
            final PrincipalFactory principalFactory,
            final UserIdentityService userIdentityService) {
        return new WechatMiniAuthenticationHandler(
                "wechatMiniAuthenticationHandler",
                principalFactory, wxMaService, userIdentityService);
    }

    // ===== 抖音小程序 Handler =====

    @Bean
    @ConditionalOnMissingBean(name = "douyinMiniAuthenticationHandler")
    public AuthenticationHandler douyinMiniAuthenticationHandler(final PrincipalFactory principalFactory,
            final MiniAppProperties miniAppProperties,
            final UserIdentityService userIdentityService) {
        return new DouyinMiniAuthenticationHandler(
                "douyinMiniAuthenticationHandler",
                principalFactory, miniAppProperties, userIdentityService);
    }

    // ===== 支付宝小程序 Handler =====

    @Bean
    @ConditionalOnMissingBean(name = "alipayMiniAuthenticationHandler")
    public AuthenticationHandler alipayMiniAuthenticationHandler(final PrincipalFactory principalFactory,
            final MiniAppProperties miniAppProperties,
            final UserIdentityService userIdentityService) {
        return new AlipayMiniAuthenticationHandler(
                "alipayMiniAuthenticationHandler",
                principalFactory, miniAppProperties, userIdentityService);
    }

    // ===== 短信验证码 Handler =====

    @Bean
    @ConditionalOnMissingBean(name = "smsOtpAuthenticationHandler")
    public AuthenticationHandler smsOtpAuthenticationHandler(final PrincipalFactory principalFactory,
            final StringRedisTemplate redisTemplate,
            final UserIdentityService userIdentityService) {
        return new SmsOtpAuthenticationHandler(
                "smsOtpAuthenticationHandler",
                principalFactory, redisTemplate, userIdentityService);
    }

    // ===== APP Token Handler =====

    @Bean
    @ConditionalOnMissingBean(name = "appTokenAuthenticationHandler")
    public AuthenticationHandler appTokenAuthenticationHandler(final PrincipalFactory principalFactory,
            final ObjectProvider<UnifiedUserMapper> userMapperProvider,
            final StringRedisTemplate redisTemplate) {
        return new AppTokenAuthenticationHandler(
                "appTokenAuthenticationHandler",
                principalFactory, userMapperProvider.getObject(), redisTemplate);
    }

    // ===== 注册到 CAS 认证引擎 =====

    /**
     * 初始化 BeenestAccessStrategy 的 ApplicationContext
     * <p>
     * BeenestAccessStrategy 通过 ApplicationContext 延迟获取 AppAccessService，
     * 解决 JPA 反序列化 Service JSON 时 static Bean 注入不可靠的问题。
     */
    @Bean
    public BeenestAccessStrategyInitializer beenestAccessStrategyInitializer(
            final ApplicationContext applicationContext) {
        BeenestAccessStrategy.setApplicationContext(applicationContext);
        return new BeenestAccessStrategyInitializer();
    }

    /** 空壳 Bean，仅触发 ApplicationContext 注入 */
    public static class BeenestAccessStrategyInitializer {
    }

    @Bean
    public AuthenticationEventExecutionPlanConfigurer beenestAuthnPlan(
            @Autowired(required = false) @Qualifier("wechatMiniAuthenticationHandler") final AuthenticationHandler wechatMiniAuthenticationHandler,
            @Autowired(required = false) @Qualifier("douyinMiniAuthenticationHandler") final AuthenticationHandler douyinMiniAuthenticationHandler,
            @Autowired(required = false) @Qualifier("alipayMiniAuthenticationHandler") final AuthenticationHandler alipayMiniAuthenticationHandler,
            @Qualifier("smsOtpAuthenticationHandler") final AuthenticationHandler smsOtpAuthenticationHandler,
            @Qualifier("appTokenAuthenticationHandler") final AuthenticationHandler appTokenAuthenticationHandler) {
        return plan -> {
            log.info("认证处理器装配状态: wechat={}, douyin={}, alipay={}, sms={}, appToken={}",
                    wechatMiniAuthenticationHandler != null,
                    douyinMiniAuthenticationHandler != null,
                    alipayMiniAuthenticationHandler != null,
                    smsOtpAuthenticationHandler != null,
                    appTokenAuthenticationHandler != null);
            if (wechatMiniAuthenticationHandler != null) {
                plan.registerAuthenticationHandler(wechatMiniAuthenticationHandler);
            }
            if (douyinMiniAuthenticationHandler != null) {
                plan.registerAuthenticationHandler(douyinMiniAuthenticationHandler);
            }
            if (alipayMiniAuthenticationHandler != null) {
                plan.registerAuthenticationHandler(alipayMiniAuthenticationHandler);
            }
            plan.registerAuthenticationHandler(smsOtpAuthenticationHandler);
            plan.registerAuthenticationHandler(appTokenAuthenticationHandler);
        };
    }

    // ===== API 路径安全：禁用 CSRF =====

    /**
     * 对小程序/APP 登录 API 路径禁用 CSRF 保护。
     * <p>
     * 这些端点通过 {@link org.apereo.cas.beenest.filter.CasServiceCredentialFilter}
     * 进行 HMAC 签名校验来保证安全性，无需 CSRF Token。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain casApiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/miniapp/**", "/app/**", "/token/**", "/refresh")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
