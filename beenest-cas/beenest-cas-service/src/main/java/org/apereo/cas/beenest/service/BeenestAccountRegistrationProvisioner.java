package org.apereo.cas.beenest.service;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.acct.provision.AccountRegistrationProvisioner;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.common.util.UserTypeUtils;
import org.apereo.cas.beenest.config.AutoGrantProperties;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

/**
 * Beenest CAS 原生账号注册落库器。
 * <p>
 * 保留 Apereo CAS 的原生 account-registration webflow，
 * 在激活完成后把用户名密码账号写入统一的 cas_user 表。
 * <p>
 * 注册成功后会自动赋予 {@link AutoGrantProperties} 中配置的角色，
 * 确保与 {@link UserIdentityService#safeCreate} 的行为一致。
 */
@Slf4j
@RequiredArgsConstructor
public class BeenestAccountRegistrationProvisioner implements AccountRegistrationProvisioner {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(12);

    private final UnifiedUserMapper userMapper;
    private final AutoGrantProperties autoGrantProperties;

    /**
     * 根据 CAS 原生注册请求创建用户名密码账号。
     * <p>
     * 注册成功后自动赋予 auto-grant 角色，与 {@link UserIdentityService} 的
     * {@code safeCreate()} 行为保持一致。
     *
     * @param request CAS 原生注册请求
     * @return 注册响应
     */
    @Override
    public AccountRegistrationResponse provision(AccountRegistrationRequest request) {
        try {
            new BeenestAccountRegistrationRequestValidator(userMapper, true).validate(request);
            UnifiedUserDO user = buildUser(request);
            userMapper.insert(user);
            // 新注册用户自动赋权（与 UserIdentityService.safeCreate() 行为一致）
            autoGrantRoles(user.getUserId());
            LOGGER.info("CAS 原生账号注册成功: userId={}, username={}", user.getUserId(), user.getUsername());
            return AccountRegistrationResponse.success().putProperty("userId", user.getUserId());
        } catch (DuplicateKeyException e) {
            LOGGER.warn("CAS 原生账号注册唯一键冲突: username={}",
                    BeenestAccountRegistrationRequestValidator.getString(request, "username"));
            return failure("注册账号已存在");
        } catch (IllegalArgumentException e) {
            LOGGER.warn("CAS 原生账号注册参数无效: {}", e.getMessage());
            return failure(e.getMessage());
        }
    }

    /**
     * 为新注册用户自动赋予 auto-grant 角色。
     * <p>
     * 遍历 autoGrantRoles 配置，当 Service ID 在 autoGrantServiceIds 中时，
     * 为用户赋予对应角色。角色名与 Service JSON 的
     * {@code accessStrategy.requiredAttributes.memberOf} 对应。
     *
     * @param userId 用户 ID
     */
    private void autoGrantRoles(String userId) {
        for (var entry : autoGrantProperties.getAutoGrantRoles().entrySet()) {
            if (autoGrantProperties.getAutoGrantServiceIds().contains(entry.getKey())) {
                userMapper.addRole(userId, entry.getValue());
                LOGGER.info("原生注册自动赋权: userId={}, serviceId={}, role={}",
                        userId, entry.getKey(), entry.getValue());
            }
        }
    }

    private UnifiedUserDO buildUser(AccountRegistrationRequest request) {
        String username = BeenestAccountRegistrationRequestValidator.getString(request, "username");
        String password = BeenestAccountRegistrationRequestValidator.getString(request, "password");
        String email = BeenestAccountRegistrationRequestValidator.getString(request, "email");
        String phone = BeenestAccountRegistrationRequestValidator.getString(request, "phone");
        String userType = BeenestAccountRegistrationRequestValidator.getString(request, "userType");

        UnifiedUserDO user = new UnifiedUserDO();
        user.setUserId(generateUserId());
        user.setUsername(username);
        user.setPasswordHash(PASSWORD_ENCODER.encode(password));
        user.setNickname(resolveNickname(request));
        user.setEmail(email);
        user.setPhone(phone);
        user.setUserType(UserTypeUtils.normalizeSelfRegistration(userType));
        user.setSource("WEB");
        user.setLoginType("USERNAME_PASSWORD");
        user.setPhoneVerified(Boolean.FALSE);
        user.setEmailVerified(StringUtils.isNotBlank(email));
        user.setMfaEnabled(Boolean.FALSE);
        user.setStatus(CasConstant.USER_STATUS_ACTIVE);
        user.setFailedLoginCount(0);
        user.setTokenVersion(1);
        return user;
    }

    private String resolveNickname(AccountRegistrationRequest request) {
        String firstName = StringUtils.defaultString(
                BeenestAccountRegistrationRequestValidator.getString(request, "firstName"));
        String lastName = StringUtils.defaultString(
                BeenestAccountRegistrationRequestValidator.getString(request, "lastName"));
        return StringUtils.trimToNull(firstName + lastName);
    }

    private String generateUserId() {
        return "U" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private AccountRegistrationResponse failure(String message) {
        return AccountRegistrationResponse.failure().putProperty("message", message);
    }
}
