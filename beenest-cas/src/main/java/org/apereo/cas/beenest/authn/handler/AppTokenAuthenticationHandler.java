package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.credential.AppTokenCredential;
import org.apereo.cas.beenest.authn.web.WebLoginModeResolver;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.security.auth.login.FailedLoginException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * APP Token 认证处理器
 * <p>
 * 支持三种 APP 登录方式：
 * 1. 用户名/手机号 + 密码
 * 2. 手机号 + 短信验证码
 * 3. refreshToken 续期
 */
@Slf4j
@RequiredArgsConstructor
public class AppTokenAuthenticationHandler implements AuthenticationHandler {

    private final String name;
    private final PrincipalFactory principalFactory;
    private final UnifiedUserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public AuthenticationHandlerExecutionResult authenticate(Credential credential, Service service) throws Throwable {
        AppTokenCredential appCredential = adaptCredential(credential);
        String loginMethod = StringUtils.defaultIfBlank(appCredential.getLoginMethod(), WebLoginModeResolver.resolveLoginMethod());

        UnifiedUserDO user;

        if ("refresh".equals(loginMethod)) {
            user = handleRefreshToken(appCredential);
        } else if ("sms".equals(loginMethod)) {
            throw new FailedLoginException("短信验证码登录请使用短信认证入口");
        } else {
            user = handlePasswordLogin(appCredential);
        }

        if (user == null) {
            throw new FailedLoginException("APP登录失败");
        }

        // 5. 重置失败计数
        userMapper.resetFailedLoginCount(user.getUserId());

        // 6. 构建 Principal
        Map<String, List<Object>> attributes = buildUserAttributes(user);
        attributes.put("rememberMe", List.of(appCredential.isRememberMe()));
        attributes.put("deviceId", List.of(appCredential.getDeviceId() != null ? appCredential.getDeviceId() : ""));
        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);

        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    private AppTokenCredential adaptCredential(Credential credential) throws FailedLoginException {
        if (credential instanceof AppTokenCredential appCredential) {
            return appCredential;
        }
        if (credential instanceof UsernamePasswordCredential usernamePasswordCredential) {
            AppTokenCredential appCredential = new AppTokenCredential();
            appCredential.setPrincipal(usernamePasswordCredential.getUsername());
            appCredential.setPassword(usernamePasswordCredential.getPassword() != null
                    ? new String(usernamePasswordCredential.getPassword()) : null);
            appCredential.setLoginMethod(WebLoginModeResolver.resolveLoginMethod());
            return appCredential;
        }
        throw new FailedLoginException("不支持的登录凭证类型");
    }

    /**
     * 密码登录
     */
    private UnifiedUserDO handlePasswordLogin(AppTokenCredential credential) throws FailedLoginException {
        String principal = credential.getPrincipal();
        String password = credential.getPassword();

        if (StringUtils.isBlank(principal) || StringUtils.isBlank(password)) {
            throw new FailedLoginException("用户名和密码不能为空");
        }

        // 1. 查找用户（支持用户名/手机号/邮箱）
        UnifiedUserDO user = userMapper.selectByUsername(principal);
        if (user == null) {
            user = userMapper.selectByPhone(principal);
        }
        if (user == null) {
            user = userMapper.selectByEmail(principal);
        }

        if (user == null) {
            // 防枚举：统一错误消息
            throw new FailedLoginException("用户名或密码错误");
        }

        // 2. 检查账号锁定
        checkAccountLock(user);

        // 3. 校验密码
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            handleFailedLogin(user);
            throw new FailedLoginException("用户名或密码错误");
        }

