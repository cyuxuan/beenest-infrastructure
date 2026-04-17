package org.apereo.cas.beenest.client.controller;

import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务系统登录代理控制器单元测试。
 * <p>
 * 验证微信、抖音、支付宝小程序登录以及 refresh 请求
 * 均能正确委托给代理服务。
 */
class CasBusinessLoginProxyControllerTest {

    private final CasBusinessLoginProxyService proxyService = mock(CasBusinessLoginProxyService.class);
    private final CasBusinessLoginProxyController controller = new CasBusinessLoginProxyController(proxyService);
    private final BearerTokenCache bearerTokenCache = mock(BearerTokenCache.class);
    private final BearerTokenRevocationService bearerTokenRevocationService = mock(BearerTokenRevocationService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BearerTokenCache> bearerTokenCacheProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BearerTokenRevocationService> bearerTokenRevocationServiceProvider = mock(ObjectProvider.class);
    private CasBusinessLoginProxyController controllerWithCache;

    @BeforeEach
    void setUp() {
        when(bearerTokenCacheProvider.getIfAvailable()).thenReturn(bearerTokenCache);
        when(bearerTokenRevocationServiceProvider.getIfAvailable()).thenReturn(bearerTokenRevocationService);
        controllerWithCache = new CasBusinessLoginProxyController(
                proxyService,
                bearerTokenCacheProvider,
                bearerTokenRevocationServiceProvider);
    }

    private MockHttpServletRequest stubRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        request.setMethod("POST");
        return request;
    }

    @Nested
    class WechatLogin {

        @Test
        void shouldDelegateToProxyService() {
            String body = "{\"code\":\"wx_code_123\",\"phoneCode\":\"phone_wx\",\"userType\":1}";
            MockHttpServletRequest request = stubRequest("/cas/miniapp/wechat/login");
            when(proxyService.proxy(request, body, "/cas/miniapp/wechat/login"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":{\"userId\":1}}"));

            ResponseEntity<String> response = controller.proxyWechatLogin(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"userId\":1");
            verify(proxyService).proxy(request, body, "/cas/miniapp/wechat/login");
        }
    }

    @Nested
    class DouyinLogin {

        @Test
        void shouldDelegateToProxyService() {
            String body = "{\"douyinCode\":\"dy_code_456\",\"userType\":2,\"nickname\":\"测试用户\"}";
            MockHttpServletRequest request = stubRequest("/cas/miniapp/douyin/login");
            when(proxyService.proxy(request, body, "/cas/miniapp/douyin/login"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":{\"userId\":2}}"));

            ResponseEntity<String> response = controller.proxyDouyinLogin(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"userId\":2");
            verify(proxyService).proxy(request, body, "/cas/miniapp/douyin/login");
        }
    }

    @Nested
    class AlipayLogin {

        @Test
        void shouldDelegateToProxyService() {
            String body = "{\"authCode\":\"alipay_auth_789\",\"phoneCode\":\"phone_ali\",\"userType\":1}";
            MockHttpServletRequest request = stubRequest("/cas/miniapp/alipay/login");
            when(proxyService.proxy(request, body, "/cas/miniapp/alipay/login"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":{\"userId\":3}}"));

            ResponseEntity<String> response = controller.proxyAlipayLogin(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"userId\":3");
            verify(proxyService).proxy(request, body, "/cas/miniapp/alipay/login");
        }
    }

    @Nested
    class MiniAppRefresh {

        @Test
        void shouldDelegateToProxyService() {
            String body = "{\"refreshToken\":\"tgt-refresh-token-abc\"}";
            MockHttpServletRequest request = stubRequest("/cas/miniapp/refresh");
            when(proxyService.proxy(request, body, "/cas/miniapp/refresh"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":{\"accessToken\":\"new-tgt\"}}"));

            ResponseEntity<String> response = controller.proxyMiniAppRefresh(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"accessToken\"");
            verify(proxyService).proxy(request, body, "/cas/miniapp/refresh");
        }
    }

    @Nested
    class AppLogin {

        @Test
        void shouldDelegateToProxyService() {
            String body = "{\"principal\":\"alice\",\"credential\":\"secret\"}";
            MockHttpServletRequest request = stubRequest("/cas/app/login");
            when(proxyService.proxy(request, body, "/cas/app/login"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"data\":{\"userId\":10}}"));

            ResponseEntity<String> response = controller.proxyAppLogin(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(proxyService).proxy(request, body, "/cas/app/login");
        }
    }

    @Nested
    class Logout {

        @Test
        void shouldRevokeBearerCacheOnMiniAppLogout() {
            String body = "{\"accessToken\":\"TGT-logout\",\"refreshToken\":\"RT-logout\"}";
            MockHttpServletRequest request = stubRequest("/cas/miniapp/logout");
            when(proxyService.proxy(request, body, "/cas/miniapp/logout"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"message\":\"ok\"}"));

            ResponseEntity<String> response = controllerWithCache.proxyMiniAppLogout(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(bearerTokenCache).revoke("TGT-logout");
            verify(bearerTokenRevocationService).revokeAccessToken("TGT-logout");
            verify(bearerTokenRevocationService).revokeRefreshToken("RT-logout");
            verify(proxyService).proxy(request, body, "/cas/miniapp/logout");
        }

        @Test
        void shouldRevokeBearerCacheOnAppLogout() {
            String body = "{\"accessToken\":\"APP-TGT-logout\",\"refreshToken\":\"APP-RT-logout\"}";
            MockHttpServletRequest request = stubRequest("/cas/app/logout");
            when(proxyService.proxy(request, body, "/cas/app/logout"))
                .thenReturn(ResponseEntity.ok("{\"code\":200,\"message\":\"ok\"}"));

            ResponseEntity<String> response = controllerWithCache.proxyAppLogout(body, request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(bearerTokenCache).revoke("APP-TGT-logout");
            verify(bearerTokenRevocationService).revokeAccessToken("APP-TGT-logout");
            verify(bearerTokenRevocationService).revokeRefreshToken("APP-RT-logout");
            verify(proxyService).proxy(request, body, "/cas/app/logout");
        }
    }
}
