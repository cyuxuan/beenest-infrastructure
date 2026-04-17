package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.sync.CasUserChangeListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CasSyncConfigurationTest {

    @Test
    void shouldFailFastWhenSignKeyMissing() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.getSync().setEnabled(true);
        properties.getSync().setWebhookPath("/cas/sync/webhook");
        properties.setSignKey("");

        CasSyncConfiguration configuration = new CasSyncConfiguration();

        assertThatThrownBy(() -> configuration.casSyncWebhookFilterRegistration(
                properties,
                new ActiveSessionRegistry(),
                List.<CasUserChangeListener>of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cas.client.sync.enabled=true");
    }
}
