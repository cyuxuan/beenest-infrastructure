package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.client.authentication.AttributePrincipal;
import org.apereo.cas.client.authentication.AttributePrincipalImpl;
import org.apereo.cas.client.validation.Assertion;
import org.apereo.cas.client.validation.AssertionImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultCasUserDetailsServiceTest {

    @Test
    void shouldExtractAuthoritiesFromAssertionAttributes() {
        ObjectProvider<CasUserRegistrationService> registrationServiceProvider = mock(ObjectProvider.class);
        when(registrationServiceProvider.getIfAvailable()).thenReturn(null);

        DefaultCasUserDetailsService service = new DefaultCasUserDetailsService(registrationServiceProvider);

        Assertion assertion = buildAssertion("U10001", Map.of(
                "roles", List.of("admin", "pilot"),
                "permissions", List.of("user.read", "user.write")
        ));

        CasUserDetails details = (CasUserDetails) service.loadUserByCasAssertion("U10001", assertion);

        assertThat(details.getUserId()).isEqualTo("U10001");
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_PILOT", "user.read", "user.write");
    }

    @Test
    void shouldFallbackToRoleUserAndAutoRegisterWhenAvailable() {
        ObjectProvider<CasUserRegistrationService> registrationServiceProvider = mock(ObjectProvider.class);
        CasUserRegistrationService registrationService = mock(CasUserRegistrationService.class);
        when(registrationServiceProvider.getIfAvailable()).thenReturn(registrationService);
        when(registrationService.userExists("U10002")).thenReturn(false);
        when(registrationService.canAutoRegister(any())).thenReturn(true);
        when(registrationService.registerFromCas(any())).thenReturn("LOCAL-10002");

        DefaultCasUserDetailsService service = new DefaultCasUserDetailsService(registrationServiceProvider);

        Assertion assertion = buildAssertion("U10002", Map.of());
        CasUserDetails details = (CasUserDetails) service.loadUserByCasAssertion("U10002", assertion);

        assertThat(details.getUserId()).isEqualTo("LOCAL-10002");
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        verify(registrationService).registerFromCas(any());
    }

    private Assertion buildAssertion(String userId, Map<String, Object> attributes) {
        AttributePrincipal principal = new AttributePrincipalImpl(userId, attributes);
        return new AssertionImpl(principal, attributes);
    }
}
