package org.apereo.cas.beenest.client.login;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login Gateway Starter 默认值测试。
 */
class LoginGatewayStarterDefaultsTest {

    @Test
    void shouldShipOpinionatedLoginGatewayDefaults() throws Exception {
        String content = StreamUtils.copyToString(
                new ClassPathResource("application.properties").getInputStream(),
                StandardCharsets.UTF_8);

        assertThat(content).contains("cas.client.enabled=true");
        assertThat(content).contains("cas.client.mode=login-gateway");
        assertThat(content).contains("cas.client.token-auth.enabled=true");
        assertThat(content).contains("cas.client.slo.enabled=true");
    }
}
