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
 * CAS 原生票据验证器测试。
 */
class CasNativeTicketValidatorTest {

    @Test
    void shouldValidateUsingNativeCasProtocol() {
        CasSecurityProperties properties = new CasSecurityProperties();
        properties.setServerUrl("http://localhost:8081/cas");
        properties.setClientHostUrl("http://localhost:8080/drone");

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:8081/cas/v1/tickets/TGT-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("service=http%3A%2F%2Flocalhost%3A8080%2Fdrone")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("ST-1",
                        MediaType.TEXT_PLAIN));
        server.expect(requestTo("http://localhost:8081/cas/p3/serviceValidate?service=http://localhost:8080/drone&ticket=ST-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                          <cas:authenticationSuccess>
                            <cas:user>U1001</cas:user>
                            <cas:attributes>
                              <cas:nickname>测试用户</cas:nickname>
                            </cas:attributes>
                          </cas:authenticationSuccess>
                        </cas:serviceResponse>
                        """, MediaType.parseMediaType("application/xml;charset=UTF-8")));

        CasUserSession session = new CasNativeTicketValidator(properties, restTemplate).validate("TGT-1");

        assertThat(session).isNotNull();
        assertThat(session.getUserId()).isEqualTo("U1001");
        assertThat(session.getNickname()).isEqualTo("测试用户");
        server.verify();
    }
}
