package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.controller.CasBusinessLoginProxyController;
import org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务登录代理装配测试。
 */
class CasBusinessLoginProxyConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class, CasBusinessLoginProxyConfiguration.class);

    @Test
    void shouldNotExposeBusinessLoginProxyByDefault() {
        contextRunner
                .withPropertyValues(
                        "cas.client.enabled=true",
                        "cas.client.server-url=https://sso.example.com/cas",
                        "cas.client.client-host-url=https://api.example.com")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RestTemplate.class);
                    assertThat(context).doesNotHaveBean(CasBusinessLoginProxyService.class);
                    assertThat(context).doesNotHaveBean(CasBusinessLoginProxyController.class);
                });
    }

    @Test
    void shouldExposeBusinessLoginProxyOnlyForLoginGatewayMode() {
        contextRunner
                .withPropertyValues(
                        "cas.client.enabled=true",
                        "cas.client.mode=login-gateway",
                        "cas.client.business-login-proxy.enabled=true",
                        "cas.client.server-url=https://sso.example.com/cas",
                        "cas.client.client-host-url=https://api.example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(RestTemplate.class);
                    assertThat(context).hasSingleBean(CasBusinessLoginProxyService.class);
                    assertThat(context).hasSingleBean(CasBusinessLoginProxyController.class);
                });
    }

    @Test
    void shouldNotExposeBusinessLoginProxyForResourceServerMode() {
        contextRunner
                .withPropertyValues(
                        "cas.client.enabled=true",
                        "cas.client.mode=resource-server",
                        "cas.client.business-login-proxy.enabled=true",
                        "cas.client.server-url=https://sso.example.com/cas",
                        "cas.client.client-host-url=https://api.example.com")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RestTemplate.class);
                    assertThat(context).doesNotHaveBean(CasBusinessLoginProxyService.class);
                    assertThat(context).doesNotHaveBean(CasBusinessLoginProxyController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CasSecurityProperties.class)
    static class TestConfiguration {
    }
}
