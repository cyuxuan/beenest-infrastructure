package org.apereo.cas.beenest.config;

import org.apereo.cas.web.CasWebSecurityConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.List;

/**
 * Palantir 监控面板安全配置。
 * <p>
 * 修复 Palantir formLogin 登录后 session 快速失效的问题。
 * <p>
 * 根因：CAS 7.3.6 注册的 {@link SecurityContextHolderFilter} 不在请求结束时调用
 * {@code SecurityContextRepository.saveContext()}，且 CAS 的
 * {@code CasWebflowSecurityContextRepository.saveContext()} 是空实现（no-op）。
 * 导致 formLogin 认证成功后安全上下文未持久化到 HTTP Session，后续请求读不到认证信息。
 * <p>
 * 本配置通过：
 * <ol>
 *   <li>覆盖 {@code securityContextRepository} bean，使用 {@link HttpSessionSecurityContextRepository}
 *       替代 CAS 的 DelegatingSecurityContextRepository（内含 saveContext 空实现的 CasWebflowSecurityContextRepository）</li>
 *   <li>覆盖 {@code securityContextHolderFilter} bean，配置 {@link SecurityContextHolderFilter}
 *       并在 {@code SecurityContext} 配置中设置 {@code requireExplicitSave(false)}，
 *       使 filter 在响应时自动保存安全上下文到 Session</li>
 *   <li>自定义 {@link CasWebSecurityConfigurer} 配置 sessionManagement（session fixation 保护 + 并发控制）</li>
 * </ol>
 */
@AutoConfiguration
public class PalantirSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(PalantirSecurityConfig.class);

    /**
     * 覆盖 CAS 默认的 securityContextRepository。
     * <p>
     * CAS 默认的 {@code DelegatingSecurityContextRepository} 内含 {@code CasWebflowSecurityContextRepository}，
     * 其 {@code saveContext()} 是空实现（no-op），且在 formLogin 场景下无意义。
     * 使用 {@link HttpSessionSecurityContextRepository} 直接持久化到 HTTP Session 更可靠。
     * <p>
     * CAS 的 {@code CasWebSecurityConfiguration} 使用 {@code @ConditionalOnMissingBean(name = "securityContextRepository")}
     * 注册默认 bean，因此本 bean 命名为 {@code securityContextRepository} 即可覆盖。
     *
     * @return 安全上下文仓库
     */
    @org.springframework.context.annotation.Bean("securityContextRepository")
    public SecurityContextRepository securityContextRepository() {
        log.info(">>> 使用 HttpSessionSecurityContextRepository 替代 CAS DelegatingSecurityContextRepository");
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 覆盖 CAS 默认的 SecurityContextHolderFilter。
     * <p>
     * 使用 {@link HttpSessionSecurityContextRepository} 替代 CAS 的 DelegatingSecurityContextRepository，
     * 配合 {@code requireExplicitSave(false)}（通过 palantirSecurityContextConfigurer 设置），
     * 使 {@link SecurityContextHolderFilter} 在请求结束时自动调用 {@code saveContext()}，
     * 将安全上下文持久化到 HTTP Session。
     * <p>
     * CAS 使用 {@code @ConditionalOnMissingBean(name = "securityContextHolderFilter")}，
     * 本 bean 需要同名覆盖。
     *
     * @param securityContextRepository 安全上下文仓库
     * @return SecurityContextHolderFilter 注册 bean
     */
    @org.springframework.context.annotation.Bean("securityContextHolderFilter")
    public FilterRegistrationBean<SecurityContextHolderFilter> securityContextHolderFilter(
            final SecurityContextRepository securityContextRepository) {
        log.info(">>> 覆盖 CAS SecurityContextHolderFilter，使用 HttpSessionSecurityContextRepository");
        FilterRegistrationBean<SecurityContextHolderFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new SecurityContextHolderFilter(securityContextRepository));
        registration.setUrlPatterns(List.of("/*"));
        registration.setName("Spring Security Context Holder Filter");
        registration.setAsyncSupported(true);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * 注册 Palantir 安全上下文和 session 管理配置器。
     * <p>
     * 在 CAS 安全链构建时：
     * <ul>
     *   <li>设置 {@code requireExplicitSave(false)}，使 SecurityContextHolderFilter
     *       在响应时自动保存安全上下文到 HTTP Session（Spring Security 6.x 默认为 true，
     *       即需要显式调用 saveContext，这正是 CAS formLogin 场景下 session 丢失的根因）</li>
     *   <li>配置 session fixation 保护和并发 session 控制</li>
     * </ul>
     *
     * @return CAS Web 安全配置器
     */
    @org.springframework.context.annotation.Bean
    @ConditionalOnMissingBean(name = "palantirSecurityContextConfigurer")
    public CasWebSecurityConfigurer<HttpSecurity> palantirSecurityContextConfigurer() {
        return new CasWebSecurityConfigurer<>() {
            @Override
            public int getOrder() {
                // 在 CAS 原生 configurer 之后执行，确保安全链已构建完成
                return Ordered.LOWEST_PRECEDENCE - 10;
            }

            @Override
            public List<String> getIgnoredEndpoints() {
                return List.of();
            }

            @Override
            public CasWebSecurityConfigurer<HttpSecurity> configure(final HttpSecurity http) throws Exception {
                // 1. 关闭 requireExplicitSave，使 SecurityContextHolderFilter 在响应时自动保存安全上下文
                //    这是修复 Palantir session 快速失效的核心配置
                http.securityContext(securityContext -> {
                    securityContext.securityContextRepository(securityContextRepository());
                    securityContext.requireExplicitSave(false);
                });

                // 2. 配置 Session Management
                http.sessionManagement(session -> session
                        // session fixation 保护：登录后迁移到新 session
                        .sessionFixation().migrateSession()
                        // 同一用户最多 5 个并发 session
                        .maximumSessions(5)
                        .maxSessionsPreventsLogin(false)
                );

                return this;
            }
        };
    }
}
