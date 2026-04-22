package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.service.UserIdentityService;
import org.apereo.cas.beenest.authn.credential.SmsOtpCredential;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsOtpAuthenticationHandlerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private SmsOtpAuthenticationHandler createHandler(
            StringRedisTemplate redisTemplate, UserIdentityService userIdentityService) {
        PrincipalFactory principalFactory = mock(PrincipalFactory.class);
        Principal principal = mock(Principal.class);
        when(principal.getId()).thenReturn("U1234567890ABCDEF");
        try {
            when(principalFactory.createPrincipal(anyString(), any())).thenReturn(principal);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        return new SmsOtpAuthenticationHandler(
                "smsOtpAuthenticationHandler", principalFactory, redisTemplate, userIdentityService);
    }

    private StringRedisTemplate stubRedisTemplate() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cas:sms:otp:13800138000")).thenReturn("123456");
        when(valueOperations.get("cas:sms:otp:fail:13800138000")).thenReturn(null);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(-1L);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        return redisTemplate;
    }

    private UserIdentityService stubUserIdentityService() {
        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId("U1234567890ABCDEF");
        user.setPhone("13800138000");
        user.setUserType("CUSTOMER");

        UserIdentityService userIdentityService = mock(UserIdentityService.class);
        UserIdentityService.UserIdentityResult result = new UserIdentityService.UserIdentityResult(user, false);
        when(userIdentityService.findOrRegisterByPhoneResult("13800138000", null)).thenReturn(result);
        return userIdentityService;
    }

    @Test
    void authenticateWithSmsOtpCredentialUsesSmsOtp() throws Throwable {
        SmsOtpAuthenticationHandler handler = createHandler(stubRedisTemplate(), stubUserIdentityService());

        SmsOtpCredential credential = new SmsOtpCredential("13800138000", "123456");
        AuthenticationHandlerExecutionResult result = handler.authenticate(credential, null);

        assertThat(result).isNotNull();
        assertThat(result.getPrincipal().getId()).isEqualTo("U1234567890ABCDEF");
    }

    @Test
    void authenticateShouldRejectUsernamePasswordCredential() {
        SmsOtpAuthenticationHandler handler = new SmsOtpAuthenticationHandler(
                "smsOtpAuthenticationHandler",
                mock(PrincipalFactory.class),
                mock(StringRedisTemplate.class),
                mock(UserIdentityService.class)
        );

        assertThatThrownBy(() -> handler.authenticate(
                new org.apereo.cas.authentication.credential.UsernamePasswordCredential("13800138000", "123456"),
                null
        )).isInstanceOf(javax.security.auth.login.FailedLoginException.class);
    }

    @Test
    void authenticateShouldAcceptUsernamePasswordCredentialInSmsMode() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("loginMethod", "sms");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        SmsOtpAuthenticationHandler handler = createHandler(stubRedisTemplate(), stubUserIdentityService());

        AuthenticationHandlerExecutionResult result = handler.authenticate(
                new org.apereo.cas.authentication.credential.UsernamePasswordCredential("13800138000", "123456"),
                null
        );

        assertThat(result).isNotNull();
        assertThat(result.getPrincipal().getId()).isEqualTo("U1234567890ABCDEF");
    }
}
