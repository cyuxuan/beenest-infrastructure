package org.apereo.cas.beenest.service;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationRequestValidator;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * Beenest 原生账号注册请求校验器。
 * <p>
 * 初始注册阶段尚未填写密码，只校验用户名、邮箱、手机号和用户类型；
 * 最终开户注册阶段再要求密码存在。
 */
@RequiredArgsConstructor
public class BeenestAccountRegistrationRequestValidator implements AccountRegistrationRequestValidator {

    private final UnifiedUserMapper userMapper;
    private final boolean requirePassword;

    /**
     * 校验 CAS 原生注册请求。
     *
     * @param request 原生注册请求
     */
    @Override
    public void validate(AccountRegistrationRequest request) {
        String username = getString(request, "username");
        String password = getString(request, "password");
        String email = getString(request, "email");
        String phone = getString(request, "phone");

        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (requirePassword && StringUtils.isBlank(password)) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (userMapper.selectByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        if (StringUtils.isNotBlank(email) && userMapper.selectByEmail(email) != null) {
            throw new IllegalArgumentException("邮箱已被注册");
        }
        if (StringUtils.isNotBlank(phone) && userMapper.selectByPhone(phone) != null) {
            throw new IllegalArgumentException("手机号已被注册");
        }
    }

    static String getString(AccountRegistrationRequest request, String name) {
        Object value = request.getProperties().get(name);
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }
}
