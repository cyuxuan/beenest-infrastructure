package org.apereo.cas.beenest.client.cache;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bearer Token 撤销服务单元测试。
 */
class BearerTokenRevocationServiceTest {

    @Test
    void shouldRevokeAccessTokenLocallyWhenNoCacheManagerPresent() {
        CasSecurityProperties properties = new CasSecurityProperties();
        BearerTokenRevocationService service = new BearerTokenRevocationService(properties, null);

        service.revokeAccessToken("access-token-1");

        assertThat(service.isAccessTokenRevoked("access-token-1")).isTrue();
        assertThat(service.isRefreshTokenRevoked("refresh-token-1")).isFalse();
    }

    @Test
    void shouldReuseSharedCacheManagerWhenAvailable() {
        CasSecurityProperties properties = new CasSecurityProperties();
        CacheManager cacheManager = new ConcurrentMapCacheManager(
                "casBearerAccessTokenRevocations",
                "casBearerRefreshTokenRevocations");
        BearerTokenRevocationService service = new BearerTokenRevocationService(properties, cacheManager);

        service.revokeRefreshToken("refresh-token-2");

        assertThat(service.isRefreshTokenRevoked("refresh-token-2")).isTrue();
    }
}
