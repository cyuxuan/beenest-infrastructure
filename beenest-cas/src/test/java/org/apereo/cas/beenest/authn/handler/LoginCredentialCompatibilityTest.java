package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.credential.AppTokenCredential;
import org.apereo.cas.beenest.authn.credential.SmsOtpCredential;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.authentication.credential.UsernamePasswordCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LoginCredentialCompatibilityTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void appAndSmsHandlersSupportBrowserCredentials() {
        AppTokenAuthenticationHandler appHandler = new AppTokenAuthenticationHandler(
                "appTokenAuthenticationHandler",
                mock(org.apereo.cas.authentication.principal.PrincipalFactory.class),
                mock(org.apereo.cas.beenest.mapper.UnifiedUserMapper.class),
                mock(StringRedisTemplate.class)
        );
        SmsOtpAuthenticationHandler smsHandler = new SmsOtpAuthenticationHandler(
                "smsOtpAuthenticationHandler",
                mock(org.apereo.cas.authentication.principal.PrincipalFactory.class),
                mock(StringRedisTemplate.class),
                mock(UserIdentityService.class)
        );

        assertThat(appHandler.supports(AppTokenCredential.class)).isTrue();
        assertThat(appHandler.supports(UsernamePasswordCredential.class)).isTrue();
        assertThat(smsHandler.supports(SmsOtpCredential.class)).isTrue();
        assertThat(smsHandler.supports(UsernamePasswordCredential.class)).isFalse();
    }

    @Test
    void usernamePasswordCredentialShouldRouteByLoginMethod() {
        AppTokenAuthenticationHandler appHandler = new AppTokenAuthenticationHandler(
                "appTokenAuthenticationHandler",
                mock(org.apereo.cas.authentication.principal.PrincipalFactory.class),
                mock(org.apereo.cas.beenest.mapper.UnifiedUserMapper.class),
                mock(StringRedisTemplate.class)
        );
        SmsOtpAuthenticationHandler smsHandler = new SmsOtpAuthenticationHandler(
                "smsOtpAuthenticationHandler",
                mock(org.apereo.cas.authentication.principal.PrincipalFactory.class),
                mock(StringRedisTemplate.class),
                mock(UserIdentityService.class)
        );

        MockHttpServletRequest passwordRequest = new MockHttpServletRequest();
        passwordRequest.addParameter("loginMethod", "password");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(passwordRequest));

        UsernamePasswordCredential credential = new UsernamePasswordCredential("alice", "secret");
        assertThat(appHandler.supports(credential)).isTrue();
        assertThat(smsHandler.supports(credential)).isFalse();
        assertThat(appHandler.supports(UsernamePasswordCredential.class)).isTrue();
        assertThat(smsHandler.supports(UsernamePasswordCredential.class)).isFalse();

        MockHttpServletRequest smsRequest = new MockHttpServletRequest();
        smsRequest.addParameter("loginMethod", "sms");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(smsRequest));

        assertThat(appHandler.supports(credential)).isFalse();
        assertThat(smsHandler.supports(credential)).isTrue();
        assertThat(appHandler.supports(UsernamePasswordCredential.class)).isFalse();
        assertThat(smsHandler.supports(UsernamePasswordCredential.class)).isTrue();
    }
}
