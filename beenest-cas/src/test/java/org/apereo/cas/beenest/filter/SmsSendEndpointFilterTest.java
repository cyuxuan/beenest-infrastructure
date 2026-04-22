package org.apereo.cas.beenest.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apereo.cas.beenest.service.SmsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SmsSendEndpointFilterTest {

    @Test
    void shouldInterceptCasSmsSendRequests() throws Exception {
        SmsService smsService = mock(SmsService.class);
        SmsSendEndpointFilter filter = new SmsSendEndpointFilter(smsService, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/cas/sms/send");
        request.setServletPath("/sms/send");
        request.setParameter("phone", "13800138000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentAsString()).contains("\"phone\":\"13800138000\"");
        verify(smsService).sendOtp("13800138000");
        verifyNoInteractions(chain);
    }
}
