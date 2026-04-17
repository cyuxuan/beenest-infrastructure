package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.filter.AdminApiAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Admin API 安全配置
 * <p>
 * 为 {@code /cas/admin/*} 路径注册认证过滤器，
 * 要求请求头携带有效的 Admin Token。
 * <p>
 * Token 通过 {@code beenest.admin.token} 配置，
 * 推荐使用环境变量 {@code CAS_ADMIN_TOKEN} 注入。
 */
@Configuration
public class CasAdminSecurityConfig {

    @Value("${beenest.admin.token:${CAS_ADMIN_TOKEN:}}")
    private String adminToken;

    @Bean
    public FilterRegistrationBean<AdminApiAuthFilter> adminApiAuthFilter(StringRedisTemplate redisTemplate) {
        FilterRegistrationBean<AdminApiAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminApiAuthFilter(adminToken, redisTemplate));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("adminApiAuthFilter");
        return registration;
    }
}
