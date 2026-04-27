package org.apereo.cas.beenest.client.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * 默认 SecurityFilterChain（零代码模式）
 * <p>
 * 当业务系统没有自定义 SecurityFilterChain 时自动创建。
 * 提供 CAS 认证 + Session 管理 + CSRF 保护的默认配置。
 * <p>
 * 如果业务系统定义了自己的 SecurityFilterChain，此配置不会生效。
 * 业务系统需要手动注入 CasAuthenticationConfigurer：
 * {@code http.with(casConfigurer, c -> {})}
 */
@Configuration
@ConditionalOnProperty(prefix = "cas.client", name = "enabled", havingValue = "true")
public class CasDefaultSecurityConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain casDefaultSecurityFilterChain(
            HttpSecurity http,
            CasAuthenticationConfigurer casConfigurer,
            CasSecurityProperties properties) throws Exception {
        boolean exposeLoginEndpoints = shouldExposeLoginEndpoints(properties);

        http
            .authorizeHttpRequests(auth -> {
                // 将 ignore-pattern 转换为 permitAll 规则
                if (properties.getIgnorePattern() != null && !properties.getIgnorePattern().isEmpty()) {
                    String[] patterns = properties.getIgnorePattern().split(",");
                    auth.requestMatchers(patterns).permitAll();
                }
                // CAS 内部路径始终放行
                if (exposeLoginEndpoints) {
                    auth.requestMatchers(
                        properties.getLoginPath() + "/**",
                        properties.getSlo().getCallbackPath()
                    ).permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .with(casConfigurer, c -> {})
            .sessionManagement(session -> session
                .sessionCreationPolicy(resolveSessionCreationPolicy(properties)))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                buildCsrfIgnoreMatchers(properties, exposeLoginEndpoints)
            ));

        if (!shouldEnableLoginRedirect(properties)) {
            http.exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"code\":401,\"message\":\"未认证或登录已失效\",\"data\":null}");
            }));
        }

        return http.build();
    }

    /**
     * 解析当前模式下的 Session 创建策略。
     *
     * @param properties Starter 配置
     * @return SessionCreationPolicy
     */
    SessionCreationPolicy resolveSessionCreationPolicy(CasSecurityProperties properties) {
        if (isResourceServerMode(properties)) {
            return SessionCreationPolicy.STATELESS;
        }
        return properties.isUseSession() ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS;
    }

    /**
     * 当前模式是否允许启用登录跳转。
     *
     * @param properties Starter 配置
     * @return true 表示允许跳转到 CAS 登录页
     */
    boolean shouldEnableLoginRedirect(CasSecurityProperties properties) {
        return !isResourceServerMode(properties) && properties.isRedirectLogin();
    }

    /**
     * 当前模式是否应暴露登录相关端点。
     *
     * @param properties Starter 配置
     * @return true 表示需要保留登录入口、SLO 回调等端点
     */
    boolean shouldExposeLoginEndpoints(CasSecurityProperties properties) {
        return !isResourceServerMode(properties);
    }

    /**
     * 构建 CSRF 忽略路径列表。
     *
     * @param properties Starter 配置
     * @param exposeLoginEndpoints 是否暴露登录端点
     * @return 忽略路径数组
     */
    private String[] buildCsrfIgnoreMatchers(CasSecurityProperties properties,
                                             boolean exposeLoginEndpoints) {
        java.util.List<String> matchers = new java.util.ArrayList<>();
        if (exposeLoginEndpoints) {
            matchers.add(properties.getLoginPath() + "/**");
            matchers.add(properties.getSlo().getCallbackPath());
        }
        return matchers.toArray(String[]::new);
    }

    /**
     * 判断当前是否为资源服务模式。
     *
     * @param properties Starter 配置
     * @return true 表示 resource-server
     */
    private boolean isResourceServerMode(CasSecurityProperties properties) {
        return properties != null && properties.isResourceServerMode();
    }

}
