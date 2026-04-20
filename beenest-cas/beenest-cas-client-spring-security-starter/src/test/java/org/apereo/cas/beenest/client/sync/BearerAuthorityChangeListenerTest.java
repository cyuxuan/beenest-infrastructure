package org.apereo.cas.beenest.client.sync;

import org.apereo.cas.beenest.client.cache.BearerAuthorityVersionService;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Bearer 权限变更监听器测试。
 */
class BearerAuthorityChangeListenerTest {

    @Test
    void shouldEvictUserTokensAndUpdateAuthorityVersion() {
        CasSecurityProperties properties = new CasSecurityProperties();
        BearerTokenCache bearerTokenCache = mock(BearerTokenCache.class);
        BearerAuthorityVersionService authorityVersionService = new BearerAuthorityVersionService(null);
        BearerAuthorityChangeListener listener = new BearerAuthorityChangeListener(
                properties, bearerTokenCache, authorityVersionService);

        UserChangeEvent event = new UserChangeEvent();
        event.setUserId("u200");
        event.setEventType("UPDATE");
        event.setNewData(Map.of("permissionVersion", "v2"));

        listener.onUserChange(event);

        verify(bearerTokenCache).removeByUserId("u200");
        assertThat(authorityVersionService.getUserVersion("u200")).isEqualTo("v2");
    }
}
