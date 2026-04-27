package org.apereo.cas.beenest.client.login;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录网关 starter 环境后置处理器测试。
 */
class LoginGatewayStarterEnvironmentPostProcessorTest {

    /**
     * 1. 当业务系统未显式配置时，应自动补齐登录网关默认值。
     */
    @Test
    void shouldAddLoginGatewayDefaultsWhenMissing() {
        MockEnvironment environment = new MockEnvironment();

        new LoginGatewayStarterEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("cas.client.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("cas.client.mode")).isEqualTo("login-gateway");
        assertThat(environment.getProperty("cas.client.redirect-login")).isEqualTo("true");
        assertThat(environment.getProperty("cas.client.use-session")).isEqualTo("true");
        assertThat(environment.getProperty("cas.client.slo.enabled")).isEqualTo("true");
    }

    /**
     * 2. 当业务系统已有配置时，不应覆盖外部显式值。
     */
    @Test
    void shouldKeepExplicitValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cas.client.enabled", "false")
                .withProperty("cas.client.mode", "resource-server");

        new LoginGatewayStarterEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("cas.client.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("cas.client.mode")).isEqualTo("resource-server");
    }
}
