package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.AppLoginRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.AuthAuditService;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.beenest.service.AppAccessService;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.TicketGrantingTicketFactory;
import org.apereo.cas.ticket.factory.DefaultTicketFactory;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppLoginControllerTest {

    @Test
    void loginShouldIssueTokenForPasswordMode() throws Throwable {
        AuthenticationSystemSupport authSupport = mock(AuthenticationSystemSupport.class);
        TicketRegistry ticketRegistry = mock(TicketRegistry.class);
        DefaultTicketFactory defaultTicketFactory = mock(DefaultTicketFactory.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthAuditService auditService = mock(AuthAuditService.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        TokenTtlProperties ttlProperties = new TokenTtlProperties();
        ttlProperties.setAccessTokenTtlSeconds(3600);
        ttlProperties.setRefreshTokenTtlSeconds(7200);

        Principal principal = mock(Principal.class);
        when(principal.getId()).thenReturn("U10001");
        when(principal.getAttributes()).thenReturn(Map.of(
                "loginType", List.of("APP"),
                "nickname", List.of("Alice"),
                "firstLogin", List.of(false)
        ));

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        AuthenticationResult authResult = mock(AuthenticationResult.class);
        when(authResult.getAuthentication()).thenReturn(authentication);
        when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

        TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory = mock(TicketGrantingTicketFactory.class);
        TicketGrantingTicket tgt = mock(TicketGrantingTicket.class);
        when(tgt.getId()).thenReturn("TGT-1");
        when(tgtFactory.create(eq(authentication), isNull())).thenReturn(tgt);
        when(defaultTicketFactory.get(TicketGrantingTicket.class)).thenReturn((TicketGrantingTicketFactory) tgtFactory);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        AppLoginController controller = new AppLoginController(
                authSupport, ticketRegistry, defaultTicketFactory, redisTemplate, auditService, appAccessService, userMapper, ttlProperties
        );

        AppLoginRequestDTO request = new AppLoginRequestDTO();
        request.setPrincipal("alice");
        request.setPassword("secret");
        request.setLoginMethod("password");
        request.setDeviceId("device-1");
        request.setRememberMe(true);

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("User-Agent", "JUnit");
        httpRequest.addHeader("X-BEENEST-SERVICE-ID", "10001");

        var response = controller.login(request, httpRequest);

        assertThat(response.isSuccess()).isTrue();
        TokenResponseDTO data = response.getData();
        assertThat(data.getAccessToken()).isEqualTo("TGT-1");
        assertThat(data.getRefreshToken()).isNotBlank();
        assertThat(data.getUserId()).isEqualTo("U10001");
        assertThat(data.getExpiresIn()).isEqualTo(3600);
        assertThat(data.getAttributes())
                .containsEntry("loginType", "APP")
                .containsEntry("nickname", "Alice")
                .containsEntry("firstLogin", false);
        org.mockito.Mockito.verify(appAccessService).autoGrantOnRegister("U10001", 10001L);
    }

    @Test
    void loginShouldRejectRefreshThroughPasswordEndpoint() {
        AuthenticationSystemSupport authSupport = mock(AuthenticationSystemSupport.class);
        TicketRegistry ticketRegistry = mock(TicketRegistry.class);
        DefaultTicketFactory defaultTicketFactory = mock(DefaultTicketFactory.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthAuditService auditService = mock(AuthAuditService.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        TokenTtlProperties ttlProperties = new TokenTtlProperties();

        AppLoginController controller = new AppLoginController(
                authSupport, ticketRegistry, defaultTicketFactory, redisTemplate, auditService, appAccessService, userMapper, ttlProperties
        );

        AppLoginRequestDTO request = new AppLoginRequestDTO();
        request.setLoginMethod("refresh");
        request.setRefreshToken("old-refresh");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("User-Agent", "JUnit");

        var response = controller.login(request, httpRequest);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("refresh");
    }

    @Test
    void loginShouldRejectSmsModeOnAppEndpoint() {
        AuthenticationSystemSupport authSupport = mock(AuthenticationSystemSupport.class);
        TicketRegistry ticketRegistry = mock(TicketRegistry.class);
        DefaultTicketFactory defaultTicketFactory = mock(DefaultTicketFactory.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthAuditService auditService = mock(AuthAuditService.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        TokenTtlProperties ttlProperties = new TokenTtlProperties();

        AppLoginController controller = new AppLoginController(
                authSupport, ticketRegistry, defaultTicketFactory, redisTemplate, auditService, appAccessService, userMapper, ttlProperties
        );

        AppLoginRequestDTO request = new AppLoginRequestDTO();
        request.setLoginMethod("sms");
        request.setPrincipal("13800138000");
        request.setOtpCode("123456");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("User-Agent", "JUnit");

        var response = controller.login(request, httpRequest);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("短信认证入口");
    }

    @Test
    void loginShouldRejectUnknownMode() {
        AuthenticationSystemSupport authSupport = mock(AuthenticationSystemSupport.class);
        TicketRegistry ticketRegistry = mock(TicketRegistry.class);
        DefaultTicketFactory defaultTicketFactory = mock(DefaultTicketFactory.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AuthAuditService auditService = mock(AuthAuditService.class);
        AppAccessService appAccessService = mock(AppAccessService.class);
        UnifiedUserMapper userMapper = mock(UnifiedUserMapper.class);
        TokenTtlProperties ttlProperties = new TokenTtlProperties();

        AppLoginController controller = new AppLoginController(
                authSupport, ticketRegistry, defaultTicketFactory, redisTemplate, auditService, appAccessService, userMapper, ttlProperties
        );

        AppLoginRequestDTO request = new AppLoginRequestDTO();
        request.setLoginMethod("wechat");
        request.setPrincipal("alice");
        request.setPassword("secret");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("User-Agent", "JUnit");

        var response = controller.login(request, httpRequest);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("不支持");
    }
}
