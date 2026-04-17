package org.apereo.cas.beenest.client.filter;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.session.ActiveSessionRegistry;
import org.apereo.cas.beenest.client.session.CasUserSession;
import org.apereo.cas.beenest.client.session.SingleSignOutHandler;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * CAS SLO 回调过滤器
 * <p>
 * 监听 CAS Server 发来的 SAML Logout POST 请求，
 * 解析 LogoutRequest 提取 SessionIndex (ST)，
 * 通过 {@link ActiveSessionRegistry} 查找并销毁对应的本地 Session。
 * <p>
 * 关键：SLO POST 来自 CAS Server 后台调用，不携带用户 JSESSIONID Cookie，
 * 因此不能依赖 {@code httpRequest.getSession(false)}，
 * 必须通过全局 Session 注册表按 sessionId 查找目标 session。
 */
@Slf4j
public class CasLogoutFilter implements Filter {

    private final CasSecurityProperties properties;
    private final SingleSignOutHandler singleSignOutHandler;
    private final ActiveSessionRegistry activeSessionRegistry;
    private final BearerTokenCache bearerTokenCache;

    public CasLogoutFilter(CasSecurityProperties properties,
                           SingleSignOutHandler singleSignOutHandler,
                           ActiveSessionRegistry activeSessionRegistry,
                           BearerTokenCache bearerTokenCache) {
        this.properties = properties;
        this.singleSignOutHandler = singleSignOutHandler;
        this.activeSessionRegistry = activeSessionRegistry;
        this.bearerTokenCache = bearerTokenCache;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // 只处理 SLO 回调路径的 POST 请求（用 getServletPath 排除 contextPath）
        if (properties.getSlo().getCallbackPath().equals(httpRequest.getServletPath())
                && "POST".equalsIgnoreCase(httpRequest.getMethod())) {

            String logoutRequest = httpRequest.getParameter("logoutRequest");
            if (logoutRequest != null) {
                String sessionIndex = singleSignOutHandler.extractSessionIndexFromLogoutRequest(logoutRequest);
                if (sessionIndex != null) {
                    String sessionId = activeSessionRegistry.removeSessionIdBySt(sessionIndex);
                    if (sessionId != null) {
                        try {
                            HttpSession targetSession = activeSessionRegistry.getSession(sessionId);
                            String userId = activeSessionRegistry.getUserIdBySessionId(sessionId);
                            if (targetSession != null) {
                                Object sessionValue = targetSession.getAttribute(CasUserSession.SESSION_KEY);
                                userId = sessionValue instanceof CasUserSession userSession ? userSession.getUserId() : userId;

                                // 1. 先清理 bearer 缓存，避免会话刚失效但 token 仍在本地缓存中命中
                                if (bearerTokenCache != null && userId != null && !userId.isBlank()) {
                                    bearerTokenCache.removeByUserId(userId);
                                }

                                // 2. 再销毁本地 session，触发 HttpSessionListener 清理 session 索引
                                targetSession.invalidate();
                                LOGGER.info("SLO: Session 已销毁, sessionId={}", sessionId);
                            } else {
                                if (bearerTokenCache != null && userId != null && !userId.isBlank()) {
                                    bearerTokenCache.removeByUserId(userId);
                                }
                                LOGGER.debug("SLO: Session 已过期或不存在, sessionId={}", sessionId);
                            }
                        } catch (IllegalStateException e) {
                            LOGGER.debug("SLO: Session 已失效, sessionId={}", sessionId);
                        } catch (Exception e) {
                            LOGGER.warn("SLO: Session 销毁失败: sessionId={}, error={}", sessionId, e.getMessage());
                        }
                    }
                }
            }
            ((HttpServletResponse) response).setStatus(HttpServletResponse.SC_OK);
            return; // SLO 回调不需要继续 filter chain
        }

        chain.doFilter(request, response);
    }
}
