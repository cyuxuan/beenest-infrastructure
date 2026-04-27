package org.apereo.cas.beenest.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.apereo.cas.beenest.dto.MiniAppLoginDTO;
import org.apereo.cas.beenest.dto.MiniAppLogoutDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.CasNativeLoginService;
import org.apereo.cas.beenest.service.CasTokenLifecycleService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小程序登录控制器单元测试。
 */
class MiniAppLoginControllerTest {

    private MiniAppLoginController createController(CasNativeLoginService nativeLoginService,
                                                     CasTokenLifecycleService tokenLifecycleService) {
        return new MiniAppLoginController(nativeLoginService, tokenLifecycleService);
    }

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Nested
    class WechatLogin {

        @Test
        void shouldIssueTokenSuccessfully() throws Throwable {
            CasNativeLoginService nativeLoginService = mock(CasNativeLoginService.class);
            CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);

            TokenResponseDTO tokenResponse = new TokenResponseDTO();
            tokenResponse.setAccessToken("TGT-TEST");
            tokenResponse.setRefreshToken("RT-TEST");
            tokenResponse.setUserId("U10001");
            when(nativeLoginService.login(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("WECHAT"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.apereo.cas.beenest.common.response.R.ok(tokenResponse));

            MiniAppLoginController controller = createController(nativeLoginService, tokenLifecycleService);

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("wx_code_abc");
            dto.setUserType("CUSTOMER");
            dto.setNickname("微信用户");

            var response = controller.wechatLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData()).isSameAs(tokenResponse);
        }

        @Test
        void shouldRejectEmptyCode() {
            MiniAppLoginController controller = createController(
                mock(CasNativeLoginService.class), mock(CasTokenLifecycleService.class));

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setCode("");

            var response = controller.wechatLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(400);
            assertThat(response.getMessage()).contains("微信授权码不能为空");
        }
    }

    @Nested
    class DouyinLogin {

        @Test
        void shouldIssueTokenSuccessfully() throws Throwable {
            CasNativeLoginService nativeLoginService = mock(CasNativeLoginService.class);
            CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);

            TokenResponseDTO tokenResponse = new TokenResponseDTO();
            tokenResponse.setAccessToken("TGT-TEST");
            tokenResponse.setRefreshToken("RT-TEST");
            tokenResponse.setUserId("U20001");
            when(nativeLoginService.login(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("DOUYIN_MINI"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.apereo.cas.beenest.common.response.R.ok(tokenResponse));

            MiniAppLoginController controller = createController(nativeLoginService, tokenLifecycleService);

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setDouyinCode("dy_code_xyz");
            dto.setUserType("CUSTOMER");
            dto.setNickname("抖音用户");

            var response = controller.douyinLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData()).isSameAs(tokenResponse);
        }
    }

    @Nested
    class AlipayLogin {

        @Test
        void shouldIssueTokenWithAuthCode() throws Throwable {
            CasNativeLoginService nativeLoginService = mock(CasNativeLoginService.class);
            CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);

            TokenResponseDTO tokenResponse = new TokenResponseDTO();
            tokenResponse.setAccessToken("TGT-TEST");
            tokenResponse.setRefreshToken("RT-TEST");
            tokenResponse.setUserId("U30001");
            when(nativeLoginService.login(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("ALIPAY_MINI"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.apereo.cas.beenest.common.response.R.ok(tokenResponse));

            MiniAppLoginController controller = createController(nativeLoginService, tokenLifecycleService);

            MiniAppLoginDTO dto = new MiniAppLoginDTO();
            dto.setAuthCode("alipay_auth_abc");
            dto.setUserType("CUSTOMER");

            var response = controller.alipayLogin(dto, stubRequest());

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData()).isSameAs(tokenResponse);
        }
    }

    @Test
    void logoutShouldRevokeTokenThroughSharedService() {
        CasNativeLoginService nativeLoginService = mock(CasNativeLoginService.class);
        CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);
        MiniAppLoginController controller = createController(nativeLoginService, tokenLifecycleService);

        MiniAppLogoutDTO dto = new MiniAppLogoutDTO();
        dto.setAccessToken("TGT-1");
        dto.setRefreshToken("RT-1");

        var response = controller.logout(dto, stubRequest());

        assertThat(response.isSuccess()).isTrue();
        verify(tokenLifecycleService).revokeTokens("TGT-1", "RT-1");
    }
}
