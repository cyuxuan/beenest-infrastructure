package org.apereo.cas.beenest.client.resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源服务 starter 环境后置处理器测试。
 */
class ResourceServerStarterEnvironmentPostProcessorTest {

    /**
     * 1. 当业务系统未显式配置时，应自动补齐资源服务默认值。
     */
    @Test
    void shouldAddResourceServerDefaultsWhenMissing() {
        MockEnvironment environment = new MockEnvironment();

        new ResourceServerStarterEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("cas.client.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("cas.client.mode")).isEqualTo("resource-server");
        assertThat(environment.getProperty("cas.client.token-auth.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("cas.client.redirect-login")).isEqualTo("false");
        assertThat(environment.getProperty("cas.client.use-session")).isEqualTo("false");
        assertThat(environment.getProperty("cas.client.slo.enabled")).isEqualTo("false");
    }

    /**
     * 2. 当业务系统已有配置时，不应覆盖外部显式值。
     */
    @Test
    void shouldKeepExplicitValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cas.client.enabled", "false")
                .withProperty("cas.client.mode", "login-gateway");

        new ResourceServerStarterEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("cas.client.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("cas.client.mode")).isEqualTo("login-gateway");
    }
}
