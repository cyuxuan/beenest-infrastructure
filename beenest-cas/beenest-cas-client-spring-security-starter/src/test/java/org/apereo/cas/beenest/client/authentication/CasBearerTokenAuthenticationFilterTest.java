package org.apereo.cas.beenest.client.authentication;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bearer Token 过滤器单元测试。
 */
class CasBearerTokenAuthenticationFilterTest {

    @Test
    void shouldReturn503WhenAuthenticationProviderThrowsRuntimeException() throws Exception {
        CasBearerTokenAuthenticationProvider authenticationProvider = mock(CasBearerTokenAuthenticationProvider.class);
        when(authenticationProvider.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));

        CasBearerTokenAuthenticationFilter filter = new CasBearerTokenAuthenticationFilter(
                () -> authenticationProvider,
                () -> null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Bearer 认证组件异常");
    }
}
