package org.apereo.cas.beenest.client.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.http.SessionCreationPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认安全配置测试。
 */
class CasDefaultSecurityConfigurationTest {

    private final CasDefaultSecurityConfiguration configuration = new CasDefaultSecurityConfiguration();

    @Test
    void shouldUseStatefulDefaultsForLoginGatewayMode() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setMode(CasMode.LOGIN_GATEWAY);
        properties.setUseSession(true);
        properties.setRedirectLogin(true);

        assertThat(configuration.resolveSessionCreationPolicy(properties)).isEqualTo(SessionCreationPolicy.IF_REQUIRED);
        assertThat(configuration.shouldEnableLoginRedirect(properties)).isTrue();
        assertThat(configuration.shouldExposeLoginEndpoints(properties)).isTrue();
    }

    @Test
    void shouldUseStatelessDefaultsForResourceServerMode() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setMode(CasMode.RESOURCE_SERVER);

        assertThat(configuration.resolveSessionCreationPolicy(properties)).isEqualTo(SessionCreationPolicy.STATELESS);
        assertThat(configuration.shouldEnableLoginRedirect(properties)).isFalse();
        assertThat(configuration.shouldExposeLoginEndpoints(properties)).isFalse();
    }

    @Test
    void shouldRespectExplicitOverridesOutsideResourceServerDefaults() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setMode(CasMode.LOGIN_GATEWAY);
        properties.setUseSession(false);
        properties.setRedirectLogin(false);

        assertThat(configuration.resolveSessionCreationPolicy(properties)).isEqualTo(SessionCreationPolicy.STATELESS);
        assertThat(configuration.shouldEnableLoginRedirect(properties)).isFalse();
    }
}
