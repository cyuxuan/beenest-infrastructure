package org.apereo.cas.beenest.client.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CAS Starter 配置属性测试。
 */
class CasSecurityPropertiesTest {

    @Test
    void shouldUseSafeDefaultsForServiceModes() {
        CasSecurityProperties properties = new CasSecurityProperties();

        assertThat(properties.getMode()).isEqualTo(CasMode.LOGIN_GATEWAY);
        assertThat(properties.isLoginGatewayMode()).isTrue();
        assertThat(properties.isResourceServerMode()).isFalse();
        assertThat(properties.getProxy().isEnabled()).isFalse();
    }
}
