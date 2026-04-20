package club.beenest.payment.common.utils;

import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.beenest.client.util.CasSecurityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 认证工具类。
 * <p>
 * 统一从 Spring Security / CAS SecurityContext 中获取当前登录用户信息，
 * 替代原先的 Sa-Token 读取方式。
 */
public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID，未登录时返回 null
     */
    public static String getCurrentUserId() {
        CasUserDetails userDetails = CasSecurityUtils.getCurrentUserDetails();
        if (userDetails != null && StringUtils.hasText(userDetails.getUserId())) {
            return userDetails.getUserId();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CasUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /**
     * 获取当前登录用户名称。
     *
     * @return 用户昵称或用户 ID，未登录时返回 null
     */
    public static String getCurrentUsername() {
        CasUserDetails userDetails = CasSecurityUtils.getCurrentUserDetails();
        if (userDetails == null) {
            return null;
        }
        if (StringUtils.hasText(userDetails.getNickname())) {
            return userDetails.getNickname();
        }
        return userDetails.getUserId();
    }

    /**
     * 获取当前用户 ID；如果未登录则抛出异常。
     *
     * @return 当前用户 ID
     */
    public static String requireCurrentUserId() {
        String userId = getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new SecurityException("用户未登录或身份验证失败");
        }
        return userId;
    }

    /**
     * 判断当前请求是否已登录。
     *
     * @return true 表示已认证
     */
    public static boolean isLogin() {
        return CasSecurityUtils.isAuthenticated();
    }

    /**
     * 获取当前请求携带的凭证串。
     *
     * @return Bearer Token 或其他认证凭证，未登录时返回 null
     */
    public static String getCurrentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object credentials = authentication.getCredentials();
        return credentials != null ? credentials.toString() : null;
    }

    /**
     * 判断当前用户是否拥有指定角色。
     *
     * @param role 角色名
     * @return true 表示拥有该角色
     */
    public static boolean hasRole(String role) {
        if (!StringUtils.hasText(role)) {
            return false;
        }
        String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role.toUpperCase();
        return getAuthorities().contains(normalized);
    }

    /**
     * 判断当前用户是否拥有指定权限。
     *
     * @param permission 权限名
     * @return true 表示拥有该权限
     */
    public static boolean hasPermission(String permission) {
        if (!StringUtils.hasText(permission)) {
            return false;
        }
        return getAuthorities().contains(permission.trim());
    }

    /**
     * 获取当前用户的角色列表。
     *
     * @return 角色列表，未登录时返回空列表
     */
    public static List<String> getRoles() {
        return getAuthorities().stream()
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    /**
     * 获取当前用户的权限列表。
     *
     * @return 权限列表，未登录时返回空列表
     */
    public static List<String> getPermissions() {
        return getAuthorities().stream()
                .filter(authority -> !authority.startsWith("ROLE_"))
                .collect(Collectors.toList());
    }

    /**
     * 提取当前用户的所有权限字符串。
     *
     * @return 权限字符串集合
     */
    private static List<String> getAuthorities() {
        CasUserDetails userDetails = CasSecurityUtils.getCurrentUserDetails();
        if (userDetails == null || userDetails.getAuthorities() == null) {
            return Collections.emptyList();
        }
        return userDetails.getAuthorities().stream()
                .filter(Objects::nonNull)
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }
}
