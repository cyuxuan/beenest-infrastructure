package org.apereo.cas.beenest.client.config;

import org.apereo.cas.beenest.client.authentication.CasUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.client.validation.Assertion;
import org.springframework.security.cas.authentication.CasAssertionAuthenticationToken;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Assertion → CasUserDetailsService 桥接器
 * <p>
 * 将 Spring Security CAS 的 {@link AuthenticationUserDetailsService} 接口
 * 桥接到业务系统实现的 {@link CasUserDetailsService}。
 * <p>
 * CasAuthenticationProvider 验证 ST 成功后，使用此类从 Assertion 中
 * 提取 userId 和 attributes，委托给业务系统的 CasUserDetailsService 加载权限。
 */
@Slf4j
@RequiredArgsConstructor
public class CasAssertionUserDetailsService
        implements AuthenticationUserDetailsService<CasAssertionAuthenticationToken> {

    private final CasUserDetailsService delegate;

    @Override
    public UserDetails loadUserDetails(CasAssertionAuthenticationToken token)
            throws UsernameNotFoundException {
        Assertion assertion = token.getAssertion();
        String userId = assertion.getPrincipal().getName();
        log.debug("通过 CAS Assertion 加载用户: userId={}", userId);
        return delegate.loadUserByCasAssertion(userId, assertion);
    }
}
