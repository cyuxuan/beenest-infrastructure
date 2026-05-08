package org.apereo.cas.beenest.config;

import org.apereo.cas.web.CasWebSecurityConfigurer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Beenest 原生登录端点安全配置。
 * <p>
 * CAS 默认安全链只放行内置协议入口，自定义的小程序、APP 和 Token 续期端点
 * 需要显式加入忽略列表，否则请求会在进入控制器前被 BasicAuthenticationEntryPoint 拒绝。
 */
@AutoConfiguration
public class BeenestNativeEndpointSecurityConfig {

    /**
     * 注册 CAS Web 安全忽略端点。
     *
     * @return CAS Web 安全配置器
     */
    @Bean
    public CasWebSecurityConfigurer<Object> beenestNativeEndpointWebSecurityConfigurer() {
        return new CasWebSecurityConfigurer<>() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public List<String> getIgnoredEndpoints() {
                return List.of(
                        "/miniapp",
                        "/app",
                        "/refresh");
            }
        };
    }
}
