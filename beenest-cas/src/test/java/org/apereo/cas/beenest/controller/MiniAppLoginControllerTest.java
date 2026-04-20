package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.config.TokenTtlProperties;
import org.apereo.cas.beenest.dto.MiniAppLoginDTO;
import org.apereo.cas.beenest.dto.MiniAppLogoutDTO;
import org.apereo.cas.beenest.dto.MiniAppRefreshDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.AppAccessService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小程序登录控制器单元测试。
 * <p>
 * 模拟业务系统通过 starter 代理发起的登录请求，验证：
 * - 微信/抖音/支付宝三个平台的正常登录流程
 * - refresh token 续期（轮换模式和非轮换模式）
 * - logout 清理 TGT 和 refreshToken
 * - 参数校验（空授权码、空 refreshToken 等）
 * - 认证失败的错误处理
 * - 通过请求头传递 businessServiceId 触发自动赋权
 */
@SuppressWarnings("unchecked")
class MiniAppLoginControllerTest {

    private AuthenticationSystemSupport authSupport;
    private TicketRegistry ticketRegistry;
    private DefaultTicketFactory defaultTicketFactory;
    private AuthAuditService auditService;
    private AppAccessService appAccessService;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TokenTtlProperties ttlProperties;
    private MiniAppLoginController controller;

    @BeforeEach
    void setUp() {
        authSupport = mock(AuthenticationSystemSupport.class);
        ticketRegistry = mock(TicketRegistry.class);
        defaultTicketFactory = mock(DefaultTicketFactory.class);
        auditService = mock(AuthAuditService.class);
        appAccessService = mock(AppAccessService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ttlProperties = new TokenTtlProperties();
        ttlProperties.setAccessTokenTtlSeconds(3600);
        ttlProperties.setRefreshTokenTtlSeconds(7200);

        controller = new MiniAppLoginController(
                authSupport, ticketRegistry, defaultTicketFactory,
                auditService, appAccessService, redisTemplate, ttlProperties
        );
    }

    // ===== 通用辅助方法 =====

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private void stubSuccessfulAuth(String userId, String loginType) throws Throwable {
        Principal principal = mock(Principal.class);
        when(principal.getId()).thenReturn(userId);
        when(principal.getAttributes()).thenReturn(Map.of("loginType", List.of(loginType)));

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);

        AuthenticationResult authResult = mock(AuthenticationResult.class);
        when(authResult.getAuthentication()).thenReturn(authentication);
        when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

        stubTgtCreation("TGT-TEST");
    }

    private void stubTgtCreation(String tgtId) {
        TicketGrantingTicketFactory<TicketGrantingTicket> tgtFactory = mock(TicketGrantingTicketFactory.class);
        TicketGrantingTicket tgt = mock(TicketGrantingTicket.class);
        when(tgt.getId()).thenReturn(tgtId);
        try {
            when(tgtFactory.create(any(Authentication.class), isNull())).thenReturn(tgt);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        when(defaultTicketFactory.get(TicketGrantingTicket.class)).thenReturn((TicketGrantingTicketFactory) tgtFactory);
    }

    // ===== 微信小程序登录 =====

    @Nested
    class WechatLogin {

        @Test
        void shouldIssueTokenSuccessfully() throws Throwable {
            Principal principal = mock(Principal.class);
            when(principal.getId()).thenReturn("U10001");
            when(principal.getAttributes()).thenReturn(Map.of(
                    "loginType", List.of("WECHAT"),
                    "nickname", List.of("微信用户"),
                    "firstLogin", List.of(false)
            ));

            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(principal);

            AuthenticationResult authResult = mock(AuthenticationResult.class);
            when(authResult.getAuthentication()).thenReturn(authentication);
            when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

            stubTgtCreation("TGT-TEST");

            MockHttpServletRequest request = stubRequest();
            request.addHeader(CasConstant.BUSINESS_SERVICE_ID_HEADER, "20001");

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("wx_code_abc");
            dto.setUserType("CUSTOMER");
            dto.setNickname("微信用户");

            R<TokenResponseDTO> response = controller.wechatLogin(dto, request);

            assertThat(response.isSuccess()).isTrue();
            TokenResponseDTO data = response.getData();
            assertThat(data.getAccessToken()).isEqualTo("TGT-TEST");
            assertThat(data.getTgt()).isEqualTo("TGT-TEST");
            assertThat(data.getRefreshToken()).isNotBlank();
            assertThat(data.getUserId()).isEqualTo("U10001");
            assertThat(data.getExpiresIn()).isEqualTo(3600);
            assertThat(data.getAttributes())
                    .containsEntry("loginType", "WECHAT")
                    .containsEntry("nickname", "微信用户")
                    .containsEntry("firstLogin", false);

            verify(appAccessService).autoGrantOnRegister("U10001", 20001L);
            verify(ticketRegistry).addTicket(any(TicketGrantingTicket.class));
        }

        @Test
        void shouldRejectEmptyCode() {
            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("");

            R<TokenResponseDTO> response = controller.wechatLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(400);
            assertThat(response.getMessage()).contains("微信授权码不能为空");
        }

        @Test
        void shouldSupportPhoneCodeForAccountMerging() throws Throwable {
            stubSuccessfulAuth("U10002", "WECHAT");

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("wx_code_123");
            dto.setPhoneCode("phone_code_wx");
            dto.setUserType("PILOT");

            R<TokenResponseDTO> response = controller.wechatLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData().getUserId()).isEqualTo("U10002");
        }
    }

