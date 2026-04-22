package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.config.CasServiceCredentialProperties;
import org.apereo.cas.beenest.entity.CasServiceCredentialDO;
import org.apereo.cas.beenest.filter.CasServiceCredentialFilter;
import org.apereo.cas.beenest.mapper.CasServiceCredentialMapper;
import org.apereo.cas.beenest.util.AesEncryptionUtils;
import org.apereo.cas.beenest.util.CasRequestSignatureUtils;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

class CasServiceCredentialServiceTest {

    private static final String SERVICE_ID = "10001";
    private static final String SERVICE_SECRET = "plain-secret-abc";
    private static final String REQUEST_BODY = "{\"principal\":\"alice\"}";

    private CasServiceCredentialService credentialServiceMock;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private CasServiceCredentialFilter filter;

    @BeforeEach
    void setUpFilterFixture() {
        credentialServiceMock = mock(CasServiceCredentialService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(credentialServiceMock.resolvePlainSecret(10001L)).thenReturn(SERVICE_SECRET);

        CasServiceCredentialProperties properties = new CasServiceCredentialProperties();
        properties.setAllowedClockSkewSeconds(300);
        properties.setNonceTtlSeconds(300);
        filter = new CasServiceCredentialFilter(credentialServiceMock, properties, redisTemplate);
    }

    @Test
    void issueCredentialShouldEncryptSecretAndSupportDecryption() {
        CasServiceCredentialMapper mapper = mock(CasServiceCredentialMapper.class);
        CasServiceCredentialProperties properties = new CasServiceCredentialProperties();
        CasServiceCredentialService service = spy(new CasServiceCredentialService(mapper, properties));
        doAnswer(invocation -> "plain-secret-abc").when(service).generatePlainSecret();

        AtomicReference<CasServiceCredentialDO> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(mapper).insert(any(CasServiceCredentialDO.class));
        when(mapper.selectByServiceId(10001L)).thenAnswer(invocation -> saved.get());

        var issued = service.issueCredential(10001L);

        assertThat(issued.plainSecret()).isEqualTo("plain-secret-abc");
        assertThat(issued.secretVersion()).isEqualTo(1L);

        CasServiceCredentialDO stored = service.getCredential(10001L);
        assertThat(stored).isNotNull();
        assertThat(stored.getSecretHash()).isNotEqualTo("plain-secret-abc");
        assertThat(AesEncryptionUtils.decrypt(stored.getSecretHash(), properties.getEncryptionKey()))
                .isEqualTo("plain-secret-abc");
        assertThat(service.resolvePlainSecret(10001L)).isEqualTo("plain-secret-abc");
    }

    @Test
    void validSignatureAndFreshNoncePasses() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-valid";
        String signature = CasRequestSignatureUtils.sign(timestamp, nonce, REQUEST_BODY, SERVICE_SECRET);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(Boolean.TRUE);

        MockHttpServletResponse response = executeProtectedRequest(timestamp, nonce, signature, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        verify(credentialServiceMock).resolvePlainSecret(10001L);
    }

    @Test
    void repeatedNonceFails() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-replay";
        String signature = CasRequestSignatureUtils.sign(timestamp, nonce, REQUEST_BODY, SERVICE_SECRET);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(Boolean.FALSE);

        MockHttpServletResponse response = executeProtectedRequest(timestamp, nonce, signature, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("nonce");
    }

    @Test
    void expiredTimestampFails() throws Exception {
        String timestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());
        String nonce = "nonce-expired";
        String signature = CasRequestSignatureUtils.sign(timestamp, nonce, REQUEST_BODY, SERVICE_SECRET);

        MockHttpServletResponse response = executeProtectedRequest(timestamp, nonce, signature, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("timestamp");
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void wrongSignatureFails() throws Exception {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-wrong-signature";
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(Boolean.TRUE);

        MockHttpServletResponse response = executeProtectedRequest(timestamp, nonce, "bad-signature", mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("signature");
    }

    private MockHttpServletResponse executeProtectedRequest(final String timestamp,
                                                            final String nonce,
                                                            final String signature,
                                                            final FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/cas/app/login");
        request.setServletPath("/app/login");
        request.setContentType("application/json");
        request.setContent(REQUEST_BODY.getBytes(StandardCharsets.UTF_8));
        request.addHeader(CasConstant.SERVICE_ID_HEADER, SERVICE_ID);
        request.addHeader(CasConstant.REQUEST_TIMESTAMP_HEADER, timestamp);
        request.addHeader(CasConstant.REQUEST_NONCE_HEADER, nonce);
        request.addHeader(CasConstant.REQUEST_SIGNATURE_HEADER, signature);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
