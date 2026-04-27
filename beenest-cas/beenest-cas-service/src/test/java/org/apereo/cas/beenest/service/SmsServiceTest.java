package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.config.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SmsServiceTest {

    @Test
    void doesNotLogPlaintextOtpCode(CapturedOutput output) {
        SmsProperties properties = new SmsProperties();
        properties.setDailyLimit(10);
        properties.setSendInterval(1);
        properties.setOtpTtl(300);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cas:sms:limit:13800138000")).thenReturn(null);
        when(redisTemplate.hasKey("cas:sms:limit:interval:13800138000")).thenReturn(false);

        SmsService service = new SmsService(properties, redisTemplate);
        String otp = service.sendOtp("13800138000");
        assertFalse(output.getOut().contains(otp) || output.getErr().contains(otp),
                "短信验证码不应出现在任何输出中");
    }
}
