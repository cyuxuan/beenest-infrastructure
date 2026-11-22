package club.beenest.iam.authz.config;

import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.ClientAdmissionFilter;
import club.beenest.iam.authz.authentication.IamAuthenticationProvider;
import club.beenest.iam.authz.authentication.step.AuthenticationStepChain;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.authorization.ApplicationAccessRefreshProvider;
import club.beenest.iam.authz.authorization.ApplicationVisibilityService;
import club.beenest.iam.authz.authorization.ClientApplicationAccessFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import club.beenest.iam.authz.jwks.RealmJwkSource;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.tenant.RealmContextFilter;
import club.beenest.iam.authz.token.IamJwtAuthenticationConverter;
import club.beenest.iam.authz.token.IamJwtDecoder;
import club.beenest.iam.authz.token.IamPlatformApiAuthorizationManager;
import club.beenest.iam.authz.token.IamTokenBlocklistValidator;
import club.beenest.iam.authz.token.IamTokenTypeValidator;
import club.beenest.iam.authz.token.IamTokenVersionValidator;
import club.beenest.iam.authz.token.TokenStateCache;
import club.beenest.iam.authz.web.BearerTokenRequestMatcher;
import club.beenest.iam.authz.web.IamApiAccessDeniedHandler;
import club.beenest.iam.authz.web.IamApiAuthenticationEntryPoint;
import club.beenest.iam.authz.web.IamAuthenticationEntryPoint;
import club.beenest.iam.authz.web.IamAuthenticationFailureHandler;
import club.beenest.iam.authz.web.IamAuthenticationSuccessHandler;
import club.beenest.iam.authz.web.IamBearerEntryPoint;
import club.beenest.iam.authz.web.IamLogoutSidCaptureFilter;
import club.beenest.iam.authz.web.IamLogoutSuccessHandler;
import club.beenest.iam.authz.web.IamOidcLogoutHandler;
import club.beenest.iam.authz.web.IamOriginGuardFilter;
import club.beenest.iam.authz.web.IamPageAwareAuthenticationEntryPoint;
import club.beenest.iam.authz.web.IamRedisSecurityContextRepository;
import club.beenest.iam.authz.web.IamSessionGuardFilter;
import club.beenest.iam.authz.web.IamRequiredActionFilter;
import club.beenest.iam.authz.web.OriginAuthorities;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import club.beenest.iam.common.web.IamOneTimeMessageService;
import club.beenest.iam.common.web.IamTraceIdFilter;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安全配置:协议链与业务链。
 * <p>协议链承载全部 OAuth2/OIDC 端点,issuer 按请求路径中的域标识解析,各域独立签发与验签;
 * 业务链承载登录页、注销与 /api/v1 接口。两条链共用 Redis 会话存储,浏览器与前后端分离的前端均凭会话 Cookie 复用登录态。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class IamSecurityConfiguration {

    /** 日志:跨源拒绝时记录请求头实况,便于定位"自家页面被判成跨源"这类部署问题 */
    private static final Logger log = LoggerFactory.getLogger(IamSecurityConfiguration.class);

    /** REST 接口路径前缀,用于区分返回 JSON 与跳转登录页两种未认证表现 */
    private static final String API_PREFIX = "/api/v1/**";

    /**
     * 协议链:授权、令牌、JWKS、用户信息端点与发现文档。
     *
     * @param http                       安全构建器
     * @param realmContextFilter         身份域上下文过滤器
     * @param securityContextRepository  会话安全上下文仓库
     * @param corsConfigurationSource    跨源策略
     * @param jwtAuthenticationConverter 令牌认证转换器
     * @param jwtDecoder                 域感知 JWT 解码器
     * @param logoutService              注销服务
     * @param traceIdFilter              链路标识过滤器
     * @param sessionGuardFilter         会话校验过滤器
     * @param requiredActionFilter       待办拦截过滤器
     * @param registeredClientRepository 注册客户端仓库
     * @param realmProvider              身份域读取
     * @param admissionChallengeService  准入挑战服务
     * @param mfaPendingService          二步验证待办票据服务
     * @param loginEventPublisher        登录事件发布器
     * @param appVisibilityService       应用可见性判定
     * @param refreshProvider            刷新令牌应用访问闸门
     * @param enforceApplicationVisibility 是否在授权端点强制应用可见性
     * @return 协议链
     * @throws Exception 过滤器链构建失败
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http,
                                                              RealmContextFilter realmContextFilter,
                                                              SecurityContextRepository securityContextRepository,
                                                              CorsConfigurationSource corsConfigurationSource,
                                                              IamJwtAuthenticationConverter jwtAuthenticationConverter,
                                                              JwtDecoder jwtDecoder,
                                                              IamLogoutService logoutService,
                                                              IamTraceIdFilter traceIdFilter,
                                                              IamSessionGuardFilter sessionGuardFilter,
                                                              IamRequiredActionFilter requiredActionFilter,
                                                              RegisteredClientRepository registeredClientRepository,
                                                              RealmProvider realmProvider,
                                                              AdmissionChallengeService admissionChallengeService,
                                                              MfaPendingService mfaPendingService,
                                                              LoginEventPublisher loginEventPublisher,
                                                              ApplicationVisibilityService appVisibilityService,
                                                              ApplicationAccessRefreshProvider refreshProvider,
                                                              @Value("${iam.authorization.enforce-application-visibility:true}")
                                                              boolean enforceApplicationVisibility)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

        // 1. 本链只匹配协议端点,其余请求交给业务链
        http.securityMatcher(endpointsMatcher)
                // 1.1 补充安全响应头:Referrer 只在本站内传递,跨源一律不带;
                //     关闭用不到的高敏浏览器能力
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()")))
                // 2. 协议端点注册全部 OAuth2/OIDC 能力,域上下文最先执行
                .addFilterBefore(realmContextFilter, AuthorizationFilter.class)
                // 2.1 链路标识:先于一切业务日志
                .addFilterBefore(traceIdFilter, AuthorizationFilter.class)
                // 2.2 会话校验:协议端点同样以会话承载登录态,
                //     账号被禁用或权限被撤销时,授权端点立即拒绝
                .addFilterAfter(sessionGuardFilter, SecurityContextHolderFilter.class)
                // 2.3 待办拦截:授权端点同样拦截,待办未完成时不能换取令牌
                .addFilterAfter(requiredActionFilter, IamSessionGuardFilter.class)
                // 2.4 客户端准入判定:排在域上下文之后(查客户端依赖域)、
                //     授权端点之前(授权成败在此判定);位置错误时过滤器照常注册并启动,
                //     只是不会被执行
                .addFilterAfter(new ClientAdmissionFilter(registeredClientRepository, realmProvider,
                        admissionChallengeService, mfaPendingService, loginEventPublisher),
                        RealmContextFilter.class)
                // 2.5 应用授权闸门:客户端绑定了应用服务时,用户必须对该应用可见;
                //     排在准入判定之后,补验通过的请求重新进入授权端点时会再次经过本过滤器
                .addFilterAfter(new ClientApplicationAccessFilter(registeredClientRepository,
                        realmProvider, appVisibilityService, loginEventPublisher,
                        enforceApplicationVisibility),
                        ClientAdmissionFilter.class)
                .with(authorizationServerConfigurer, authorizationServer -> authorizationServer
                        // 同意页:客户端开启 require_consent 且用户未同意时重定向到主题页,
                        // 由该页展示 scope 并回传,协议栈负责写入同意记录
                        .authorizationEndpoint(endpoint -> endpoint.consentPage("/consent"))
                        // 刷新令牌同样要复核应用可见性:首次授权后收回的访问不能靠刷新续期
                        .tokenEndpoint(token -> token.authenticationProvider(refreshProvider))
                        // OIDC 登出端点:默认处理器负责校验 post_logout_redirect_uri 并回跳,
                        // 这里替换登出动作,结束 IAM 会话并向其它应用广播
                        .oidc(oidc -> oidc.logoutEndpoint(logout -> {
                            OidcLogoutAuthenticationSuccessHandler handler =
                                    new OidcLogoutAuthenticationSuccessHandler();
                            handler.setLogoutHandler(new IamOidcLogoutHandler(logoutService));
                            logout.logoutResponseHandler(handler);
                        })))
                // 3. 全部协议端点要求已认证(浏览器会话)或携带有效 Bearer 令牌
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // 4. 未登录的授权请求跳登录页,透传域与回跳地址
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new IamAuthenticationEntryPoint()))
                // 5. userinfo 等端点支持 Bearer 访问令牌访问
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(new IamBearerEntryPoint())
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // 6. 令牌端点可能被浏览器端直接调用,需允许跨源
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // 7. 登录态从共享会话读取(与业务链同源)
                .securityContext(context -> context.securityContextRepository(securityContextRepository));
        return http.build();
    }

    /**
     * 业务链:登录页、注销、/api/v1 接口与静态资源。
     *
     * @param http                           安全构建器
     * @param realmContextFilter             身份域上下文过滤器
     * @param securityContextRepository      会话安全上下文仓库
     * @param userSessionService             用户会话服务
     * @param loginSessionService            登录会话服务
     * @param logoutService                  注销服务
     * @param loginEventPublisher            登录事件发布器
     * @param apiAuthenticationEntryPoint    REST 接口未认证入口点
     * @param apiAccessDeniedHandler         REST 接口无权限处理器
     * @param corsConfigurationSource        跨源策略
     * @param jwtAuthenticationConverter     令牌认证转换器
     * @param jwtDecoder                     域感知 JWT 解码器
     * @param mfaPendingService              二步验证待办票据服务
     * @param oneTimeMessageService          一次性消息服务
     * @param sessionGuardFilter             会话校验过滤器
     * @param requiredActionFilter           待办拦截过滤器
     * @param traceIdFilter                  链路标识过滤器
     * @param platformApiAuthorizationManager IAM 自身接口的令牌类别判定
     * @param bearerTokenRequestMatcher       Bearer 请求匹配器(验签通过才豁免 CSRF)
     * @param originGuardFilter               来源校验过滤器
     * @return 业务链
     * @throws Exception 过滤器链构建失败
     */
    @Bean
    @Order(2)
    public SecurityFilterChain businessFilterChain(HttpSecurity http,
                                                   RealmContextFilter realmContextFilter,
                                                   SecurityContextRepository securityContextRepository,
                                                   IamUserSessionService userSessionService,
                                                   IamLoginSessionService loginSessionService,
                                                   IamLogoutService logoutService,
                                                   LoginEventPublisher loginEventPublisher,
                                                   IamApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
                                                   IamApiAccessDeniedHandler apiAccessDeniedHandler,
                                                   CorsConfigurationSource corsConfigurationSource,
                                                   IamJwtAuthenticationConverter jwtAuthenticationConverter,
                                                   JwtDecoder jwtDecoder,
                                                   MfaPendingService mfaPendingService,
                                                   IamOneTimeMessageService oneTimeMessageService,
                                                   IamSessionGuardFilter sessionGuardFilter,
                                                   IamRequiredActionFilter requiredActionFilter,
                                                   IamTraceIdFilter traceIdFilter,
                                                   IamPlatformApiAuthorizationManager platformApiAuthorizationManager,
                                                   BearerTokenRequestMatcher bearerTokenRequestMatcher,
                                                   IamOriginGuardFilter originGuardFilter,
                                                   @Value("${iam.session.cookie-secure:true}") boolean cookieSecure)
            throws Exception {
        http
                // 1 补充安全响应头:Referrer 只在本站内传递,跨源一律不带;
                //   不能下发 no-referrer —— 浏览器对表单提交的 Origin 由 referrer 推导,
                //   referrer 被清空后 Origin 会退化成字面量 null(不透明来源),
                //   服务端再也拿不到真实来源,登录请求会被判成跨源
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()")))
                // 1. 公开资源:登录页/MFA页/错误页/静态资源/健康检查/登录接口
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/login-mfa", "/login-challenge", "/error").permitAll()
                        // 找回口令:用户正是在"登不进去"的时候用它,必须未登录可达
                        .requestMatchers("/forgot-password", "/reset-password", "/verify-email").permitAll()
                        // 补验页可能被跨站联邦回跳链路凭令牌打开(那时没有会话 Cookie),
                        // 不能要求已认证;能否真正升级会话由票据与会话绑定关系决定
                        // actuator 跑在独立管理端口(management.server.port),
                        // 业务端口上并不存在这些端点,放行不影响对外暴露面;
                        // 管理端口本身必须由网络策略限制在运维网段内
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/theme-static/**", "/console-static/**", "/portal-static/**",
                                "/assets/**", "/css/**", "/js/**", "/themes/**").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/login-mfa",
                                "/api/v1/auth/provider-code",
                                "/api/v1/auth/captcha", "/api/v1/auth/captcha-required",
                                "/api/v1/auth/idps",
                                // 短信验证码登录:发码与登录都必须在未登录状态下可达
                                "/api/v1/auth/sms-code", "/api/v1/auth/login-sms",
                                // 找回口令:与登录同为首屏能力,未登录、无会话
                                "/api/v1/auth/password-reset/request",
                                "/api/v1/auth/password-reset/confirm",
                                // Passkey 登录:挑战与断言提交同样发生在未登录状态
                                "/api/v1/auth/webauthn/options",
                                "/api/v1/auth/webauthn/authenticate").permitAll()
                        // 三方登录:发起与回调都要在未登录状态下可达
                        .requestMatchers("/broker/**").permitAll()
                        // SAML 身份提供方端点:元数据供 SP 匿名拉取,单点登录与单点登出
                        // 由 SP 侧发起,用户此时可能尚无本平台会话;能否签发断言由端点自行判定
                        .requestMatchers("/realms/*/saml/**").permitAll()
                        // OpenAPI 文档:接入方联调入口。生产可用 springdoc.api-docs.enabled=false
                        // 整组关闭(端点随之 404);内网联调环境可按需开启
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // 1.1 IAM 自身接口只接受浏览器会话或平台类令牌:
                        //     业务应用令牌的授权范围是该应用的权限命名空间,不该反过来
                        //     调用管理端/自助端/门户接口(LEGACY_GLOBAL 档令牌仍带权限全集)
                        .requestMatchers("/api/v1/admin/**", "/api/v1/console/**", "/api/v1/launchpad/**")
                        .access(platformApiAuthorizationManager)
                        // SCIM 同样是管理面:业务应用令牌带 scim:manage 也不得借此读写用户与组
                        .requestMatchers("/scim/v2/**").access(platformApiAuthorizationManager)
                        // 2. 其余全部要求已认证;权限粒度交给各端点的方法级注解
                        .anyRequest().authenticated())
                // 3. 表单登录:登记会话记录后按回跳地址跳转;需要 MFA 时跳二步验证页
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(new IamAuthenticationSuccessHandler(loginSessionService))
                        .failureHandler(new IamAuthenticationFailureHandler(mfaPendingService, oneTimeMessageService)))
                // 4. 注销:结束会话、广播单点登出后回登录页。
                //    sid 捕获过滤器排在 LogoutFilter 之前:注销链路会先失效容器会话
                .addFilterBefore(new IamLogoutSidCaptureFilter(
                                PathPatternRequestMatcher.pathPattern("/logout")),
                        LogoutFilter.class)
                .logout(logout -> logout
                        .logoutSuccessHandler(new IamLogoutSuccessHandler(logoutService)))
                // 5. 未认证:接口返回 JSON,页面跳登录页;无权限:接口返回 403 JSON
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(new IamPageAwareAuthenticationEntryPoint(
                                apiAuthenticationEntryPoint, new IamAuthenticationEntryPoint()))
                        .defaultAccessDeniedHandlerFor(apiAccessDeniedHandler,
                                PathPatternRequestMatcher.pathPattern(API_PREFIX)))
                // 6. 同时接受浏览器会话与 Bearer 令牌:
                //    浏览器前端凭 Cookie 调用接口,业务系统/脚本凭令牌调用,两条路径互不干扰
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // 7. CSRF:令牌写入可读 Cookie 供前端回传。
                //    显式携带 Bearer 的请求不依赖浏览器自动附带凭据,天然免疫 CSRF;
                //    登录接口本身不使用环境凭据(账号口令/一次性票据均由用户显式提交),
                //    一并豁免以免妨碍首屏登录与二步验证
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository(cookieSecure))
                        .csrfTokenRequestHandler(csrfTokenRequestHandler())
                        .ignoringRequestMatchers(bearerTokenRequestMatcher,
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/login"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/login-mfa"),
                                // 短信验证码登录:与账号口令登录同为首屏接口(未登录、无会话、
                                // 无任何环境凭据),手机号与验证码均由用户显式提交,一并豁免
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/sms-code"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/login-sms"),
                                // 找回口令:同样无环境凭据,账号与票据均由用户显式提交
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/password-reset/request"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/password-reset/confirm"),
                                // Passkey 登录:断言由浏览器按 WebAuthn 规范生成,
                                // 防重放由一次性挑战承担,不依赖 CSRF 令牌
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/webauthn/options"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/webauthn/authenticate"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/provider-code"),
                                // 三方登录回调:SAML 的断言由外部 IdP 以表单 POST 到 ACS,
                                // 浏览器在这里只是转交,拿不到本站 CSRF 令牌;防伪由一次性
                                // RelayState(state)与签名断言承担,见 IamFederationController
                                PathPatternRequestMatcher.pathPattern("/broker/**"),
                                // SAML IdP 端点:SP 的 AuthnRequest / LogoutRequest 由 SP 侧
                                // 构造并 POST,浏览器只是转交,同样拿不到 CSRF 令牌;
                                // 防伪由签名校验与请求 ID 的一次性消费承担
                                PathPatternRequestMatcher.pathPattern("/realms/*/saml/**")))
                // 7.1 来源校验:状态变更请求必须来自本站或已登记的前端来源。
                //     登录类接口按设计豁免 CSRF 令牌,登录 CSRF 由这一层拦住
                .addFilterBefore(originGuardFilter, CsrfFilter.class)
                // 8. 跨源访问:前后端分离部署时前端域名与身份服务不同源
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // 9. 登录态读写共享会话仓库(Bearer 请求不使用会话)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                // 10. 域上下文最先执行
                .addFilterBefore(realmContextFilter, AuthorizationFilter.class)
                // 10.1 链路标识:先于一切业务日志
                .addFilterBefore(traceIdFilter, AuthorizationFilter.class)
                // 11. 会话校验:复核账号状态与权限代次。
                //     排在安全上下文之后,校验对象取自上下文中的主体;
                //     作废会话时清空上下文,后续判定按未登录处理
                .addFilterAfter(sessionGuardFilter, SecurityContextHolderFilter.class)
                // 12. 待办拦截:排在会话守卫之后,待办状态取自会话快照
                .addFilterAfter(requiredActionFilter, IamSessionGuardFilter.class);
        return http.build();
    }

    /**
     * CSRF 令牌请求处理器。
     * <p>令牌默认延迟加载,首个响应不会写出 Cookie;请求属性名置空后强制在响应提交前加载。</p>
     *
     * @return CSRF 令牌请求处理器
     */
    private static CsrfTokenRequestHandler csrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * CSRF 令牌 Cookie 仓库。
     * <p>令牌需被前端脚本读取后回传,故不设 HttpOnly;Secure 与站点 Cookie 同口径,
     * SameSite=Lax 阻止跨站请求顺带携带该令牌。</p>
     *
     * @param cookieSecure 是否仅通过 HTTPS 传输
     * @return CSRF 令牌 Cookie 仓库
     */
    private static CookieCsrfTokenRepository csrfTokenRepository(boolean cookieSecure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/"));
        return repository;
    }

    /**
     * 跨源访问策略:配置项逐条放行,本站来源始终放行。
     * <p>本站判定与来源校验过滤器同口径(见 {@link OriginAuthorities}),避免
     * "过滤器放行、CORS 拒绝"的自相矛盾;不透明来源({@code Origin: null})不放行。</p>
     *
     * @param allowedOrigins 允许的来源,多个以逗号分隔,可为空
     * @return 跨源配置源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${iam.cors.allowed-origins:}") String allowedOrigins) {
        Set<String> whitelist = new LinkedHashSet<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
                if (!origin.isBlank()) {
                    whitelist.add(origin.trim());
                }
            }
        }
        return request -> {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin == null || origin.isBlank()) {
                // 非跨源请求:交给后续链,不写任何 CORS 响应头
                return null;
            }
            CorsConfiguration configuration = new CorsConfiguration();
            boolean sameSite = OriginAuthorities.isSameSite(request, origin);
            if (sameSite) {
                configuration.addAllowedOrigin(origin);
            }
            whitelist.forEach(configuration::addAllowedOrigin);
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("*"));
            configuration.setAllowCredentials(true);
            configuration.setMaxAge(Duration.ofHours(1));
            if (!sameSite && !whitelist.contains(origin)) {
                // 拒绝时把判定依据一并打出:运维只看得到浏览器的 403,
                // 而真实原因往往藏在 Host 与 Origin 的差异里
                log.warn("跨源请求被拒: origin={}, host={}, forwardedHost={}, server={}:{}, candidates={}{}",
                        origin, request.getHeader(HttpHeaders.HOST),
                        request.getHeader("X-Forwarded-Host"), request.getServerName(),
                        request.getServerPort(), OriginAuthorities.hostAuthorities(request),
                        OriginAuthorities.isOpaque(origin)
                                ? "; 来源为不透明值,检查响应头 Referrer-Policy 是否被设为 no-referrer"
                                : "");
            }
            return configuration;
        };
    }

    /**
     * 会话存储:登录态写入共享 Redis,Cookie 只携带会话标识。
     * <p>同一次请求内优先读请求属性,错误分派(如 500 错误页)也能读到登录态;
     * 请求属性没有时回到 Redis 读取权威副本。</p>
     *
     * @param userSessionService 用户会话服务
     * @return 安全上下文仓库
     */
    @Bean
    public SecurityContextRepository securityContextRepository(IamUserSessionService userSessionService) {
        // 先看请求属性:同一次请求内的错误分派(如 500 错误页)要能读到登录态;
        // 请求属性里没有再回到共享存储取,那里是登录态的权威副本
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new IamRedisSecurityContextRepository(userSessionService));
    }

    /**
     * 身份域上下文过滤器:从请求路径或 realm 参数解析当前身份域。
     *
     * @param realmProvider 身份域读取
     * @return 身份域上下文过滤器
     */
    @Bean
    public RealmContextFilter realmContextFilter(RealmProvider realmProvider) {
        return new RealmContextFilter(realmProvider);
    }

    /**
     * 账号密码认证 Provider:表单登录与 REST 登录共用。
     * <p>具体校验逻辑由 {@link club.beenest.iam.authz.authentication.step.AuthenticationStepChain}
     * 内的步骤承担,Provider 只做上下文组装与主体构建。</p>
     *
     * @param authenticationStepChain 认证步骤链
     * @param principalFactory        登录主体工厂
     * @return 账号密码认证 Provider
     */
    @Bean
    public IamAuthenticationProvider iamAuthenticationProvider(AuthenticationStepChain authenticationStepChain,
                                                              IamPrincipalFactory principalFactory) {
        return new IamAuthenticationProvider(authenticationStepChain, principalFactory);
    }

    /**
     * REST 接口未认证入口点:返回 401 JSON 而非跳转登录页。
     *
     * @param jackson2ObjectMapper JSON 映射器
     * @return 未认证入口点
     */
    @Bean
    public IamApiAuthenticationEntryPoint apiAuthenticationEntryPoint(ObjectMapper jackson2ObjectMapper) {
        return new IamApiAuthenticationEntryPoint(jackson2ObjectMapper);
    }

    /**
     * REST 接口无权限处理器:返回 403 JSON。
     *
     * @param jackson2ObjectMapper JSON 映射器
     * @return 无权限处理器
     */
    @Bean
    public IamApiAccessDeniedHandler apiAccessDeniedHandler(ObjectMapper jackson2ObjectMapper) {
        return new IamApiAccessDeniedHandler(jackson2ObjectMapper);
    }

    /**
     * 刷新令牌的应用访问闸门。
     * <p>排在协议栈默认刷新 Provider 之前:只否决,不换票。</p>
     *
     * @param authorizationService       授权存储(按刷新令牌定位授权记录)
     * @param registeredClientRepository 注册客户端仓库
     * @param appVisibilityService       应用可见性判定
     * @param enabled                    是否启用闸门,与授权端点同一开关
     * @return 刷新令牌闸门
     */
    @Bean
    public ApplicationAccessRefreshProvider applicationAccessRefreshProvider(
            OAuth2AuthorizationService authorizationService,
            RegisteredClientRepository registeredClientRepository,
            ApplicationVisibilityService appVisibilityService,
            IamClientSessionService clientSessionService,
            SessionRevocationService revocationService,
            @Value("${iam.authorization.enforce-application-visibility:true}") boolean enabled) {
        return new ApplicationAccessRefreshProvider(authorizationService, registeredClientRepository,
                appVisibilityService, clientSessionService, revocationService, enabled);
    }

    /**
     * 授权服务器设置:不固定 issuer,同一实例按请求路径中的域标识并存多个签发方。
     *
     * @return 授权服务器设置
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .multipleIssuersAllowed(true)
                .build();
    }

    /**
     * JWT 编码器:签名密钥取生效密钥。
     * <p>密钥轮换后 JWKS 同时包含新密钥与旧公钥,默认选择器会因存在多把 RS256 密钥而无法决策;
     * 只有生效密钥携带私钥,据此选择唯一。</p>
     *
     * @param jwkSource 域密钥源
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
        // 只有生效密钥带私钥,以是否含私钥作为唯一选择依据
        encoder.setJwkSelector(jwks -> jwks.stream()
                .filter(JWK::isPrivate)
                .findFirst()
                .orElseThrow(() -> new JwtEncodingException("身份域无可用签名私钥")));
        return encoder;
    }

    /**
     * JWT 解码器:按令牌 issuer 定位身份域并取该域密钥验签,验签通过后追加令牌状态检查。
     * <p>业务接口路径不含域标识,解码时不依赖请求上下文。校验链:默认签名与有效期、
     * 令牌类型(拒绝 ID Token)、令牌版本与账号状态、吊销黑名单,覆盖"ID Token 被当访问令牌重放"、
     * 全端下线、禁用删号、单令牌吊销四类失效场景。</p>
     *
     * @param realmJwkSource   域密钥源
     * @param tokenStateCache  账号状态与令牌版本缓存
     * @param redisUtils       Redis 访问
     * @return JWT 解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(RealmJwkSource realmJwkSource, TokenStateCache tokenStateCache,
                                 RedisUtils redisUtils, SessionRevocationService revocationService,
                                 IamIssuerBaseUrlResolver baseUrlResolver) {
        // 1. 基础校验(有效期)、令牌类型与令牌版本/账号状态校验组合
        OAuth2TokenValidator<Jwt> typeValidator = new IamTokenTypeValidator();
        OAuth2TokenValidator<Jwt> versionValidator = new IamTokenVersionValidator(tokenStateCache);
        OAuth2TokenValidator<Jwt> blocklistValidator =
                new IamTokenBlocklistValidator(redisUtils, revocationService);
        OAuth2TokenValidator<Jwt> composite =
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), typeValidator,
                        versionValidator, blocklistValidator);
        // 2. 交由域感知解码器按 issuer 选择密钥
        return new IamJwtDecoder(realmJwkSource, composite, baseUrlResolver);
    }

    /**
     * 令牌认证转换器:把令牌中的权限与角色声明映射为 authority。
     *
     * @return 令牌认证转换器
     */
    @Bean
    public IamJwtAuthenticationConverter jwtAuthenticationConverter() {
        return new IamJwtAuthenticationConverter();
    }

    /**
     * IAM 自身接口的令牌类别判定:只放行会话与平台类令牌。
     *
     * @return 授权管理器
     */
    @Bean
    public IamPlatformApiAuthorizationManager platformApiAuthorizationManager() {
        return new IamPlatformApiAuthorizationManager();
    }

    /**
     * Bearer 请求匹配器:令牌验签通过才豁免 CSRF。
     * <p>只校验签名与时效,不查令牌状态——CSRF 判定早于认证,业务态结论由认证环节给出。</p>
     *
     * @param realmJwkSource 域密钥源
     * @return Bearer 请求匹配器
     */
    @Bean
    public BearerTokenRequestMatcher bearerTokenRequestMatcher(RealmJwkSource realmJwkSource,
                                                              IamIssuerBaseUrlResolver baseUrlResolver) {
        return new BearerTokenRequestMatcher(
                new IamJwtDecoder(realmJwkSource, JwtValidators.createDefault(), baseUrlResolver));
    }

    /**
     * 来源校验过滤器:状态变更请求必须来自本站或已登记的前端来源。
     *
     * @param allowedOrigins     允许的跨源前端来源,多个以逗号分隔
     * @param jackson2ObjectMapper JSON 映射器
     * @return 来源校验过滤器
     */
    @Bean
    public IamOriginGuardFilter originGuardFilter(
            @Value("${iam.cors.allowed-origins:}") String allowedOrigins,
            ObjectMapper jackson2ObjectMapper) {
        List<String> origins = allowedOrigins == null || allowedOrigins.isBlank()
                ? List.of()
                : java.util.Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList();
        return new IamOriginGuardFilter(origins, jackson2ObjectMapper);
    }

    /**
     * 认证管理器:账号密码认证 Provider 供表单登录与 REST 登录共用。
     *
     * @param iamAuthenticationProvider 账号密码认证 Provider
     * @return 认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(IamAuthenticationProvider iamAuthenticationProvider) {
        return new ProviderManager(iamAuthenticationProvider);
    }

    /**
     * 链路标识过滤器:为每个请求生成标识,日志按请求串联。
     *
     * @return 链路标识过滤器
     */
    @Bean
    public IamTraceIdFilter traceIdFilter() {
        return new IamTraceIdFilter();
    }

    /**
     * 会话校验过滤器:执行会话最大存续时长,刷新活跃时间并复核权限代次。
     *
     * @param userSessionService        用户会话服务
     * @param tokenStateCache           账号状态与令牌版本缓存
     * @param permissionProvider        权限计算
     * @param principalFactory          登录主体工厂
     * @param realmProvider             身份域读取
     * @param userProvider              用户读取
     * @param loginEventPublisher       登录事件发布器
     * @param securityContextRepository 会话安全上下文仓库
     * @return 会话校验过滤器
     */
    @Bean
    public IamSessionGuardFilter sessionGuardFilter(IamUserSessionService userSessionService,
                                                    TokenStateCache tokenStateCache,
                                                    PermissionProvider permissionProvider,
                                                    IamPrincipalFactory principalFactory,
                                                    RealmProvider realmProvider,
                                                    UserProvider userProvider,
                                                    LoginEventPublisher loginEventPublisher,
                                                    SecurityContextRepository securityContextRepository) {
        return new IamSessionGuardFilter(userSessionService, tokenStateCache, permissionProvider,
                principalFactory, realmProvider, userProvider, loginEventPublisher, securityContextRepository);
    }

    /**
     * 账户待办拦截过滤器:会话存在未完成待办时只放行办理待办所需的入口。
     *
     * @param objectMapper JSON 映射器
     * @return 待办拦截过滤器
     */
    @Bean
    public IamRequiredActionFilter requiredActionFilter(ObjectMapper objectMapper) {
        return new IamRequiredActionFilter(objectMapper);
    }
}
