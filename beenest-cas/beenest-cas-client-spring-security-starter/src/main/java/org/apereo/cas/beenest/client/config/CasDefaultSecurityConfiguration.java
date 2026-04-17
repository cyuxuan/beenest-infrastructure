package org.apereo.cas.beenest.client.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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
        String businessProxyBasePath = normalizeBasePath(properties.getBusinessLoginProxy().getBasePath());

        http
            .authorizeHttpRequests(auth -> {
                // 将 ignore-pattern 转换为 permitAll 规则
                if (properties.getIgnorePattern() != null && !properties.getIgnorePattern().isEmpty()) {
                    String[] patterns = properties.getIgnorePattern().split(",");
                    auth.requestMatchers(patterns).permitAll();
                }
                if (properties.getBusinessLoginProxy().isEnabled()) {
                    auth.requestMatchers(businessProxyBasePath + "/**").permitAll();
                }
                // CAS 内部路径始终放行
                auth.requestMatchers(
                    properties.getLoginPath() + "/**",
                    properties.getSlo().getCallbackPath(),
                    properties.getSync().getWebhookPath()
                ).permitAll();
                auth.anyRequest().authenticated();
            })
            .with(casConfigurer, c -> {})
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                businessProxyBasePath + "/**",
                properties.getLoginPath() + "/**",
                properties.getSlo().getCallbackPath(),
                properties.getSync().getWebhookPath()
            ));

        return http.build();
    }

    /**
     * 规范化代理基础路径，确保以 / 开头且不以 / 结尾。
     *
     * @param basePath 原始路径
     * @return 规范化后的路径
     */
    private String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isBlank()) {
            return "/cas";
        }
        String normalized = basePath.startsWith("/") ? basePath : "/" + basePath;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
