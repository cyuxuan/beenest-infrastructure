package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.exception.BusinessException;
import org.apereo.cas.beenest.config.SmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码服务
 * <p>
 * 生成、存储、校验短信验证码。实际短信发送需集成阿里云/腾讯云 SDK。
 * 使用 {@link BusinessException} 统一异常处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final SmsProperties smsProperties;
    private final StringRedisTemplate redisTemplate;

    /**
     * 发送短信验证码
     *
     * @param phone 手机号
     * @return 验证码（开发环境直接返回，生产环境由 SDK 发送）
     */
    public String sendOtp(String phone) {
        // 1. 限流检查
        String limitKey = CasConstant.REDIS_SMS_LIMIT_PREFIX + phone;
        String limitCount = redisTemplate.opsForValue().get(limitKey);
        if (limitCount != null && Integer.parseInt(limitCount) >= smsProperties.getDailyLimit()) {
            throw new BusinessException(429, "今日发送次数已达上限");
        }

        // 2. 间隔检查
        String intervalKey = CasConstant.REDIS_SMS_LIMIT_PREFIX + "interval:" + phone;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(intervalKey))) {
            throw new BusinessException(429, "发送过于频繁，请稍后再试");
        }

        // 3. 生成验证码
        String code = RandomStringUtils.secure().nextNumeric(6);

        // 4. 存储到 Redis
        String otpKey = CasConstant.REDIS_SMS_OTP_PREFIX + phone;
        redisTemplate.opsForValue().set(otpKey, code, smsProperties.getOtpTtl(), TimeUnit.SECONDS);

        // 5. 设置发送间隔
        redisTemplate.opsForValue().set(intervalKey, "1", smsProperties.getSendInterval(), TimeUnit.SECONDS);

        // 6. 递增每日计数
        if (limitCount == null) {
            // 当天第一次发送，设置 key 过期到当天结束
            long secondsUntilMidnight = Duration.between(
                    LocalDateTime.now(),
                    LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0)
            ).getSeconds();
            redisTemplate.opsForValue().set(limitKey, "1", secondsUntilMidnight, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().increment(limitKey);
        }

        // 7. 发送短信（TODO: 集成阿里云/腾讯云 SMS SDK）
        LOGGER.info("发送短信验证码: phone={}", phone);

        return code;
    }
}
