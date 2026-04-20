package club.beenest.payment.config;

import org.apereo.cas.beenest.client.config.CasAuthenticationConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * 支付服务安全配置。
 * <p>
 * 该配置将支付服务切换为 CAS 资源服务模式：
 * <ul>
 *   <li>用户侧 API 统一走 CAS Bearer Token 认证</li>
 *   <li>内部 API 继续保留现有内网签名过滤器</li>
 *   <li>第三方支付回调与 Swagger 文档保持匿名访问</li>
 *   <li>管理接口通过 Spring Security 方法级鉴权限制为管理员</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
@EnableMethodSecurity
public class PaymentSecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/internal/**",
            "/api/wallet/payment/callback/**",
            "/api/wallet/payment/refund/callback/**",
            "/v3/api-docs/**",
            "/v3/api-docs/swagger-config",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/webjars/**"
    };

    /**
     * 构建支付服务的安全过滤链。
     *
     * @param http Spring Security 构建器
     * @param casConfigurer CAS starter 提供的认证装配器
     * @return 安全过滤链
     * @throws Exception 构建安全链失败时抛出
     */
    @Bean
    public SecurityFilterChain paymentSecurityFilterChain(HttpSecurity http,
                                                          CasAuthenticationConfigurer casConfigurer) throws Exception {
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
                .with(casConfigurer, customizer -> {})
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, 401, "未认证或登录已失效"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, 403, "无权限访问该资源")));

        return http.build();
    }

    /**
     * 输出统一 JSON 错误响应。
     *
     * @param response HTTP 响应对象
     * @param status HTTP 状态码
     * @param message 错误信息
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
