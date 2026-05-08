package org.apereo.cas.beenest.config;

import org.apereo.cas.web.CasWebSecurityConfigurer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beenest 原生端点安全配置测试。
 */
class BeenestNativeEndpointSecurityConfigTest {

    /**
     * 验证小程序、APP 和 Token 续期入口会加入 CAS 安全忽略列表。
     */
    @Test
    void shouldIgnoreNativeLoginEndpoints() {
        BeenestNativeEndpointSecurityConfig config = new BeenestNativeEndpointSecurityConfig();

        CasWebSecurityConfigurer<Object> configurer =
                config.beenestNativeEndpointWebSecurityConfigurer();

        assertThat(configurer.getIgnoredEndpoints())
                .containsExactly("/miniapp", "/app", "/refresh");
    }

    /**
     * 验证配置类已经登记到 Spring Boot 自动配置清单，确保 CAS 启动时可以加载该 Bean。
     *
     * @throws IOException 读取自动配置清单失败时抛出
     */
    @Test
    void shouldBeRegisteredAsAutoConfiguration() throws IOException {
        try (var inputStream = getClass().getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(inputStream).isNotNull();

            String imports = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(imports)
                    .contains("org.apereo.cas.beenest.config.BeenestNativeEndpointSecurityConfig");
        }
    }
}