    // ===== 抖音小程序登录 =====

    @Nested
    class DouyinLogin {

        @Test
        void shouldIssueTokenSuccessfully() throws Throwable {
            stubSuccessfulAuth("U20001", "DOUYIN_MINI");

            MockHttpServletRequest request = stubRequest();
            request.addHeader(CasConstant.SERVICE_ID_HEADER, "20002");

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setDouyinCode("dy_code_xyz");
            dto.setUserType("CUSTOMER");
            dto.setNickname("抖音用户");

            R<TokenResponseDTO> response = controller.douyinLogin(dto, request);

            assertThat(response.isSuccess()).isTrue();
            TokenResponseDTO data = response.getData();
            assertThat(data.getAccessToken()).isEqualTo("TGT-TEST");
            assertThat(data.getRefreshToken()).isNotBlank();
            assertThat(data.getUserId()).isEqualTo("U20001");
            assertThat(data.getAttributes()).containsKey("loginType");

            verify(appAccessService).autoGrantOnRegister("U20001", 20002L);
        }

        @Test
        void shouldRejectEmptyDouyinCode() {
            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setDouyinCode(null);

            R<TokenResponseDTO> response = controller.douyinLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(400);
            assertThat(response.getMessage()).contains("抖音授权码不能为空");
        }
    }

    // ===== 支付宝小程序登录 =====

    @Nested
    class AlipayLogin {

        @Test
        void shouldIssueTokenWithAuthCode() throws Throwable {
            stubSuccessfulAuth("U30001", "ALIPAY_MINI");

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setAuthCode("alipay_auth_abc");
            dto.setUserType("CUSTOMER");

            R<TokenResponseDTO> response = controller.alipayLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData().getUserId()).isEqualTo("U30001");
            assertThat(response.getData().getAccessToken()).isEqualTo("TGT-TEST");
        }

