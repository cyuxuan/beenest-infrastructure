package org.apereo.cas.beenest.filter;

import org.apereo.cas.beenest.common.constant.CasConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminApiAuthFilterTest {

    @Test
    void rejectsMissingToken() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AdminApiAuthFilter filter = new AdminApiAuthFilter("secret-token", redisTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cas/admin/user/U1001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("\"code\":401");
        verifyNoInteractions(chain);
    }

    @Test
    void acceptsConfiguredToken() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AdminApiAuthFilter filter = new AdminApiAuthFilter("secret-token", redisTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cas/admin/user/U1001");
        request.addHeader(CasConstant.ADMIN_TOKEN_HEADER, "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }
}
