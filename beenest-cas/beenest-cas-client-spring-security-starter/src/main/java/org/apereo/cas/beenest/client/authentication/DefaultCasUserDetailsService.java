package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.beenest.client.session.CasUserSession;
import org.apereo.cas.client.validation.Assertion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认 CAS 用户详情加载器。
 * <p>
 * 目标是让 starter 在业务应用未提供自定义权限加载器时也能直接运行：
 * <ul>
 *   <li>从 CAS Assertion 构建 {@link CasUserSession}</li>
 *   <li>自动提取 CAS 属性中的角色/权限字段</li>
 *   <li>如果没有任何权限属性，默认授予 {@code ROLE_USER}</li>
 *   <li>如果业务系统同时提供 {@link CasUserRegistrationService}，则可在首次登录时自动注册本地用户</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DefaultCasUserDetailsService implements CasUserDetailsService {

    private static final String[] ROLE_KEYS = {"roles", "role"};
    private static final String[] AUTHORITY_KEYS = {"authorities", "authority", "permissions", "permission", "grantedAuthorities", "grantedAuthority"};

    private final ObjectProvider<CasUserRegistrationService> registrationServiceProvider;

    @Override
    public UserDetails loadUserByCasAssertion(String userId, Assertion assertion) {
        CasUserSession session = CasUserSession.fromAssertion(assertion);
        if (!StringUtils.hasText(session.getUserId())) {
            session.setUserId(userId);
        }

        CasUserRegistrationService registrationService = registrationServiceProvider.getIfAvailable();
        if (registrationService != null && StringUtils.hasText(session.getUserId())) {
            maybeRegisterLocalUser(session, registrationService);
        }

        Collection<GrantedAuthority> authorities = extractAuthorities(assertion.getAttributes());
        if (authorities.isEmpty()) {
            authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return new CasUserDetails(session, authorities);
    }

    private void maybeRegisterLocalUser(CasUserSession session, CasUserRegistrationService registrationService) {
        try {
            if (!registrationService.userExists(session.getUserId())
                    && registrationService.canAutoRegister(session)) {
                String localUserId = registrationService.registerFromCas(session);
                if (StringUtils.hasText(localUserId)) {
                    session.setUserId(localUserId);
                }
                LOGGER.info("CAS 默认用户详情加载器已自动注册本地用户: userId={}", session.getUserId());
            }
        } catch (Exception e) {
            LOGGER.warn("CAS 默认用户详情加载器自动注册失败: userId={}", session.getUserId(), e);
        }
    }

    private Collection<GrantedAuthority> extractAuthorities(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return List.of();
        }

        Set<String> granted = new LinkedHashSet<>();
        for (String roleKey : ROLE_KEYS) {
            collectAuthorities(attributes.get(roleKey), granted, true);
        }
        for (String authorityKey : AUTHORITY_KEYS) {
            collectAuthorities(attributes.get(authorityKey), granted, false);
        }

        if (granted.isEmpty()) {
            Object fallbackAuthorities = attributes.get("authorities");
            if (fallbackAuthorities != null) {
                collectAuthorities(fallbackAuthorities, granted, false);
            }
        }

        return granted.stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    private void collectAuthorities(Object rawValue, Set<String> granted, boolean roleMode) {
        if (rawValue == null) {
            return;
        }
        if (rawValue instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectAuthorities(item, granted, roleMode);
            }
            return;
        }
        if (rawValue.getClass().isArray()) {
            Object[] array = (Object[]) rawValue;
            for (Object item : array) {
                collectAuthorities(item, granted, roleMode);
            }
            return;
        }

        String[] values = StringUtils.tokenizeToStringArray(rawValue.toString(), ",; \t\r\n");
        if (values == null || values.length == 0) {
            addAuthorityToken(rawValue.toString(), granted, roleMode);
            return;
        }
        for (String value : values) {
            addAuthorityToken(value, granted, roleMode);
        }
    }

    private void addAuthorityToken(String value, Set<String> granted, boolean roleMode) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.trim();
        if (roleMode && !normalized.regionMatches(true, 0, "ROLE_", 0, 5)) {
            normalized = "ROLE_" + normalized.toUpperCase();
        }
        granted.add(normalized);
    }
}