        @Test
        void shouldIssueTokenWithPhoneCodeOnly() throws Throwable {
            stubSuccessfulAuth("U30002", "ALIPAY_MINI");

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setPhoneCode("phone_code_ali");
            dto.setUserType("PILOT");

            R<TokenResponseDTO> response = controller.alipayLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData().getUserId()).isEqualTo("U30002");
        }

        @Test
        void shouldRejectWhenBothAuthCodeAndPhoneCodeEmpty() {
            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setAuthCode(null);
            dto.setPhoneCode(null);

            R<TokenResponseDTO> response = controller.alipayLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(400);
            assertThat(response.getMessage()).contains("支付宝授权码或手机号授权码不能同时为空");
        }
    }

    // ===== Refresh Token 续期 =====

    @Nested
    class Refresh {

        @Test
        void shouldRotateRefreshTokenInRotationMode() throws Throwable {
            ttlProperties.setRefreshTokenRotation(true);

            String oldRefreshToken = "old-rt-abc";
            String redisKey = CasConstant.REDIS_MINIAPP_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
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

            MiniAppRefreshDTO dto = new MiniAppRefreshDTO();
            dto.setRefreshToken(oldRefreshToken);

            R<TokenResponseDTO> response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData().getAccessToken()).isEqualTo("TGT-NEW");
            assertThat(response.getData().getRefreshToken()).isNotBlank();
            assertThat(response.getData().getRefreshToken()).isNotEqualTo(oldRefreshToken);

            verify(valueOperations).getAndDelete(redisKey);
        }

        @Test
        void shouldKeepOldRefreshTokenInNonRotationMode() throws Throwable {
            ttlProperties.setRefreshTokenRotation(false);

            String oldRefreshToken = "old-rt-xyz";
            String redisKey = CasConstant.REDIS_MINIAPP_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
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

            MiniAppRefreshDTO dto = new MiniAppRefreshDTO();
            dto.setRefreshToken(oldRefreshToken);

            R<TokenResponseDTO> response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            verify(valueOperations).get(redisKey);
        }

        @Test
        void shouldRejectExpiredRefreshToken() {
            String oldRefreshToken = "expired-rt";
            String redisKey = CasConstant.REDIS_MINIAPP_TOKEN_PREFIX + "refresh:" + oldRefreshToken;
            when(valueOperations.getAndDelete(redisKey)).thenReturn(null);

            MiniAppRefreshDTO dto = new MiniAppRefreshDTO();
            dto.setRefreshToken(oldRefreshToken);

            R<TokenResponseDTO> response = controller.refresh(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(401);
            assertThat(response.getMessage()).contains("refreshToken 已过期或已被使用");
        }
    }

    // ===== Logout =====

    @Nested
    class Logout {

        @Test
        void shouldDeleteRefreshTokenAndDestroyTgt() throws Throwable {
            String refreshToken = "rt-to-delete";
            String accessToken = "TGT-TO-DESTROY";

            Principal principal = mock(Principal.class);
            when(principal.getId()).thenReturn("U10001");
            Authentication authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn(principal);

            TicketGrantingTicket tgt = mock(TicketGrantingTicket.class);
            when(tgt.getAuthentication()).thenReturn(authentication);
            when(ticketRegistry.getTicket(accessToken, TicketGrantingTicket.class)).thenReturn(tgt);

            MiniAppLogoutDTO dto = new MiniAppLogoutDTO();
            dto.setRefreshToken(refreshToken);
            dto.setAccessToken(accessToken);

            R<Void> response = controller.logout(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            verify(redisTemplate).delete(CasConstant.REDIS_MINIAPP_TOKEN_PREFIX + "refresh:" + refreshToken);
            verify(ticketRegistry).deleteTicket(accessToken);
        }

        @Test
        void shouldHandleMissingTgtGracefully() throws Throwable {
            String accessToken = "TGT-NOT-EXIST";
            when(ticketRegistry.getTicket(accessToken, TicketGrantingTicket.class))
                    .thenThrow(new RuntimeException("ticket not found"));

            MiniAppLogoutDTO dto = new MiniAppLogoutDTO();
            dto.setAccessToken(accessToken);

            R<Void> response = controller.logout(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
        }
    }

    // ===== 认证失败场景 =====

    @Nested
    class AuthenticationFailure {

        @Test
        void shouldReturn401WhenAuthResultIsNull() throws Throwable {
            when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(null);

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("wx_code");

            R<TokenResponseDTO> response = controller.wechatLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(401);
        }

        @Test
        void shouldReturn401WhenAuthenticationIsNull() throws Throwable {
            AuthenticationResult authResult = mock(AuthenticationResult.class);
            when(authResult.getAuthentication()).thenReturn(null);
            when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("wx_code");

            R<TokenResponseDTO> response = controller.wechatLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(401);
        }
    }
}
