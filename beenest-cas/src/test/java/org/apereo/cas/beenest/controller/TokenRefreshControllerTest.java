package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.TokenRefreshRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketGrantingTicketFactory;
import org.apereo.cas.ticket.factory.DefaultTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一 Token 续期控制器测试。
 */
@SuppressWarnings("unchecked")
class TokenRefreshControllerTest {

    private AuthenticationSystemSupport authSupport;
    private TicketRegistry ticketRegistry;
    private DefaultTicketFactory defaultTicketFactory;
    private AuthAuditService auditService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TokenTtlProperties ttlProperties;
    private TokenRefreshController controller;

    @BeforeEach
    void setUp() {
        authSupport = mock(AuthenticationSystemSupport.class);
        ticketRegistry = mock(TicketRegistry.class);
        defaultTicketFactory = mock(DefaultTicketFactory.class);
        auditService = mock(AuthAuditService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ttlProperties = new TokenTtlProperties();
        ttlProperties.setAccessTokenTtlSeconds(3600);
        ttlProperties.setRefreshTokenTtlSeconds(7200);

        controller = new TokenRefreshController(
                authSupport, ticketRegistry, defaultTicketFactory, auditService, redisTemplate, ttlProperties);
    }

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private void stubTgtCreation(String tgtId) {
        TicketGrantingTicketFactory tgtFactory = mock(TicketGrantingTicketFactory.class);
        TicketGrantingTicket tgt = mock(TicketGrantingTicket.class);
        when(tgt.getId()).thenReturn(tgtId);
        try {
            when(tgtFactory.create(any(Authentication.class), isNull())).thenReturn(tgt);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        when(defaultTicketFactory.get(TicketGrantingTicket.class)).thenReturn((TicketGrantingTicketFactory) tgtFactory);
    }

    @Nested
    class RotationMode {

        @Test
        void shouldRotateUnifiedRefreshToken() throws Throwable {
            String oldRefreshToken = "old-rt-abc";
            String redisKey = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
            when(valueOperations.getAndDelete(redisKey)).thenReturn("U10001");

            Principal principal = mock(Principal.class);
            when(principal.getId()).thenReturn("U10001");
            when(principal.getAttributes()).thenReturn(Map.of());

            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(principal);
            AuthenticationResult authResult = mock(AuthenticationResult.class);
            when(authResult.getAuthentication()).thenReturn(authentication);
            when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

            stubTgtCreation("TGT-NEW");

            TokenRefreshRequestDTO dto = new TokenRefreshRequestDTO();
            dto.setRefreshToken(oldRefreshToken);

            var response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            TokenResponseDTO data = response.getData();
            assertThat(data.getAccessToken()).isEqualTo("TGT-NEW");
            assertThat(data.getRefreshToken()).isNotBlank();
            assertThat(data.getRefreshToken()).isNotEqualTo(oldRefreshToken);

            verify(valueOperations).getAndDelete(redisKey);
        }
    }

    @Nested
    class NonRotationMode {

        @Test
        void shouldKeepUnifiedRefreshTokenWhenRotationDisabled() throws Throwable {
            ttlProperties.setRefreshTokenRotation(false);

            String oldRefreshToken = "old-rt-xyz";
            String redisKey = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
            when(valueOperations.get(redisKey)).thenReturn("U10002");

            Principal principal = mock(Principal.class);
            when(principal.getId()).thenReturn("U10002");
            when(principal.getAttributes()).thenReturn(Map.of());

            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(principal);
            AuthenticationResult authResult = mock(AuthenticationResult.class);
            when(authResult.getAuthentication()).thenReturn(authentication);
            when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

            stubTgtCreation("TGT-REFRESH");

            TokenRefreshRequestDTO dto = new TokenRefreshRequestDTO();
            dto.setRefreshToken(oldRefreshToken);

            var response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            verify(valueOperations).get(redisKey);
        }
    }

    @Nested
    class FailureMode {

        @Test
        void shouldRejectExpiredRefreshToken() {
            String oldRefreshToken = "expired-rt";
            String redisKey = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
            when(valueOperations.getAndDelete(redisKey)).thenReturn(null);

            TokenRefreshRequestDTO dto = new TokenRefreshRequestDTO();
            dto.setRefreshToken(oldRefreshToken);

            var response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(401);
            assertThat(response.getMessage()).contains("refreshToken 已过期或已被使用");
        }
    }

    @Nested
    class MigrationFallback {

        @Test
        void shouldConsumeLegacyAppPrefixToken() throws Throwable {
            String oldRefreshToken = "legacy-app-rt";
            String unifiedKey = CasConstant.REDIS_REFRESH_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
            String legacyKey = CasConstant.REDIS_APP_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
            when(valueOperations.getAndDelete(unifiedKey)).thenReturn(null);
            when(valueOperations.getAndDelete(legacyKey)).thenReturn("U10003");

            Principal principal = mock(Principal.class);
            when(principal.getId()).thenReturn("U10003");
            when(principal.getAttributes()).thenReturn(Map.of());

            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(principal);
            AuthenticationResult authResult = mock(AuthenticationResult.class);
            when(authResult.getAuthentication()).thenReturn(authentication);
            when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

            stubTgtCreation("TGT-LEGACY");

            TokenRefreshRequestDTO dto = new TokenRefreshRequestDTO();
            dto.setRefreshToken(oldRefreshToken);

            var response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData().getAccessToken()).isEqualTo("TGT-LEGACY");
            verify(valueOperations).getAndDelete(unifiedKey);
            verify(valueOperations).getAndDelete(legacyKey);
        }
    }
}
