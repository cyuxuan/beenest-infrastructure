package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Token 刷新器测试。
 */
class CasTokenRefresherTest {

    @Test
    void shouldUseRefreshEndpointWithSignatureHeaders() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setServerUrl("http://localhost:8081/cas");
        properties.setServiceId("10001");
        properties.setSignKey("test-sign-key");

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:8081/cas/app/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-CAS-Service-Id", "10001"))
                .andExpect(header("X-CAS-Timestamp", not(blankOrNullString())))
                .andExpect(header("X-CAS-Nonce", not(blankOrNullString())))
                .andExpect(header("X-CAS-Signature", not(blankOrNullString())))
                .andExpect(content().json("""
                        {
                          "refreshToken": "refresh-1"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "accessToken": "TGT-2",
                            "refreshToken": "refresh-2",
                            "expiresIn": 604800,
                            "userId": "U1001",
                            "attributes": {
                              "nickname": "测试用户"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        CasTokenRefresher.TokenRefreshResult result =
                new CasTokenRefresher(properties, restTemplate).refreshToken("refresh-1");

        assertThat(result).isNotNull();
        assertThat(result.getNewAccessToken()).isEqualTo("TGT-2");
        assertThat(result.getNewRefreshToken()).isEqualTo("refresh-2");
        assertThat(result.getSession().getNickname()).isEqualTo("测试用户");
        server.verify();
    }

    @Test
    void shouldRefreshWithoutSignatureHeadersWhenSignKeyMissing() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setServerUrl("http://localhost:8081/cas");

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:8081/cas/app/refresh"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "refreshToken": "refresh-1"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "accessToken": "TGT-2",
                            "refreshToken": "refresh-2",
                            "expiresIn": 604800,
                            "userId": "U1001",
                            "attributes": {}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        CasTokenRefresher.TokenRefreshResult result =
                new CasTokenRefresher(properties, restTemplate).refreshToken("refresh-1");

        assertThat(result).isNotNull();
        assertThat(result.getNewAccessToken()).isEqualTo("TGT-2");
        server.verify();
    }
}
