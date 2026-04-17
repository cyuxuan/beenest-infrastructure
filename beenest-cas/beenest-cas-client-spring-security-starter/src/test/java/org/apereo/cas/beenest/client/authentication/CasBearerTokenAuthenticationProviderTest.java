package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.beenest.client.session.CasUserSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bearer Token 认证提供者单元测试。
 */
class CasBearerTokenAuthenticationProviderTest {

    private final CasTgtValidator tgtValidator = mock(CasTgtValidator.class);
    private final CasSecurityProperties properties = new CasSecurityProperties();
    private final BearerTokenCache tokenCache = mock(BearerTokenCache.class);
    private final BearerTokenRevocationService revocationService = mock(BearerTokenRevocationService.class);
    private final CasUserDetailsService userDetailsService = mock(CasUserDetailsService.class);
    private final CasTokenRefresher tokenRefresher = mock(CasTokenRefresher.class);

    private CasBearerTokenAuthenticationProvider newProvider() {
        return new CasBearerTokenAuthenticationProvider(
                tgtValidator,
                properties,
                tokenCache,
                revocationService,
                userDetailsService,
                tokenRefresher);
    }

    @Test
    void shouldRejectRevokedAccessTokenBeforeCacheLookup() {
        when(revocationService.isAccessTokenRevoked("revoked-access")).thenReturn(true);

        CasBearerTokenAuthenticationToken authentication = new CasBearerTokenAuthenticationToken("revoked-access");

        assertThatThrownBy(() -> newProvider().authenticate(authentication))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("accessToken 已注销");

        verify(tokenCache, never()).get("revoked-access");
        verify(tgtValidator, never()).validate("revoked-access");
    }

    @Test
    void shouldRejectRevokedRefreshTokenBeforeAutoRefresh() {
        when(tokenCache.get("expired-access")).thenReturn(null);
        when(tgtValidator.validate("expired-access")).thenReturn(null);
        when(revocationService.isAccessTokenRevoked("expired-access")).thenReturn(false);
        when(revocationService.isRefreshTokenRevoked("revoked-refresh")).thenReturn(true);

        CasBearerTokenAuthenticationToken authentication =
                new CasBearerTokenAuthenticationToken("expired-access", "revoked-refresh");

        assertThatThrownBy(() -> newProvider().authenticate(authentication))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("refreshToken 已注销");

        verify(tokenRefresher, never()).refreshToken("revoked-refresh");
    }

    @Test
    void shouldAuthenticateValidTokenWithCacheHit() {
        CasUserSession session = new CasUserSession();
        session.setUserId("u100");
        session.setNickname("测试用户");
        CasUserDetails userDetails = new CasUserDetails(session, List.of());

        when(revocationService.isAccessTokenRevoked("access-ok")).thenReturn(false);
        when(tokenCache.get("access-ok")).thenReturn(session);
        when(userDetailsService.loadUserByCasAssertion(org.mockito.ArgumentMatchers.eq("u100"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(userDetails);

        Authentication result = newProvider().authenticate(new CasBearerTokenAuthenticationToken("access-ok"));

        verify(tokenCache).get("access-ok");
        verify(tgtValidator, never()).validate("access-ok");
        org.assertj.core.api.Assertions.assertThat(result.isAuthenticated()).isTrue();
    }
}
