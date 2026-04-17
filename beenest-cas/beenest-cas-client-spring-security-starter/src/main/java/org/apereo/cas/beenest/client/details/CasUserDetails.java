package org.apereo.cas.beenest.client.details;

import org.apereo.cas.beenest.client.session.CasUserSession;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Map;

/**
 * CAS 用户详情
 * <p>
 * 实现 Spring Security 的 {@link UserDetails}，包装 {@link CasUserSession}。
 * {@code getUsername()} 返回 userId（CAS 用户 ID 是系统中的唯一标识）。
 * CAS 认证不使用本地密码，{@code getPassword()} 始终返回 null。
 */
@Getter
public class CasUserDetails implements UserDetails {

    private final CasUserSession casUserSession;
    private final Collection<? extends GrantedAuthority> authorities;

    public CasUserDetails(CasUserSession casUserSession,
                          Collection<? extends GrantedAuthority> authorities) {
        this.casUserSession = casUserSession;
        this.authorities = authorities;
    }

    // --- UserDetails 接口实现 ---

    /**
     * 返回 userId（CAS 用户 ID 是系统中的唯一标识）
     */
    @Override
    public String getUsername() {
        return casUserSession.getUserId();
    }

    /**
     * CAS 认证不使用本地密码
     */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // --- 扩展方法 ---

    public String getUserId() {
        return casUserSession.getUserId();
    }

    public String getNickname() {
        return casUserSession.getNickname();
    }

    public String getUserType() {
        return casUserSession.getUserType();
    }

    public String getPhone() {
        return casUserSession.getPhone();
    }

    public String getEmail() {
        return casUserSession.getEmail();
    }

    public String getAvatarUrl() {
        return casUserSession.getAvatarUrl();
    }

    public String getIdentity() {
        return casUserSession.getIdentity();
    }

    public Map<String, String> getAttributes() {
        return casUserSession.getAttributes();
    }
}
