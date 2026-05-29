package org.apereo.cas.beenest.authn.exception;

import org.apereo.cas.authentication.AuthenticationException;

/**
 * 账号锁定异常。
 * <p>
 * 当用户连续登录失败次数达到阈值，或账号处于锁定期间尝试登录时抛出。
 * CAS webflow 通过 {@code authenticationFailure.AccountLockedException} 映射错误提示。
 */
public class AccountLockedException extends AuthenticationException {

    public AccountLockedException(final String message) {
        super(message);
    }

    @Override
    public String getCode() {
        return "AccountLockedException";
    }
}
