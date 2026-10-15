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
 * 确保与 {@link UserIdentityService#safeCreate(UnifiedUserDO)} 的行为一致。
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
    /**
     * 自助注册渠道开户落库（CAS 原生注册 webflow / 机器通道）。
     * <p>
     * 自助渠道不允许创建 ADMIN 账号：userType 走
     * {@link UserTypeUtils#normalizeSelfRegistration(String)}，非法值统一降级为 CUSTOMER。
     */
    @Override
    public AccountRegistrationResponse provision(AccountRegistrationRequest request) {
        return doProvision(request, false);
    }

    /**
     * 受信渠道开户落库（管理端点 / 机器通道调用）。
     * <p>
     * 与自助注册的差异：userType 走 {@link UserTypeUtils#normalize(String)}（允许 ADMIN）；
     * 受信渠道创建的 ADMIN 账号额外授予 {@link CasConstant#ROLE_ADMIN}。
     */
    public AccountRegistrationResponse provisionAdmin(AccountRegistrationRequest request) {
        return doProvision(request, true);
    }

    private AccountRegistrationResponse doProvision(AccountRegistrationRequest request, boolean trusted) {
        try {
            new BeenestAccountRegistrationRequestValidator(userMapper, true).validate(request);
            UnifiedUserDO user = buildUser(request, trusted);
            userMapper.insert(user);
            // 新注册用户自动赋权（与 UserIdentityService.safeCreate() 行为一致）
            autoGrantRoles(user.getUserId());
            // 受信渠道创建的 ADMIN 账号额外授予管理角色
            if (trusted && "ADMIN".equals(user.getUserType())) {
                userMapper.addRole(user.getUserId(), CasConstant.ROLE_ADMIN);
                LOGGER.info("受信渠道授予管理角色: userId={}, role={}", user.getUserId(), CasConstant.ROLE_ADMIN);
            }
            LOGGER.info("CAS 账号注册成功: userId={}, username={}, userType={}, trusted={}",
                    user.getUserId(), user.getUsername(), user.getUserType(), trusted);
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
     * 遍历 auto-grant 规则列表，为用户授予每条规则定义的所有角色。
     * 角色可以跨应用，例如注册时可同时授予 ROLE_DRONE_SYSTEM + ROLE_PAYMENT。
     * <p>
     * CAS 的 accessStrategy 会在 ST 验证时按 service 过滤 memberOf 属性，
     * 因此注册时授予所有角色是安全的。
     *
     * @param userId 用户 ID
     */
    private void autoGrantRoles(String userId) {
        for (var rule : autoGrantProperties.getAutoGrant()) {
            for (String role : rule.getRoles()) {
                userMapper.addRole(userId, role);
                LOGGER.info("原生注册自动赋权: userId={}, serviceIds={}, role={}",
                        userId, rule.getServiceIds(), role);
            }
        }
    }

    private UnifiedUserDO buildUser(AccountRegistrationRequest request, boolean trusted) {
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
        // 受信渠道允许创建 ADMIN 账号；自助渠道统一降级为自助可注册类型
        user.setUserType(trusted
                ? UserTypeUtils.normalize(userType)
                : UserTypeUtils.normalizeSelfRegistration(userType));
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
