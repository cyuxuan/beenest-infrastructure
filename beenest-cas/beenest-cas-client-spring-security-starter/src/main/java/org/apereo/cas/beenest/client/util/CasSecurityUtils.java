package org.apereo.cas.beenest.client.util;

import org.apereo.cas.beenest.client.details.CasUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.cas.authentication.CasAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAS Security 工具类
 * <p>
 * 提供获取当前登录用户信息的便捷静态方法。
 * 所有方法从 Spring Security 的 SecurityContext 读取（非 HttpSession）。
 * <p>
 * 替代旧版 CasClientUtils（基于 ThreadLocal + HttpSession），
 * 新版本直接使用 Spring Security 标准机制。
 */
public final class CasSecurityUtils {

    private CasSecurityUtils() {}

    /**
     * 从 SecurityContext 获取当前用户 ID
     */
    public static String getCurrentUserId() {
        CasUserDetails details = getCurrentUserDetails();
        return details != null ? details.getUserId() : null;
    }

    /**
     * 从 SecurityContext 获取当前用户详细信息
     */
    public static CasUserDetails getCurrentUserDetails() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        if (auth.getPrincipal() instanceof CasUserDetails details) {
            return details;
        }
        return null;
    }

    /**
     * 是否已登录
     */
    public static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof CasUserDetails;
    }

    /**
     * 获取 Proxy Ticket（用于服务间调用）
     *
     * @param targetUrl 目标服务 URL
     * @return Proxy Ticket 字符串，获取失败返回 null
     */
    public static String getProxyTicketFor(String targetUrl) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof CasAuthenticationToken casToken) {
            return casToken.getAssertion().getPrincipal().getProxyTicketFor(targetUrl);
        }
        return null;
    }

    /**
     * 登出：清除 SecurityContext + 使 HttpSession 失效
     *
     * @param request 当前 HTTP 请求（用于使 session 失效）
     */
    public static void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
