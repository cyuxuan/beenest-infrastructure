package org.apereo.cas.beenest.authn.handler;

import org.apereo.cas.beenest.authn.web.WebLoginModeResolver;
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
public class UsernamePasswordAuthenticationHandler implements AuthenticationHandler, Ordered {

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
            throw new FailedLoginException("账号不存在");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new FailedLoginException("账号已禁用");
        }
        if (StringUtils.isBlank(user.getPasswordHash())) {
            throw new FailedLoginException("账号未设置密码");
        }
        if (!PASSWORD_ENCODER.matches(rawPassword, user.getPasswordHash())) {
            throw new FailedLoginException("用户名或密码错误");
        }

        Map<String, List<Object>> attributes = buildUserAttributes(user);
        Principal principal = principalFactory.createPrincipal(user.getUserId(), attributes);
        return new DefaultAuthenticationHandlerExecutionResult(this, credential, principal, List.of());
    }

    @Override
    public boolean supports(final Credential credential) {
        return credential instanceof UsernamePasswordCredential && WebLoginModeResolver.isPasswordMode();
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
