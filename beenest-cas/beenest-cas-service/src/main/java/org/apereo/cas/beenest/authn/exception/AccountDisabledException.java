package org.apereo.cas.beenest.authn.exception;

import org.apereo.cas.authentication.AuthenticationException;

/**
 * 账号禁用异常。
 * <p>
 * 当账号被管理员手动禁用（status=3）后尝试登录时抛出。
 * 禁用状态不会自动恢复，必须由管理员手动解除。
 * CAS webflow 通过 {@code authenticationFailure.AccountDisabledException} 映射错误提示。
 */
public class AccountDisabledException extends AuthenticationException {

    public AccountDisabledException(final String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return "AccountDisabledException";
    }
}
