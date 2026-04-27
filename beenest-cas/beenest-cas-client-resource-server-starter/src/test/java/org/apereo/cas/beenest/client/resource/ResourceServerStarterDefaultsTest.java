package org.apereo.cas.beenest.client.resource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resource Server Starter 默认值测试。
 */
class ResourceServerStarterDefaultsTest {

    @Test
    void shouldShipOpinionatedResourceServerDefaults() throws Exception {
        String content = StreamUtils.copyToString(
                new ClassPathResource("application.properties").getInputStream(),
                StandardCharsets.UTF_8);

        assertThat(content).contains("cas.client.enabled=true");
        assertThat(content).contains("cas.client.mode=resource-server");
        assertThat(content).contains("cas.client.token-auth.enabled=true");
        assertThat(content).contains("cas.client.redirect-login=false");
        assertThat(content).contains("cas.client.use-session=false");
    }
}
