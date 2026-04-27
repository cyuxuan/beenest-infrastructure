package org.apereo.cas.beenest.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.apereo.cas.beenest.dto.TokenRefreshRequestDTO;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.apereo.cas.beenest.service.CasNativeLoginService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一 Token 续期控制器测试。
 */
class TokenRefreshControllerTest {

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Nested
    class RotationMode {

        @Test
        void shouldRotateUnifiedRefreshToken() {
            CasNativeLoginService nativeLoginService = mock(CasNativeLoginService.class);
            MockHttpServletRequest request = stubRequest();

            TokenResponseDTO tokenResponse = new TokenResponseDTO();
            tokenResponse.setAccessToken("TGT-NEW");
            tokenResponse.setRefreshToken("RT-NEW");
            tokenResponse.setUserId("U10001");
            when(nativeLoginService.refresh("old-rt-abc", request))
                .thenReturn(org.apereo.cas.beenest.common.response.R.ok(tokenResponse));

            TokenRefreshController controller = new TokenRefreshController(nativeLoginService);

            TokenRefreshRequestDTO dto = new TokenRefreshRequestDTO();
            dto.setRefreshToken("old-rt-abc");

            var response = controller.refresh(dto, request);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getData()).isSameAs(tokenResponse);
            verify(nativeLoginService).refresh("old-rt-abc", request);
        }
    }

    @Nested
    class FailureMode {

        @Test
        void shouldRejectExpiredRefreshToken() {
            CasNativeLoginService nativeLoginService = mock(CasNativeLoginService.class);
            MockHttpServletRequest request = stubRequest();
            when(nativeLoginService.refresh("expired-rt", request))
                .thenReturn(org.apereo.cas.beenest.common.response.R.fail(401, "refreshToken 已过期或已被使用"));

            TokenRefreshController controller = new TokenRefreshController(nativeLoginService);

            TokenRefreshRequestDTO dto = new TokenRefreshRequestDTO();
            dto.setRefreshToken("expired-rt");

            var response = controller.refresh(dto, request);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getCode()).isEqualTo(401);
            assertThat(response.getMessage()).contains("refreshToken 已过期或已被使用");
        }
    }
}
