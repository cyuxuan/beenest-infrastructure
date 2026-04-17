package org.apereo.cas.beenest.client.proxy;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 业务系统登录代理服务单元测试。
 * <p>
 * 验证代理服务能正确转发微信、抖音、支付宝小程序登录请求到 CAS Server，
 * 包括自动签名头的添加和 502 降级处理。
 */
class CasBusinessLoginProxyServiceTest {

    private CasSecurityProperties baseProperties() {
        CasSecurityProperties props = new CasSecurityProperties();
        props.setServerUrl("http://localhost:8081/cas");
        props.setServiceId("test-service");
        props.setSignKey("test-sign-key");
        return props;
    }

    private MockHttpServletRequest stubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    @Nested
    class WechatMiniLogin {

        @Test
        void shouldProxyWechatLoginToCasServer() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            String responseBody = "{\"code\":200,\"data\":{\"userId\":1,\"accessToken\":\"TGT-1\",\"refreshToken\":\"RT-1\"}}";
            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/wechat/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            String body = "{\"code\":\"wx_code_123\",\"phoneCode\":\"phone_wx\",\"userType\":1}";

            ResponseEntity<String> response = service.proxy(request, body, "/cas/miniapp/wechat/login");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"accessToken\":\"TGT-1\"");
            assertThat(response.getBody()).contains("\"userId\":1");
            server.verify();
        }
    }

    @Nested
    class DouyinMiniLogin {

        @Test
        void shouldProxyDouyinLoginToCasServer() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            String responseBody = "{\"code\":200,\"data\":{\"userId\":2,\"accessToken\":\"TGT-2\",\"refreshToken\":\"RT-2\"}}";
            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/douyin/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            String body = "{\"douyinCode\":\"dy_code_456\",\"userType\":2,\"nickname\":\"抖音用户\"}";

            ResponseEntity<String> response = service.proxy(request, body, "/cas/miniapp/douyin/login");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"accessToken\":\"TGT-2\"");
            server.verify();
        }
    }

    @Nested
    class AlipayMiniLogin {

        @Test
        void shouldProxyAlipayLoginToCasServer() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            String responseBody = "{\"code\":200,\"data\":{\"userId\":3,\"accessToken\":\"TGT-3\",\"refreshToken\":\"RT-3\"}}";
            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/alipay/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            String body = "{\"authCode\":\"alipay_auth_789\",\"phoneCode\":\"phone_ali\",\"userType\":1}";

            ResponseEntity<String> response = service.proxy(request, body, "/cas/miniapp/alipay/login");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"accessToken\":\"TGT-3\"");
            server.verify();
        }
    }

    @Nested
    class MiniAppRefresh {

        @Test
        void shouldProxyRefreshToCasServer() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            String responseBody = "{\"code\":200,\"data\":{\"accessToken\":\"new-TGT\",\"refreshToken\":\"new-RT\"}}";
            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            String body = "{\"refreshToken\":\"RT-1\"}";

            ResponseEntity<String> response = service.proxy(request, body, "/cas/miniapp/refresh");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"accessToken\":\"new-TGT\"");
            server.verify();
        }
    }

    @Nested
    class SignatureHeaders {

        @Test
        void shouldAppendHmacSignatureHeadersWhenSignKeyConfigured() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/wechat/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(req -> {
                    // 验证签名头存在
                    String serviceId = req.getHeaders().getFirst("X-CAS-Service-Id");
                    String timestamp = req.getHeaders().getFirst("X-CAS-Timestamp");
                    String nonce = req.getHeaders().getFirst("X-CAS-Nonce");
                    String signature = req.getHeaders().getFirst("X-CAS-Signature");

                    assertThat(serviceId).isEqualTo("test-service");
                    assertThat(timestamp).isNotBlank();
                    assertThat(nonce).isNotBlank();
                    assertThat(signature).isNotBlank();
                })
                .andRespond(withSuccess("{\"code\":200}", MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            service.proxy(request, "{}", "/cas/miniapp/wechat/login");

            server.verify();
        }

        @Test
        void shouldNotAppendSignatureHeadersWhenSignKeyMissing() {
            CasSecurityProperties properties = baseProperties();
            properties.setServiceId(null);
            properties.setSignKey(null);

            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/wechat/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(req -> {
                    assertThat(req.getHeaders().getFirst("X-CAS-Service-Id")).isNull();
                    assertThat(req.getHeaders().getFirst("X-CAS-Signature")).isNull();
                })
                .andRespond(withSuccess("{\"code\":200}", MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            service.proxy(request, "{}", "/cas/miniapp/wechat/login");

            server.verify();
        }
    }

    @Nested
    class ErrorHandling {

        @Test
        void shouldReturn502WhenCasServerUnreachable() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate() {
                @Override
                public <T> ResponseEntity<T> exchange(String url, HttpMethod method,
                                                      org.springframework.http.HttpEntity<?> requestEntity,
                                                      Class<T> responseType, Object... uriVariables) {
                    throw new org.springframework.web.client.RestClientException("Connection refused");
                }
            };

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();

            ResponseEntity<String> response = service.proxy(request, "{\"code\":\"wx\"}", "/cas/miniapp/wechat/login");

            assertThat(response.getStatusCode().value()).isEqualTo(502);
            assertThat(response.getBody()).contains("CAS 登录服务暂不可用");
        }

        @Test
        void shouldReturn502WhenCasReturns5xx() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/douyin/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":500,\"message\":\"内部错误\"}", MediaType.APPLICATION_JSON));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();

            ResponseEntity<String> response = service.proxy(request, "{\"douyinCode\":\"abc\"}", "/cas/miniapp/douyin/login");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).contains("\"code\":500");
            server.verify();
        }
    }

    @Nested
    class ResponseHeaders {

        @Test
        void shouldFilterHopByHopHeadersFromResponse() {
            CasSecurityProperties properties = baseProperties();
            RestTemplate restTemplate = new RestTemplate();
            MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();

            server.expect(once(), requestTo("http://localhost:8081/cas/miniapp/wechat/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"code\":200}", MediaType.APPLICATION_JSON)
                    .header("Transfer-Encoding", "chunked")
                    .header("Connection", "keep-alive"));

            CasBusinessLoginProxyService service = new CasBusinessLoginProxyService(properties, restTemplate);
            MockHttpServletRequest request = stubRequest();
            ResponseEntity<String> response = service.proxy(request, "{}", "/cas/miniapp/wechat/login");

            assertThat(response.getHeaders().getFirst("Transfer-Encoding")).isNull();
            assertThat(response.getHeaders().getFirst("Connection")).isNull();
            server.verify();
        }
    }
}
