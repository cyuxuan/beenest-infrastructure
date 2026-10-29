package club.beenest.payment.config;

import club.beenest.payment.security.IamAccessTokenValidator;
import club.beenest.payment.security.JwtAudienceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 支付服务安全配置。
 * <p>
 * 支付服务以<b>标准 OAuth2 资源服务</b>形态接入 IAM：
 * <ul>
 *   <li>用户侧 API 校验 IAM 签发的 RS256 访问令牌（JWT）：验签 + iss + aud + 有效期</li>
 *   <li>权限判定基于令牌中的 {@code permissions} / 角色声明，映射规则见
 *       {@link IamJwtAuthenticationConverter}</li>
 *   <li>内部 API（{@code /internal/**}）继续走内网签名过滤器 {@code InternalApiFilter}</li>
 *   <li>第三方支付回调与 Swagger 文档保持匿名访问</li>
 *   <li>管理接口通过 Spring Security 方法级鉴权限制为管理员</li>
 * </ul>
 *
 * <p>CSRF 保护已禁用：支付服务为纯后端 API 服务，无浏览器 UI，
 * 使用无状态 Bearer Token 认证（SessionCreationPolicy.STATELESS），
 * 不存在 Cookie-based 会话，CSRF 攻击向量不适用。</p>
 *
 * @since 2026-10-29
 */
@Configuration
@ConditionalOnProperty(prefix = "iam.resource-server", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableMethodSecurity
@Slf4j
@SuppressWarnings("squid:S4502")
public class PaymentSecurityConfig {

    /**
     * 匿名可访问路径。
     * <p>内部 API 由 {@code InternalApiFilter} 做独立鉴权；支付回调由渠道签名与
     * IP 白名单保护；文档与健康检查路径不含业务数据。</p>
     */
    private static final String[] PUBLIC_PATHS = {
            "/internal/**",
            "/api/wallet/payment/callback/**",
            "/api/wallet/payment/refund/callback/**",
            "/api/payscore/callback/**",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/v3/api-docs/swagger-config",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/**"
    };

    /**
     * 构建支付服务的安全过滤链。
     *
     * @param http                          Spring Security 构建器
     * @param iamJwtAuthenticationConverter IAM 令牌 → 认证对象转换器
     * @return 安全过滤链
     * @throws Exception 构建安全链失败时抛出
     */
    @Bean
    public SecurityFilterChain paymentSecurityFilterChain(
            HttpSecurity http,
            IamJwtAuthenticationConverter iamJwtAuthenticationConverter) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> {
                    for (String path : PUBLIC_PATHS) {
                        auth.requestMatchers(path).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(iamJwtAuthenticationConverter))
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, 401, "未认证或登录已失效"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, 403, "无权限访问该资源")));

        return http.build();
    }

    /**
     * 构建 IAM 令牌解码器。
     * <p>
     * 端点来源二选一，优先级 {@code jwk-set-uri} &gt; {@code issuer-uri}：
     * <ul>
     *   <li>{@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
     *       —— 启动时拉取发现文档，端点与 JWKS 自动解析（推荐）</li>
     *   <li>{@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
     *       —— 显式指定 JWKS，避免启动期强依赖 IAM 可用性</li>
     * </ul>
     * <p>校验清单：签名（RS256）+ {@code exp/nbf/iat}（允许 60 秒时钟偏差）+ {@code iss}
     * + {@code aud} + 令牌类型（拒绝 ID Token）。</p>
     *
     * @param serverProperties Spring Boot 标准资源服务配置
     * @param iamProperties    IAM 扩展配置
     * @return 令牌解码器
     */
    @Bean
    public JwtDecoder iamJwtDecoder(OAuth2ResourceServerProperties serverProperties,
                                    IamResourceServerProperties iamProperties) {
        OAuth2ResourceServerProperties.Jwt jwtProperties = serverProperties.getJwt();
        String issuerUri = jwtProperties.getIssuerUri();
        String jwkSetUri = jwtProperties.getJwkSetUri();

        // 1. 解析 JWKS 端点：显式配置优先，避免 IAM 未启动时应用起不来
        NimbusJwtDecoder decoder;
        if (StringUtils.hasText(jwkSetUri)) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else if (StringUtils.hasText(issuerUri)) {
            decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        } else {
            throw new IllegalStateException("IAM 资源服务未配置端点：请设置 "
                    + "spring.security.oauth2.resourceserver.jwt.issuer-uri（推荐）"
                    + " 或 spring.security.oauth2.resourceserver.jwt.jwk-set-uri");
        }

        // 2. 组合校验器：默认（时间戳）+ issuer + 受众 + 令牌类型
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefault());
        if (StringUtils.hasText(issuerUri)) {
            validators.add(new JwtIssuerValidator(issuerUri));
        }
        if (StringUtils.hasText(iamProperties.getAudience())) {
            validators.add(new JwtAudienceValidator(iamProperties.getAudience()));
        } else {
            // 没有受众校验，同域其他应用的访问令牌也能调用本服务
            log.warn("IAM 资源服务未配置受众(iam.resource-server.audience)，已跳过 aud 校验");
        }
        validators.add(new IamAccessTokenValidator(iamProperties.isRequireApplicationToken()));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    /**
     * 输出统一 JSON 错误响应。
     *
     * @param response HTTP 响应对象
     * @param status   HTTP 状态码
     * @param message  错误信息
     */
    private void writeJson(jakarta.servlet.http.HttpServletResponse response, int status, String message) {
        try {
            response.setStatus(status);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("写入安全响应失败", e);
        }
    }
}
