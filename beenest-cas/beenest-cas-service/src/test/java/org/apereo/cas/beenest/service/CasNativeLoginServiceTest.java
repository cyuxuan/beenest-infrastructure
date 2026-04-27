package org.apereo.cas.beenest.service;

import jakarta.servlet.http.HttpServletRequest;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.beenest.authn.credential.WechatMiniCredential;
import org.apereo.cas.beenest.common.response.R;
import org.apereo.cas.beenest.dto.TokenResponseDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CAS 原生登录执行服务测试。
 */
class CasNativeLoginServiceTest {

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");
        request.addHeader("X-Forwarded-For", "10.0.0.1");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    void shouldLoginAndIssueToken() throws Throwable {
        AuthenticationSystemSupport authSupport = mock(AuthenticationSystemSupport.class);
        CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);
        CasNativeLoginService service = new CasNativeLoginService(authSupport, tokenLifecycleService);

        AuthenticationResult authResult = mock(AuthenticationResult.class);
        when(authResult.getAuthentication()).thenReturn(mock(Authentication.class));
        when(authSupport.finalizeAuthenticationTransaction(any(Credential.class))).thenReturn(authResult);

        TokenResponseDTO tokenResponse = new TokenResponseDTO();
        tokenResponse.setAccessToken("TGT-1");
        tokenResponse.setRefreshToken("RT-1");
        tokenResponse.setUserId("U10001");
        when(tokenLifecycleService.issueToken(any(AuthenticationResult.class), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(tokenResponse);

        Credential credential = new WechatMiniCredential("code-1");

        R<TokenResponseDTO> response = service.login(credential, "WECHAT", stubRequest(), "device-1");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(tokenResponse);
        verify(tokenLifecycleService).issueToken(authResult, "WECHAT", "10.0.0.1", "JUnit", "device-1");
    }

    @Test
    void shouldRefreshTokenSuccessfully() throws Throwable {
        CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);
        CasNativeLoginService service = new CasNativeLoginService(null, tokenLifecycleService);

        when(tokenLifecycleService.consumeRefreshToken("old-rt")).thenReturn("U10001");

        TokenResponseDTO tokenResponse = new TokenResponseDTO();
        tokenResponse.setAccessToken("TGT-2");
        tokenResponse.setRefreshToken("RT-2");
        tokenResponse.setUserId("U10001");
        when(tokenLifecycleService.issueTokenForUserId("U10001", "TOKEN_REFRESH", "10.0.0.1", "JUnit", null))
            .thenReturn(tokenResponse);

        R<TokenResponseDTO> response = service.refresh("old-rt", stubRequest());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(tokenResponse);
        verify(tokenLifecycleService).consumeRefreshToken("old-rt");
        verify(tokenLifecycleService).issueTokenForUserId("U10001", "TOKEN_REFRESH", "10.0.0.1", "JUnit", null);
    }

    @Test
    void shouldRejectBlankRefreshToken() {
        AuthenticationSystemSupport authSupport = mock(AuthenticationSystemSupport.class);
        CasTokenLifecycleService tokenLifecycleService = mock(CasTokenLifecycleService.class);
        CasNativeLoginService service = new CasNativeLoginService(authSupport, tokenLifecycleService);

        R<TokenResponseDTO> response = service.refresh("   ", stubRequest());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getCode()).isEqualTo(400);
        assertThat(response.getMessage()).contains("refreshToken 不能为空");
    }
}
