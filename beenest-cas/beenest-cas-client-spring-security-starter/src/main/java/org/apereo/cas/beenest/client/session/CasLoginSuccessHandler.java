package org.apereo.cas.beenest.client.session;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * CAS 登录成功处理器。
 * <p>
 * 负责在 CAS 认证成功后，把当前 HTTP Session 与 ST / userId / 用户会话快照写入
 * {@link ActiveSessionRegistry}，确保后续单点登出、用户同步和会话定位都能命中同一条索引链路。
 * <p>
 * 处理完成后再交给 Spring Security 的默认成功处理器，保留原有跳转行为。
 */
@Slf4j
public class CasLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String SERVICE_TICKET_PARAM = "ticket";

    private final ActiveSessionRegistry activeSessionRegistry;
    private final AuthenticationSuccessHandler delegate;

    /**
     * 构造 CAS 登录成功处理器。
     *
     * @param activeSessionRegistry 活跃会话注册表
     */
    public CasLoginSuccessHandler(ActiveSessionRegistry activeSessionRegistry) {
        this(activeSessionRegistry, new SavedRequestAwareAuthenticationSuccessHandler());
    }

    /**
     * 构造 CAS 登录成功处理器。
     *
     * @param activeSessionRegistry 活跃会话注册表
     * @param delegate              后续成功处理器，用于保持原有跳转行为
     */
    public CasLoginSuccessHandler(ActiveSessionRegistry activeSessionRegistry,
                                  AuthenticationSuccessHandler delegate) {
        this.activeSessionRegistry = activeSessionRegistry;
        this.delegate = delegate;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        // 1. 仅在 principal 是我们自己的 CasUserDetails 时，才写入本地会话快照
        if (authentication != null && authentication.getPrincipal() instanceof CasUserDetails casUserDetails) {
            // 2. 提前创建 Session，并把 CAS 用户会话和 ST 一并写入注册表
            CasUserSession userSession = casUserDetails.getCasUserSession();
            String serviceTicket = request.getParameter(SERVICE_TICKET_PARAM);
            HttpSession httpSession = request.getSession(true);
            activeSessionRegistry.registerAuthenticatedSession(httpSession, userSession, serviceTicket);
            LOGGER.info("CAS 登录成功，已注册本地会话: userId={}, sessionId={}",
                    userSession.getUserId(), httpSession.getId());
        } else {
            LOGGER.debug("CAS 登录成功，但 principal 不是 CasUserDetails，跳过会话注册");
        }

        // 3. 保留 Spring Security 默认跳转逻辑
        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}
