package org.apereo.cas.beenest.filter;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.config.CasServiceCredentialProperties;
import org.apereo.cas.beenest.entity.CasServiceCredentialDO;
import org.apereo.cas.beenest.service.CasServiceCredentialService;
import org.apereo.cas.beenest.util.CasRequestSignatureUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CasServiceCredentialFilterTest {

    private CasServiceCredentialService credentialService;
    private CasServiceCredentialProperties properties;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private CasServiceCredentialFilter filter;

    @BeforeEach
    void setUp() {
        credentialService = mock(CasServiceCredentialService.class);
        properties = new CasServiceCredentialProperties();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        filter = new CasServiceCredentialFilter(credentialService, properties, redisTemplate);
    }

    @Test
    void validSignatureAndFreshNonceShouldPass() throws Exception {
        String body = "{\"principal\":\"alice\"}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-001";
        String secret = "plain-secret-abc";
        String signature = CasRequestSignatureUtils.sign(timestamp, nonce, body, secret);

        CasServiceCredentialDO credential = new CasServiceCredentialDO();
        credential.setServiceId(10001L);
        credential.setState("ACTIVE");

        when(credentialService.getCredential(10001L)).thenReturn(credential);
        when(credentialService.resolvePlainSecret(10001L)).thenReturn(secret);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);

        MockHttpServletRequest request = buildRequest(body, timestamp, nonce, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(response));
    }

    @Test
    void repeatedNonceShouldFail() throws Exception {
        String body = "{\"principal\":\"alice\"}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-002";
        String secret = "plain-secret-abc";
        String signature = CasRequestSignatureUtils.sign(timestamp, nonce, body, secret);

        CasServiceCredentialDO credential = new CasServiceCredentialDO();
        credential.setServiceId(10001L);
        credential.setState("ACTIVE");

        when(credentialService.getCredential(10001L)).thenReturn(credential);
        when(credentialService.resolvePlainSecret(10001L)).thenReturn(secret);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        MockHttpServletRequest request = buildRequest(body, timestamp, nonce, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("重放");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredTimestampShouldFail() throws Exception {
        String body = "{\"principal\":\"alice\"}";
        String timestamp = String.valueOf(System.currentTimeMillis() - 10 * 60 * 1000L);
        String nonce = "nonce-003";
        String secret = "plain-secret-abc";
        String signature = CasRequestSignatureUtils.sign(timestamp, nonce, body, secret);

        CasServiceCredentialDO credential = new CasServiceCredentialDO();
        credential.setServiceId(10001L);
        credential.setState("ACTIVE");

        when(credentialService.getCredential(10001L)).thenReturn(credential);
        when(credentialService.resolvePlainSecret(10001L)).thenReturn(secret);

        MockHttpServletRequest request = buildRequest(body, timestamp, nonce, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("过期");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wrongSignatureShouldFail() throws Exception {
        String body = "{\"principal\":\"alice\"}";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = "nonce-004";
        String signature = "bad-signature";

        CasServiceCredentialDO credential = new CasServiceCredentialDO();
        credential.setServiceId(10001L);
        credential.setState("ACTIVE");

        when(credentialService.getCredential(10001L)).thenReturn(credential);
        when(credentialService.resolvePlainSecret(10001L)).thenReturn("plain-secret-abc");

        MockHttpServletRequest request = buildRequest(body, timestamp, nonce, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("签名");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private MockHttpServletRequest buildRequest(String body, String timestamp, String nonce, String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setServletPath("/cas/app/login");
        request.setRequestURI("/cas/app/login");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader(CasConstant.BUSINESS_SERVICE_ID_HEADER, "10001");
        request.addHeader(CasConstant.BUSINESS_TIMESTAMP_HEADER, timestamp);
        request.addHeader(CasConstant.BUSINESS_NONCE_HEADER, nonce);
        request.addHeader(CasConstant.BUSINESS_SIGNATURE_HEADER, signature);
        request.setContentType("application/json");
        return request;
    }
}