        return user;
    }

    /**
     * refreshToken 续期
     * <p>
     * 支持两种模式：
     * 1. Controller 已通过 getAndDelete 原子验证 refreshToken 并传入 preValidatedUserId
     *    — 直接使用，无需再查 Redis（防重放由 Controller 保证）
     * 2. 兼容旧路径 — 从 Redis 查询 refreshToken 对应的 userId
     *    — 此模式下 refreshToken 未被删除，仅作为降级方案
     */
    private UnifiedUserDO handleRefreshToken(AppTokenCredential credential) throws FailedLoginException {
        // 优先使用 Controller 预验证的 userId（refreshToken 轮换场景）
        if (StringUtils.isNotBlank(credential.getPreValidatedUserId())) {
            UnifiedUserDO user = userMapper.selectByUserId(credential.getPreValidatedUserId());
            if (user == null) {
                throw new FailedLoginException("用户不存在");
            }
            return user;
        }

        // 兼容旧路径：从 Redis 查询 refreshToken
        String refreshToken = credential.getRefreshToken();
        if (StringUtils.isBlank(refreshToken)) {
            throw new FailedLoginException("refreshToken 不能为空");
        }

        // 依次尝试 APP 前缀和小程序前缀
        String userId = findUserIdByRefreshToken(refreshToken);
        if (userId == null) {
            throw new FailedLoginException("refreshToken 已过期");
        }

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            throw new FailedLoginException("用户不存在");
        }

        return user;
    }

    /**
     * 查找 refreshToken 对应的 userId
     * <p>
     * 依次尝试 APP 前缀和小程序前缀，兼容两种场景。
     */
    private String findUserIdByRefreshToken(String refreshToken) {
        // 先查 APP 前缀
        String appKey = CasConstant.REDIS_APP_TOKEN_PREFIX + "refresh:" + refreshToken;
        String userId = redisTemplate.opsForValue().get(appKey);
        if (userId != null) {
            return userId;
        }
        // 再查小程序前缀
        String miniappKey = CasConstant.REDIS_MINIAPP_TOKEN_PREFIX + "refresh:" + refreshToken;
        return redisTemplate.opsForValue().get(miniappKey);
    }

    /**
     * 检查账号是否被锁定
     */
    private void checkAccountLock(UnifiedUserDO user) throws FailedLoginException {
        if (user.getStatus() != null && user.getStatus() == CasConstant.USER_STATUS_LOCKED) {
            if (user.getLockUntilTime() != null && user.getLockUntilTime().isAfter(LocalDateTime.now())) {
                throw new FailedLoginException("账号已被锁定，请稍后再试");
            }
            // 锁定已过期，自动解锁
            userMapper.resetFailedLoginCount(user.getUserId());
            userMapper.updateStatus(user.getUserId(), CasConstant.USER_STATUS_ACTIVE);
        }
    }

    /**
     * 处理登录失败：增加失败计数，超过阈值则锁定
     */
    private void handleFailedLogin(UnifiedUserDO user) {
        userMapper.incrementFailedLoginCount(user.getUserId());
        int failedCount = user.getFailedLoginCount() != null ? user.getFailedLoginCount() + 1 : 1;
        if (failedCount >= CasConstant.MAX_FAILED_LOGIN_ATTEMPTS) {
            userMapper.lockAccount(user.getUserId(),
                    LocalDateTime.now().plusMinutes(CasConstant.LOCK_DURATION_MINUTES));
            LOGGER.warn("账号已锁定: userId={}, failedCount={}", user.getUserId(), failedCount);
        }
    }

    @Override
    public boolean supports(Credential credential) {
        if (credential instanceof AppTokenCredential) {
            return true;
        }
        return credential instanceof UsernamePasswordCredential && !WebLoginModeResolver.isSmsMode();
    }

    @Override
    public boolean supports(Class<? extends Credential> clazz) {
        if (AppTokenCredential.class.isAssignableFrom(clazz)) {
            return true;
        }
        return UsernamePasswordCredential.class.isAssignableFrom(clazz) && !WebLoginModeResolver.isSmsMode();
    }

    @Override
    public String getName() {
        return this.name;
    }

    private Map<String, List<Object>> buildUserAttributes(UnifiedUserDO user) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("userId", List.of(user.getUserId()));
        attrs.put("userType", List.of(user.getUserType() != null ? user.getUserType() : "CUSTOMER"));
        attrs.put("loginType", List.of("APP"));
        if (StringUtils.isNotBlank(user.getUsername())) {
            attrs.put("username", List.of(user.getUsername()));
        }
        if (StringUtils.isNotBlank(user.getPhone())) {
            attrs.put("phone", List.of(user.getPhone()));
        }
        if (StringUtils.isNotBlank(user.getEmail())) {
            attrs.put("email", List.of(user.getEmail()));
        }
        if (StringUtils.isNotBlank(user.getNickname())) {
            attrs.put("nickname", List.of(user.getNickname()));
        }
        return attrs;
    }
}
