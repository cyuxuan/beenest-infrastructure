package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationProvider;
import org.apereo.cas.beenest.client.controller.CasBusinessLoginProxyController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starter 模式化自动配置测试。
 */
class CasSecurityAutoConfigurationModeTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    CasSecurityAutoConfiguration.class));

    @Test
    void shouldOnlyEnableBearerAuthenticationForResourceServerMode() {
        contextRunner
                .withPropertyValues(
                        "cas.client.enabled=true",
                        "cas.client.mode=resource-server",
                        "cas.client.server-url=https://sso.example.com/cas",
                        "cas.client.client-host-url=https://api.example.com",
                        "cas.client.service-id=demo-service",
                        "cas.client.sign-key=test-sign-key",
                        "cas.client.token-validation-secret=test-validation-secret",
                        "cas.client.token-auth.enabled=true",
                        "cas.client.business-login-proxy.enabled=true",
                        "cas.client.slo.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CasBearerTokenAuthenticationProvider.class);
                    assertThat(context).doesNotHaveBean(CasAuthenticationProvider.class);
                    assertThat(context).doesNotHaveBean(CasBusinessLoginProxyController.class);
                });
    }
}
