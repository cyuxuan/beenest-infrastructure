package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerAuthorityVersionService;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bearer Token 认证提供者单元测试。
 */
class CasBearerTokenAuthenticationProviderTest {

    private final CasNativeTicketValidator nativeTicketValidator = mock(CasNativeTicketValidator.class);
    private final CasSecurityProperties properties = new CasSecurityProperties();
    private final BearerTokenCache tokenCache = mock(BearerTokenCache.class);
    private final BearerTokenRevocationService revocationService = mock(BearerTokenRevocationService.class);
    private final CasUserDetailsService userDetailsService = mock(CasUserDetailsService.class);
    private final CasTokenRefresher tokenRefresher = mock(CasTokenRefresher.class);
    private final BearerAuthorityVersionService authorityVersionService = mock(BearerAuthorityVersionService.class);

    private CasBearerTokenAuthenticationProvider newProvider() {
        return new CasBearerTokenAuthenticationProvider(
                nativeTicketValidator,
                properties,
                tokenCache,
                revocationService,
                userDetailsService,
                tokenRefresher,
                authorityVersionService);
    }

    @Test
    void shouldRejectRevokedAccessTokenBeforeCacheLookup() {
        when(revocationService.isAccessTokenRevoked("revoked-access")).thenReturn(true);

        CasBearerTokenAuthenticationToken authentication = new CasBearerTokenAuthenticationToken("revoked-access");

        assertThatThrownBy(() -> newProvider().authenticate(authentication))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("accessToken 已注销");

        verify(tokenCache, never()).get("revoked-access");
        verify(nativeTicketValidator, never()).validate("revoked-access");
    }

    @Test
    void shouldRejectRevokedRefreshTokenBeforeAutoRefresh() {
        when(tokenCache.get("expired-access")).thenReturn(null);
        when(nativeTicketValidator.validate("expired-access")).thenReturn(null);
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
        verify(nativeTicketValidator, never()).validate("access-ok");
        org.assertj.core.api.Assertions.assertThat(result.isAuthenticated()).isTrue();
    }

    @Test
    void shouldReuseCachedAuthoritiesForSameAccessToken() {
        BearerTokenCache realTokenCache = new BearerTokenCache(300, 100);
        CasBearerTokenAuthenticationProvider provider = new CasBearerTokenAuthenticationProvider(
                nativeTicketValidator,
                properties,
                realTokenCache,
                revocationService,
                userDetailsService,
                tokenRefresher);

        CasUserSession session = new CasUserSession();
        session.setUserId("u101");
        session.setNickname("缓存用户");
        CasUserDetails userDetails = new CasUserDetails(session, List.of());

        when(revocationService.isAccessTokenRevoked("access-cached")).thenReturn(false);
        when(nativeTicketValidator.validate("access-cached")).thenReturn(session);
        when(userDetailsService.loadUserByCasAssertion(org.mockito.ArgumentMatchers.eq("u101"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(userDetails);

        Authentication first = provider.authenticate(new CasBearerTokenAuthenticationToken("access-cached"));
        Authentication second = provider.authenticate(new CasBearerTokenAuthenticationToken("access-cached"));

        org.assertj.core.api.Assertions.assertThat(first.isAuthenticated()).isTrue();
        org.assertj.core.api.Assertions.assertThat(second.isAuthenticated()).isTrue();
        verify(nativeTicketValidator, times(1)).validate("access-cached");
        verify(userDetailsService, times(1))
                .loadUserByCasAssertion(org.mockito.ArgumentMatchers.eq("u101"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldReloadAuthoritiesWhenCachedVersionIsStale() {
        BearerTokenCache realTokenCache = new BearerTokenCache(300, 100);
        CasBearerTokenAuthenticationProvider provider = new CasBearerTokenAuthenticationProvider(
                nativeTicketValidator,
                properties,
                realTokenCache,
                revocationService,
                userDetailsService,
                tokenRefresher,
                authorityVersionService);
        properties.getTokenAuth().setAuthorityVersionAttribute("permissionVersion");

        CasUserSession sessionV1 = new CasUserSession();
        sessionV1.setUserId("u300");
        sessionV1.setAttributes(new java.util.HashMap<>(java.util.Map.of("permissionVersion", "v1")));
        CasUserDetails userDetailsV1 = new CasUserDetails(sessionV1, List.of());

        CasUserSession sessionV2 = new CasUserSession();
        sessionV2.setUserId("u300");
        sessionV2.setAttributes(new java.util.HashMap<>(java.util.Map.of("permissionVersion", "v2")));
        CasUserDetails userDetailsV2 = new CasUserDetails(sessionV2, List.of());

        when(revocationService.isAccessTokenRevoked("access-stale")).thenReturn(false);
        when(nativeTicketValidator.validate("access-stale")).thenReturn(sessionV1, sessionV2);
        when(authorityVersionService.isVersionStale("u300", "v1")).thenReturn(true);
        when(authorityVersionService.isVersionStale("u300", "v2")).thenReturn(false);
        when(userDetailsService.loadUserByCasAssertion(org.mockito.ArgumentMatchers.eq("u300"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(userDetailsV1, userDetailsV2);

        Authentication first = provider.authenticate(new CasBearerTokenAuthenticationToken("access-stale"));
        Authentication second = provider.authenticate(new CasBearerTokenAuthenticationToken("access-stale"));

        org.assertj.core.api.Assertions.assertThat(first.isAuthenticated()).isTrue();
        org.assertj.core.api.Assertions.assertThat(second.isAuthenticated()).isTrue();
        verify(nativeTicketValidator, times(2)).validate("access-stale");
        verify(userDetailsService, times(2))
                .loadUserByCasAssertion(org.mockito.ArgumentMatchers.eq("u300"), org.mockito.ArgumentMatchers.any());
    }
}
