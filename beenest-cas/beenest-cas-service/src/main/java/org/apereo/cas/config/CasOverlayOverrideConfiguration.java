package org.apereo.cas.config;

import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.beenest.authn.handler.AlipayMiniAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.DouyinMiniAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.SmsOtpAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.UsernamePasswordAuthenticationHandler;
import org.apereo.cas.beenest.authn.handler.WechatMiniAuthenticationHandler;
import org.apereo.cas.beenest.config.MiniAppProperties;
import org.apereo.cas.beenest.config.SmsProperties;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.controller.MiniAppLoginController;
import org.apereo.cas.beenest.controller.PalantirDashboardController;
import org.apereo.cas.beenest.controller.SessionManagementController;
import org.apereo.cas.beenest.controller.SmsController;
import org.apereo.cas.beenest.controller.TokenRefreshController;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.authn.strategy.BeenestAccessStrategy;
import org.apereo.cas.beenest.service.CasNativeLoginService;
import org.apereo.cas.beenest.service.CasTokenLifecycleService;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.service.SmsService;
import cn.binarywang.wx.miniapp.api.WxMaService;
import org.apereo.cas.authentication.AuthenticationEventExecutionPlanConfigurer;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.ticket.factory.DefaultTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.web.cookie.CookieValueManager;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
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
    @ConditionalOnMissingBean(name = "usernamePasswordAuthenticationHandler")
    public AuthenticationHandler usernamePasswordAuthenticationHandler(final PrincipalFactory principalFactory,
                                                                       final UnifiedUserMapper userMapper) {
        return new UsernamePasswordAuthenticationHandler(
            "usernamePasswordAuthenticationHandler",
            principalFactory,
            userMapper);
    }

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

    /**
     * 空壳 Bean，仅触发 ApplicationContext 注入
     */
    public static class BeenestAccessStrategyInitializer {
    }

    @Bean
    public AuthenticationEventExecutionPlanConfigurer beenestAuthnPlan(
        @Qualifier("usernamePasswordAuthenticationHandler") final AuthenticationHandler usernamePasswordAuthenticationHandler,
        @Autowired(required = false) @Qualifier("wechatMiniAuthenticationHandler") final AuthenticationHandler wechatMiniAuthenticationHandler,
        @Autowired(required = false) @Qualifier("douyinMiniAuthenticationHandler") final AuthenticationHandler douyinMiniAuthenticationHandler,
        @Autowired(required = false) @Qualifier("alipayMiniAuthenticationHandler") final AuthenticationHandler alipayMiniAuthenticationHandler,
        @Qualifier("smsOtpAuthenticationHandler") final AuthenticationHandler smsOtpAuthenticationHandler) {
        return plan -> {
            log.info("认证处理器装配状态: password={}, wechat={}, douyin={}, alipay={}, sms={}",
                usernamePasswordAuthenticationHandler != null,
                wechatMiniAuthenticationHandler != null,
                douyinMiniAuthenticationHandler != null,
                alipayMiniAuthenticationHandler != null,
                smsOtpAuthenticationHandler != null);
            plan.registerAuthenticationHandler(usernamePasswordAuthenticationHandler);
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
        };
    }

    // ===== REST 控制器注册 =====

    /**
     * 注册 Token 生命周期服务。
     *
     * @param ticketRegistry       票据仓库
     * @param defaultTicketFactory 默认票据工厂
     * @param redisTemplate        Redis 模板
     * @param userMapper           统一用户映射器
     * @param tokenTtlProperties   Token 生命周期配置
     * @return Token 生命周期服务
     */
    @Bean
    public CasTokenLifecycleService casTokenLifecycleService(
        final TicketRegistry ticketRegistry,
        final DefaultTicketFactory defaultTicketFactory,
        final StringRedisTemplate redisTemplate,
        final UnifiedUserMapper userMapper,
        final TokenTtlProperties tokenTtlProperties,
        final PrincipalFactory principalFactory) {
        return new CasTokenLifecycleService(
            ticketRegistry,
            defaultTicketFactory,
            redisTemplate,
            userMapper,
            tokenTtlProperties,
            principalFactory);
    }

    /**
     * 注册 CAS 原生登录执行服务。
     *
     * @param authenticationSystemSupport 认证系统支持
     * @param tokenLifecycleService       Token 生命周期服务
     * @return 原生登录执行服务
     */
    @Bean
    public CasNativeLoginService casNativeLoginService(
        final AuthenticationSystemSupport authenticationSystemSupport,
        final CasTokenLifecycleService tokenLifecycleService) {
        return new CasNativeLoginService(authenticationSystemSupport, tokenLifecycleService);
    }

    /**
     * 注册小程序登录控制器。
     *
     * @param nativeLoginService    原生登录执行服务
     * @param tokenLifecycleService Token 生命周期服务
     * @return 小程序登录控制器
     */
    @Bean
    public MiniAppLoginController miniAppLoginController(
        final CasNativeLoginService nativeLoginService,
        final CasTokenLifecycleService tokenLifecycleService) {
        return new MiniAppLoginController(
            nativeLoginService,
            tokenLifecycleService);
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
     * 注册 Token 续期控制器。
     *
     * @param nativeLoginService 原生登录执行服务
     * @return Token 续期控制器
     */
    @Bean
    public TokenRefreshController tokenRefreshController(
        final CasNativeLoginService nativeLoginService) {
        return new TokenRefreshController(nativeLoginService);
    }

    /**
     * 注册会话管理控制器（踢人下线）。
     *
     * @param ticketRegistry 票据仓库
     * @return 会话管理控制器
     */
    @Bean
    public SessionManagementController sessionManagementController(
        final TicketRegistry ticketRegistry) {
        return new SessionManagementController(ticketRegistry);
    }

    /**
     * 注册 Palantir 管理控制台。
     *
     * @param casProperties         CAS 配置
     * @param endpointLinksResolver 端点链接解析器
     * @param webEndpointProperties Web 端点配置
     * @param ticketRegistry        票据仓库
     * @param cookieValueManager    cookie管理
     * @return Palantir 管理控制器
     */
    @Bean
    public PalantirDashboardController palantirDashboardController(
        final CasConfigurationProperties casProperties,
        final EndpointLinksResolver endpointLinksResolver,
        final WebEndpointProperties webEndpointProperties,
        final TicketRegistry ticketRegistry,
        final CookieValueManager cookieValueManager) {
        return new PalantirDashboardController(
            casProperties,
            endpointLinksResolver,
            webEndpointProperties,
            ticketRegistry,
            cookieValueManager);
    }

    // ===== API 路径安全：禁用 CSRF =====

    /**
     * 对小程序和短信登录 API 路径禁用 CSRF 保护。
     * <p>
     * 这些端点使用自定义认证机制，无需 CSRF Token。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain casApiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/miniapp/**", "/refresh", "/sms/**")
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .build();
    }

    /**
     * 本地 Palantir 管理页直接放行，让同应用 dashboard 能被主登录态访问。
     *
     * @param http HTTP 安全对象
     * @return Palantir 安全链
     * @throws Exception 配置失败时抛出
     */
    @Bean
    @Order(0)
    public SecurityFilterChain casPalantirSecurityFilterChain(final HttpSecurity http) throws Exception {
        return http
            .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher("/palantir/**"))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .build();
    }
}
