package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.session.CasUserSession;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TGT 远程验证器测试。
 */
class CasTgtValidatorTest {

    @Test
    void shouldCallValidateEndpointWithValidationSecretHeader() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setServerUrl("http://localhost:8081/cas");
        properties.setTokenValidationSecret("token-secret");

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:8081/cas/token/validate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-CAS-Token-Secret", "token-secret"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("accessToken=TGT-1")))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "success",
                          "data": {
                            "userId": "U1001",
                            "attributes": {
                              "nickname": "测试用户"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        CasUserSession session = new CasTgtValidator(properties, restTemplate).validate("TGT-1");

        assertThat(session).isNotNull();
        assertThat(session.getUserId()).isEqualTo("U1001");
        assertThat(session.getNickname()).isEqualTo("测试用户");
        server.verify();
    }
}
