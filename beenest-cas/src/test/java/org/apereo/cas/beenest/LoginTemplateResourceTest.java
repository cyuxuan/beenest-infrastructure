package org.apereo.cas.beenest;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class LoginTemplateResourceTest {

    @Test
    void loginTemplateAndStylesheetExist() throws Exception {
        String html = new String(
                new ClassPathResource("templates/login/casLoginView.html").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
        );

        assertThat(new ClassPathResource("templates/login/casLoginView.html").exists()).isTrue();
        assertThat(new ClassPathResource("static/css/beenest-login.css").exists()).isTrue();
        assertThat(html).contains("name=\"loginMethod\"");
        assertThat(html).contains("name=\"service\"");
        assertThat(html).contains("th:field=\"*{username}\"");
        assertThat(html).contains("th:field=\"*{password}\"");
        assertThat(html).contains("name=\"lt\"");
        assertThat(html).contains("name=\"execution\"");
        assertThat(html).contains("name=\"_eventId\"");
        assertThat(html).contains("loginMethod: 'sms'");
        assertThat(html).contains("secondaryLabel: '短信验证码'");
        assertThat(html).contains("th:action=\"@{/login}\"");
    }
}
