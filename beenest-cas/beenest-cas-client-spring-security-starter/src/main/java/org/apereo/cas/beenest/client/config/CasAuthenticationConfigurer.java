package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationFilter;
import org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.cas.web.CasAuthenticationFilter;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationEntryPoint;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.SecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

/**
 * CAS SecurityFilterChain DSL 配置器
 * <p>
 * 业务系统通过 {@code http.with(casConfigurer, c -> {})} 一行引入 CAS 认证。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>init() — 注册 AuthenticationProvider（必须在 init 阶段，configure 阶段 Provider 已构建完毕）</li>
 *   <li>configure() — 添加 Filter 和配置 EntryPoint</li>
 * </ul>
 */
@Slf4j
public class CasAuthenticationConfigurer
        extends SecurityConfigurerAdapter<DefaultSecurityFilterChain, HttpSecurity> {

    private final CasAuthenticationFilter casFilter; // nullable
    private final CasAuthenticationProvider casProvider; // nullable
    private final CasAuthenticationEntryPoint entryPoint; // nullable
    private final CasBearerTokenAuthenticationFilter bearerTokenFilter; // nullable
    private final CasBearerTokenAuthenticationProvider bearerTokenProvider; // nullable
    
    public CasAuthenticationConfigurer(CasAuthenticationFilter casFilter,
                                        CasAuthenticationProvider casProvider,
                                        CasAuthenticationEntryPoint entryPoint,
                                        CasBearerTokenAuthenticationFilter bearerTokenFilter,
                                        CasBearerTokenAuthenticationProvider bearerTokenProvider,
                                        CasSecurityProperties properties) {
        this.casFilter = casFilter;
        this.casProvider = casProvider;
        this.entryPoint = entryPoint;
        this.bearerTokenFilter = bearerTokenFilter;
        this.bearerTokenProvider = bearerTokenProvider;
    }

    @Override
    public void init(HttpSecurity http) {
        // 1. 注册 CasAuthenticationProvider（init 阶段，AuthenticationManager 构建前）
        if (casProvider != null) {
            http.authenticationProvider(casProvider);
        }

        // 2. 如果 Bearer Token 启用，注册 BearerTokenAuthenticationProvider
        if (bearerTokenProvider != null) {
            http.authenticationProvider(bearerTokenProvider);
            log.info("CAS Bearer Token 认证已启用");
        }

        if (casProvider != null) {
            log.info("CAS AuthenticationProvider 已注册");
        }
    }

    @Override
    public void configure(HttpSecurity http) throws Exception {
        // 1. 设置 CasAuthenticationEntryPoint（仅浏览器请求触发重定向）
        if (entryPoint != null) {
            http.exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    entryPoint,
                    new RequestHeaderRequestMatcher("Accept", "text/html")
                )
            );
        }

        // 2. 添加 CasBearerTokenAuthenticationFilter（在 CAS_FILTER 之前）
        if (bearerTokenFilter != null) {
            http.addFilterBefore(bearerTokenFilter, CasAuthenticationFilter.class);
        }

        // 3. 添加 CasAuthenticationFilter 到 CAS_FILTER 位置
        //    设置 AuthenticationManager（Spring Security 要求 Filter 必须有）
        if (casFilter != null) {
            AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager.class);
            if (authenticationManager != null) {
                casFilter.setAuthenticationManager(authenticationManager);
            }
            http.addFilter(casFilter);
        }

        log.info("CAS Filter Chain 已配置");
    }
}
