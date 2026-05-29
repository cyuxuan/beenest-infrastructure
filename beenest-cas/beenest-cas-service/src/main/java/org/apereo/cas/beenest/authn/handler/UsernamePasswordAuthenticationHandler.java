package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.exception.AccountDisabledException;
import org.apereo.cas.beenest.authn.exception.AccountLockedException;
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
import org.springframework.core.Ordered;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.security.auth.login.FailedLoginException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户名密码认证处理器。
 * <p>
 * 直接校验 CAS 用户表中的 bcrypt 密码哈希，
 * 作为 Web 登录页的主认证路径。
 */
@Slf4j
@RequiredArgsConstructor
public class UsernamePasswordAuthenticationHandler implements AuthenticationHandler {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final String name;
    private final PrincipalFactory principalFactory;
    private final UnifiedUserMapper userMapper;

    @Override
    public AuthenticationHandlerExecutionResult authenticate(final Credential credential,
                                                             final Service service) throws Throwable {
        UsernamePasswordCredential usernamePasswordCredential = adaptCredential(credential);
        String username = usernamePasswordCredential.getUsername();
        String rawPassword = usernamePasswordCredential.getPassword() != null
            ? new String(usernamePasswordCredential.getPassword()) : null;

        if (StringUtils.isBlank(username) || StringUtils.isBlank(rawPassword)) {
            throw new FailedLoginException("用户名和密码不能为空");
        }

        UnifiedUserDO user = userMapper.selectByUsername(username);
        if (user == null) {
            LOGGER.debug("用户不存在: {}", username);
            throw new FailedLoginException("用户名或密码错误");
        }

        // 1. 检查账号状态（含自动解锁）
        ensureLoginAllowed(user);

        // 2. 校验密码
        if (StringUtils.isBlank(user.getPasswordHash())) {
            LOGGER.debug("账号未设置密码: userId={}", user.getUserId());
            throw new FailedLoginException("用户名或密码错误");
        }
        if (!PASSWORD_ENCODER.matches(rawPassword, user.getPasswordHash())) {
            handleLoginFailure(user);
        }

        // 3. 登录成功，重置失败计数
        if (user.getFailedLoginCount() != null && user.getFailedLoginCount() > 0) {
            userMapper.resetFailedLoginCount(user.getUserId());
            LOGGER.info("登录成功，重置失败计数: userId={}", user.getUserId());
        }

        Map<String, List<Object>> attributes = buildUserAttributes(user);
        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);
        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    @Override
    public boolean supports(final Credential credential) {
        return credential instanceof UsernamePasswordCredential && WebLoginModeResolver.isPasswordMode();
    }

    /**
     * 检查账号是否允许登录，支持自动解锁。
     * <p>
     * 锁定账号如果已过自动解锁时间，则恢复为正常状态并允许登录；
     * 仍在锁定期则抛出异常并提示剩余时间。
     */
    private void ensureLoginAllowed(final UnifiedUserDO user)
            throws AccountLockedException, AccountDisabledException, FailedLoginException {
        if (user.getStatus() == null) {
            return;
        }
        // 锁定状态：尝试自动解锁
        if (user.getStatus() == CasConstant.USER_STATUS_LOCKED) {
            int unlocked = userMapper.unlockAccountIfNeeded(user.getId());
            if (unlocked > 0) {
                LOGGER.info("密码登录自动解锁: userId={}, lockUntilTime={}", user.getUserId(), user.getLockUntilTime());
                user.setStatus(CasConstant.USER_STATUS_ACTIVE);
                user.setFailedLoginCount(0);
                user.setLockUntilTime(null);
                return;
            }
            long remainingMinutes = calculateRemainingLockMinutes(user.getLockUntilTime());
            LOGGER.warn("账号仍在锁定期: userId={}, 剩余{}分钟", user.getUserId(), remainingMinutes);
            throw new AccountLockedException("账号已锁定，请" + remainingMinutes + "分钟后重试");
        }
        // 禁用状态：不允许登录
        if (user.getStatus() == CasConstant.USER_STATUS_DISABLED) {
            throw new AccountDisabledException("账号已禁用，请联系管理员");
        }
        // 已删除状态：统一模糊提示，防止账号枚举攻击
        if (user.getStatus() == CasConstant.USER_STATUS_DELETED) {
            LOGGER.debug("已删除账号尝试登录: userId={}", user.getUserId());
            throw new FailedLoginException("用户名或密码错误");
        }
    }

    /**
     * 处理登录失败：递增失败计数，达到阈值则锁定账号。
     */
    private void handleLoginFailure(final UnifiedUserDO user)
            throws AccountLockedException, FailedLoginException {
        // 1. 递增失败计数（原子操作）
        userMapper.incrementFailedLoginCount(user.getUserId());
        int newCount = (user.getFailedLoginCount() != null ? user.getFailedLoginCount() : 0) + 1;

        // 2. 达到锁定阈值：锁定账号
        if (newCount >= CasConstant.MAX_FAILED_LOGIN_ATTEMPTS) {
            LocalDateTime lockUntil = LocalDateTime.now().plusMinutes(CasConstant.LOCK_DURATION_MINUTES);
            userMapper.lockAccount(user.getUserId(), lockUntil);
            LOGGER.warn("连续登录失败{}次，账号已锁定: userId={}, 解锁时间={}",
                    newCount, user.getUserId(), lockUntil);
            throw new AccountLockedException(
                    "连续登录失败次数过多，账号已锁定，请" + CasConstant.LOCK_DURATION_MINUTES + "分钟后重试");
        }

        // 3. 未达阈值，提示剩余次数
        int remaining = CasConstant.MAX_FAILED_LOGIN_ATTEMPTS - newCount;
        LOGGER.debug("登录失败: userId={}, 已失败{}次, 剩余{}次", user.getUserId(), newCount, remaining);
        throw new FailedLoginException("用户名或密码错误，还剩" + remaining + "次尝试机会");
    }

    /**
     * 计算账号锁定的剩余分钟数（向上取整，最少1分钟）。
     */
    private long calculateRemainingLockMinutes(final LocalDateTime lockUntilTime) {
        if (lockUntilTime == null) {
            return CasConstant.LOCK_DURATION_MINUTES;
        }
        long seconds = Duration.between(LocalDateTime.now(), lockUntilTime).getSeconds();
        return Math.max(1, (seconds + 59) / 60);
    }

    @Override
    public boolean supports(final Class<? extends Credential> clazz) {
        return UsernamePasswordCredential.class.isAssignableFrom(clazz) && WebLoginModeResolver.isPasswordMode();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private UsernamePasswordCredential adaptCredential(final Credential credential) throws FailedLoginException {
        if (credential instanceof UsernamePasswordCredential usernamePasswordCredential) {
            return usernamePasswordCredential;
        }
        throw new FailedLoginException("不支持的登录凭证类型");
    }

    private Map<String, List<Object>> buildUserAttributes(final UnifiedUserDO user) {
        Map<String, List<Object>> attrs = new HashMap<>();
        attrs.put("userId", List.of(user.getUserId()));
        attrs.put("userType", List.of(user.getUserType() != null ? user.getUserType() : "CUSTOMER"));
        attrs.put("loginType", List.of("USERNAME_PASSWORD"));
        attrs.put("phoneVerified", List.of(Boolean.TRUE.equals(user.getPhoneVerified())));
        attrs.put("emailVerified", List.of(Boolean.TRUE.equals(user.getEmailVerified())));
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
