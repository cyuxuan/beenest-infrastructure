package org.apereo.cas.beenest.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;

/**
 * CAS 请求上下文工具。
 * <p>
 * 用于统一解析客户端 IP 和 User-Agent，避免登录控制器重复实现同一套请求头兼容逻辑。
 */
public final class CasRequestContextUtils {

    private CasRequestContextUtils() {
    }

    /**
     * 解析客户端真实 IP。
     *
     * @param request 当前 HTTP 请求
     * @return 客户端 IP
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StringUtils.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 解析客户端 User-Agent。
     *
     * @param request 当前 HTTP 请求
     * @return User-Agent，可能为空
     */
    public static String resolveUserAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
