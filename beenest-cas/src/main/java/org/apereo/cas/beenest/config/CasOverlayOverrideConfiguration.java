package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.authn.handler.AlipayMiniAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.AppTokenAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.DouyinMiniAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.SmsOtpAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.WechatMiniAuthenticationHandler;
import org.apereo.cas.beenest.controller.AppLoginController;
import org.apereo.cas.beenest.controller.CasServiceAdminController;
import org.apereo.cas.beenest.controller.CasUserAdminController;
import org.apereo.cas.beenest.controller.MiniAppLoginController;
import org.apereo.cas.beenest.controller.SmsController;
import org.apereo.cas.beenest.controller.SyncStrategyController;
import org.apereo.cas.beenest.controller.TokenValidationController;
import org.apereo.cas.beenest.controller.UserSyncController;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.authn.strategy.BeenestAccessStrategy;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.beenest.service.CasServiceAdminService;
import org.apereo.cas.beenest.service.SmsService;
import org.apereo.cas.beenest.service.SyncStrategyService;
import org.apereo.cas.beenest.service.UserAdminService;
import org.apereo.cas.beenest.service.UserSyncService;
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
 * Beenest CAS 覆盖配置入口。
 * <p>
 * 这里按照 CAS overlay 的官方约定集中注册自定义 bean，
 * 避免把自定义认证处理器和控制器分散在多个自动配置类里。
 */
@AutoConfiguration
@EnableConfigurationProperties({
        CasConfigurationProperties.class,
        MiniAppProperties.class,
        SmsProperties.class,
        TokenTtlProperties.class
})
public class CasOverlayOverrideConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CasOverlayOverrideConfiguration.class);

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

    // ===== REST 控制器注册 =====

    /**
     * 注册小程序登录控制器。
     *
     * @param authenticationSystemSupport 认证系统支持
     * @param ticketRegistry 票据仓库
     * @param defaultTicketFactory 默认票据工厂
     * @param auditService 审计服务
     * @param appAccessService 应用访问控制服务
     * @param redisTemplate Redis 模板
     * @param tokenTtlProperties Token 生命周期配置
     * @return 小程序登录控制器
     */
    @Bean
    public MiniAppLoginController miniAppLoginController(
            final org.apereo.cas.authentication.AuthenticationSystemSupport authenticationSystemSupport,
            final org.apereo.cas.ticket.registry.TicketRegistry ticketRegistry,
            final org.apereo.cas.ticket.factory.DefaultTicketFactory defaultTicketFactory,
            final AuthAuditService auditService,
            final AppAccessService appAccessService,
            final StringRedisTemplate redisTemplate,
            final TokenTtlProperties tokenTtlProperties) {
        return new MiniAppLoginController(
                authenticationSystemSupport,
                ticketRegistry,
                defaultTicketFactory,
                auditService,
                appAccessService,
                redisTemplate,
                tokenTtlProperties);
    }

    /**
     * 注册 APP 登录控制器。
     *
     * @param authenticationSystemSupport 认证系统支持
     * @param ticketRegistry 票据仓库
     * @param defaultTicketFactory 默认票据工厂
     * @param redisTemplate Redis 模板
     * @param auditService 审计服务
     * @param appAccessService 应用访问控制服务
     * @param tokenTtlProperties Token 生命周期配置
     * @return APP 登录控制器
     */
    @Bean
    public AppLoginController appLoginController(
            final org.apereo.cas.authentication.AuthenticationSystemSupport authenticationSystemSupport,
            final org.apereo.cas.ticket.registry.TicketRegistry ticketRegistry,
            final org.apereo.cas.ticket.factory.DefaultTicketFactory defaultTicketFactory,
            final StringRedisTemplate redisTemplate,
            final AuthAuditService auditService,
            final AppAccessService appAccessService,
            final TokenTtlProperties tokenTtlProperties) {
        return new AppLoginController(
                authenticationSystemSupport,
                ticketRegistry,
                defaultTicketFactory,
                redisTemplate,
                auditService,
                appAccessService,
                tokenTtlProperties);
    }

    /**
     * 注册 Token 校验控制器。
     *
     * @param ticketRegistry 票据仓库
     * @return Token 校验控制器
     */
    @Bean
    public TokenValidationController tokenValidationController(
            final org.apereo.cas.ticket.registry.TicketRegistry ticketRegistry) {
        return new TokenValidationController(ticketRegistry);
    }

    /**
     * 注册用户同步控制器。
     *
     * @param userMapper 用户映射器
     * @param userSyncService 用户同步服务
     * @param redisTemplate Redis 模板
     * @return 用户同步控制器
     */
    @Bean
    public UserSyncController userSyncController(
            final UnifiedUserMapper userMapper,
            final UserSyncService userSyncService,
            final StringRedisTemplate redisTemplate) {
        return new UserSyncController(userMapper, userSyncService, redisTemplate);
    }

    /**
     * 注册用户管理控制器。
     *
     * @param userAdminService 用户管理服务
     * @param appAccessService 应用访问控制服务
     * @return 用户管理控制器
     */
    @Bean
    public CasUserAdminController casUserAdminController(
            final UserAdminService userAdminService,
            final AppAccessService appAccessService) {
        return new CasUserAdminController(userAdminService, appAccessService);
    }

    /**
     * 注册服务管理控制器。
     *
     * @param serviceAdminService 服务管理服务
     * @return 服务管理控制器
     */
    @Bean
    public CasServiceAdminController casServiceAdminController(
            final CasServiceAdminService serviceAdminService) {
        return new CasServiceAdminController(serviceAdminService);
    }

    /**
     * 注册短信控制器。
     *
     * @param smsService 短信服务
     * @return 短信控制器
     */
    @Bean
    public SmsController smsController(final SmsService smsService) {
        return new SmsController(smsService);
    }

    /**
     * 注册同步策略控制器。
     *
     * @param syncStrategyService 同步策略服务
     * @return 同步策略控制器
     */
    @Bean
    public SyncStrategyController syncStrategyController(
            final SyncStrategyService syncStrategyService) {
        return new SyncStrategyController(syncStrategyService);
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
                .securityMatcher("/miniapp/**", "/app/**", "/token/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
