package org.apereo.cas.beenest.config;

import org.apereo.cas.web.CasWebSecurityConfigurer;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import java.util.List;

/**
 * /api/admin/** 路径的 Spring Security 放行配置。
 * <p>
 * CAS 的 CasWebSecurityConfigurerAdapter 默认对未匹配路径拒绝访问，
 * {@code /api/admin/**} 下的管理端接口（如 SessionManagementController
 * 的会话管理端点）会因此被拦截返回 403。
 * 本配置器对 {@code /api/admin/**} 放行授权并忽略 CSRF；
 * 认证由 AdminApiAuthFilter（X-CAS-Admin-Token 请求头）在
 * Spring Security 链之前完成。
 */
public class BeenestApiSecurityConfigurer implements CasWebSecurityConfigurer<HttpSecurity> {

    /**
     * 对 /api/admin/** 放行授权并忽略 CSRF。
     * <p>
     * 放行不会引入风险：AdminApiAuthFilter 已在更早的过滤器位置
     * （HIGHEST_PRECEDENCE + 10）校验 Admin Token。
     */
    @Override
    public BeenestApiSecurityConfigurer configure(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/admin/**"));
        http.authorizeHttpRequests(requests -> requests
                .requestMatchers("/api/admin/**").permitAll());
        return this;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public List<String> getIgnoredEndpoints() {
        return List.of();
    }
}
