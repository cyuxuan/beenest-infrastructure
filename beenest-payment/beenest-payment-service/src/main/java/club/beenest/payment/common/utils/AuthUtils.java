package club.beenest.payment.common.utils;

import java.util.List;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 认证工具类
 */
public class AuthUtils {

    private AuthUtils() {
    }

    public static String getCurrentUserId() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsString();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public static String getCurrentUsername() {
        try {
            if (StpUtil.isLogin()) {
                Object username = StpUtil.getSession().get("username");
                if (username != null) {
                    return username.toString();
                }
                return StpUtil.getLoginIdAsString();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public static String requireCurrentUserId() {
        String userId = getCurrentUserId();
        if (userId == null || userId.isEmpty()) {
            throw new SecurityException("用户未登录或身份验证失败");
        }
        return userId;
    }

    public static boolean isLogin() {
        try {
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
        }
    }

    public static String getCurrentToken() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getTokenValue();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    public static boolean hasRole(String role) {
        try {
            return StpUtil.hasRole(role);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasPermission(String permission) {
        try {
            return StpUtil.hasPermission(permission);
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> getRoles() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getRoleList();
            }
        } catch (Exception e) {
            // ignore
        }
        return java.util.Collections.emptyList();
    }

    public static java.util.List<String> getPermissions() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getPermissionList();
            }
        } catch (Exception e) {
            // ignore
        }
        return java.util.Collections.emptyList();
    }
}
