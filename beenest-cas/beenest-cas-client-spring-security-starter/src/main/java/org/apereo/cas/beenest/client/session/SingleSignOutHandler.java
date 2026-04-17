package org.apereo.cas.beenest.client.session;

import lombok.extern.slf4j.Slf4j;

/**
 * SLO (Single Logout) 处理器
 * <p>
 * 解析 CAS Server 发来的 SAML LogoutRequest，
 * 提取 SessionIndex (ST) 供 {@link org.apereo.cas.beenest.client.filter.CasLogoutFilter} 使用。
 * <p>
 * ST→SessionId 映射和查找委托给 {@link ActiveSessionRegistry}，
 * 后者通过 HttpSessionListener 在 session 销毁时自动清理孤儿 ST，防止内存泄漏。
 */
@Slf4j
public class SingleSignOutHandler {

    /**
     * 从 SAML LogoutRequest 中提取 SessionIndex (ST)
     */
    public String extractSessionIndexFromLogoutRequest(String logoutRequest) {
        if (logoutRequest == null) return null;

        // 简单 XML 解析提取 SessionIndex
        int start = logoutRequest.indexOf("<samlp:SessionIndex>");
        int end = logoutRequest.indexOf("</samlp:SessionIndex>");
        if (start >= 0 && end > start) {
            return logoutRequest.substring(start + "<samlp:SessionIndex>".length(), end);
        }
        return null;
    }
}
