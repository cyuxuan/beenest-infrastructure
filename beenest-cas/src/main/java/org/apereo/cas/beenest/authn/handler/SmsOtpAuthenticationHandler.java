package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.credential.SmsOtpCredential;
import org.apereo.cas.beenest.authn.web.WebLoginModeResolver;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.service.UserIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apereo.cas.authentication.AuthenticationHandler;
import org.apereo.cas.authentication.AuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.DefaultAuthenticationHandlerExecutionResult;
import org.apereo.cas.authentication.credential.UsernamePasswordCredential;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.Service;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.security.auth.login.FailedLoginException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码认证处理器
 * <p>
 * 从 Redis 获取存储的验证码与用户输入进行校验，
 * 用户不存在则自动注册（手机号即账号）。
 */
@Slf4j
@RequiredArgsConstructor
public class SmsOtpAuthenticationHandler implements AuthenticationHandler {

    private final String name;
    private final PrincipalFactory principalFactory;
    private final StringRedisTemplate redisTemplate;
    private final UserIdentityService userIdentityService;

    @Override
    public AuthenticationHandlerExecutionResult authenticate(Credential credential, Service service) throws Throwable {
        SmsOtpCredential smsCredential = adaptCredential(credential);
        String phone = smsCredential.getPhone();
        String otpCode = smsCredential.getOtpCode();

        LOGGER.info("SMS authentication request received: credentialClass={}, phone={}",
                credential.getClass().getName(), phone);
        LOGGER.info("SMS redis server info: {}",
                redisTemplate.execute((RedisCallback<String>) connection ->
                        connection.serverCommands().info("server").toString()));
        LOGGER.info("SMS redis keyspace info: {}",
                redisTemplate.execute((RedisCallback<String>) connection ->
                        connection.serverCommands().info("keyspace").toString()));

        if (StringUtils.isBlank(phone) || StringUtils.isBlank(otpCode)) {
            throw new FailedLoginException("手机号和验证码不能为空");
        }

        // 1. 检查暴力破解锁定
        String failKey = CasConstant.REDIS_SMS_OTP_FAIL_PREFIX + phone;
        String failCountStr = redisTemplate.opsForValue().get(failKey);
        int failCount = failCountStr != null ? Integer.parseInt(failCountStr) : 0;
        if (failCount >= CasConstant.MAX_SMS_OTP_ATTEMPTS) {
            // 超过最大尝试次数，删除验证码使其失效
            redisTemplate.delete(CasConstant.REDIS_SMS_OTP_PREFIX + phone);
            redisTemplate.delete(failKey);
            throw new FailedLoginException("验证码错误次数过多，请重新获取验证码");
        }

        // 2. 从 Redis 获取验证码
        String redisKey = CasConstant.REDIS_SMS_OTP_PREFIX + phone;
        String storedCode = redisTemplate.opsForValue().get(redisKey);
        LOGGER.info("SMS otp lookup: redisKey={}, hit={}", redisKey, storedCode != null);

        if (storedCode == null) {
            throw new FailedLoginException("验证码已过期，请重新获取");
        }

        if (!otpCode.equals(storedCode)) {
            LOGGER.info("SMS otp mismatch: phone={}, provided={}, stored={}", phone, otpCode, storedCode);
            // 验证失败，递增失败计数
            long ttl = redisTemplate.getExpire(failKey, TimeUnit.SECONDS);
            if (ttl > 0) {
                redisTemplate.opsForValue().increment(failKey);
            } else {
                // 首次失败或已过期，设置 5 分钟窗口
                redisTemplate.opsForValue().set(failKey, "1", 300, TimeUnit.SECONDS);
            }
            throw new FailedLoginException("验证码错误");
        }

        // 3. 验证成功，删除验证码（一次性使用）和失败计数
        redisTemplate.delete(redisKey);
        redisTemplate.delete(failKey);

        // 4. 查找或注册用户
        UserIdentityService.UserIdentityResult identityResult = userIdentityService.findOrRegisterByPhoneResult(
                phone, smsCredential.getUserType());
        UnifiedUserDO user = identityResult.user();

        if (user == null) {
            throw new FailedLoginException("短信登录失败：无法获取用户信息");
        }

        // 5. 构建 Principal
        Map<String, List<Object>> attributes = buildUserAttributes(user, identityResult.firstLogin());
        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);

        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    private SmsOtpCredential adaptCredential(Credential credential) throws FailedLoginException {
        if (credential instanceof SmsOtpCredential smsCredential) {
            return smsCredential;
        }
        if (credential instanceof UsernamePasswordCredential usernamePasswordCredential) {
            if (!WebLoginModeResolver.isSmsMode()) {
                throw new FailedLoginException("非短信登录模式");
            }
            SmsOtpCredential smsCredential = new SmsOtpCredential();
            smsCredential.setPhone(usernamePasswordCredential.getUsername());
            smsCredential.setOtpCode(usernamePasswordCredential.getPassword() != null
                    ? new String(usernamePasswordCredential.getPassword()) : null);
            return smsCredential;
        }
        throw new FailedLoginException("不支持的登录凭证类型");
    }

    @Override
    public boolean supports(Credential credential) {
        if (credential instanceof SmsOtpCredential) {
            return true;
        }
        return credential instanceof UsernamePasswordCredential && WebLoginModeResolver.isSmsMode();
    }

    @Override
    public boolean supports(Class<? extends Credential> clazz) {
        if (SmsOtpCredential.class.isAssignableFrom(clazz)) {
            return true;
        }
        return UsernamePasswordCredential.class.isAssignableFrom(clazz) && WebLoginModeResolver.isSmsMode();
    }

    @Override
    public String getName() {
        return this.name;
    }

    private Map<String, List<Object>> buildUserAttributes(UnifiedUserDO user, boolean firstLogin) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("userId", List.of(user.getUserId()));
        attrs.put("userType", List.of(user.getUserType() != null ? user.getUserType() : "CUSTOMER"));
        attrs.put("loginType", List.of("PHONE_SMS"));
        attrs.put("firstLogin", List.of(firstLogin));
        attrs.put("phone", List.of(user.getPhone()));
        if (StringUtils.isNotBlank(user.getUsername())) {
            attrs.put("username", List.of(user.getUsername()));
        }
        if (StringUtils.isNotBlank(user.getNickname())) {
            attrs.put("nickname", List.of(user.getNickname()));
        }
        return attrs;
    }
}
