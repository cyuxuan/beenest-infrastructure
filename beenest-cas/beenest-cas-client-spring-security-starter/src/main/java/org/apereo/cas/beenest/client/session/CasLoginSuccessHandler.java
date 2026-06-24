package org.apereo.cas.beenest.client.session;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.springframework.security.core.Authentication;
import org.apereo.cas.beenest.client.accesscontrol.CasAccessControlDeniedHandler;
import org.apereo.cas.beenest.client.accesscontrol.CasAccessControlManager;
import org.apereo.cas.beenest.client.accesscontrol.AccessControlResult;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * CAS 登录成功处理器。
 * <p>
 * 负责在 CAS 认证成功后，把当前 HTTP Session 与 ST / userId / 用户会话快照写入
 * {@link ActiveSessionRegistry}，确保后续单点登出和会话定位都能命中同一条索引链路。
 * <p>
 * 处理完成后再交给 Spring Security 的默认成功处理器，保留原有跳转行为。
 */
@Slf4j
public class CasLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String SERVICE_TICKET_PARAM = "ticket";

    private final ActiveSessionRegistry activeSessionRegistry;
    private final AuthenticationSuccessHandler delegate;

    /** 访问控制协调器（可选，未启用时为 null） */
    private final CasAccessControlManager accessControlManager;

    /** 访问控制拒绝处理器（可选，未启用时为 null） */
    private final CasAccessControlDeniedHandler deniedHandler;

    /**
     * 构造 CAS 登录成功处理器。
     *
     * @param activeSessionRegistry 活跃会话注册表
     */
    public CasLoginSuccessHandler(ActiveSessionRegistry activeSessionRegistry) {
        this(activeSessionRegistry, new SavedRequestAwareAuthenticationSuccessHandler(), null, null);
    }

    /**
     * 构造 CAS 登录成功处理器。
     *
     * @param activeSessionRegistry 活跃会话注册表
     * @param delegate              后续成功处理器，用于保持原有跳转行为
     */
    public CasLoginSuccessHandler(ActiveSessionRegistry activeSessionRegistry,
                                  AuthenticationSuccessHandler delegate) {
        this(activeSessionRegistry, delegate, null, null);
    }

    /**
     * 构造 CAS 登录成功处理器（含访问控制）。
     *
     * @param activeSessionRegistry 活跃会话注册表
     * @param delegate              后续成功处理器
     * @param accessControlManager  访问控制协调器（可选）
     * @param deniedHandler         访问控制拒绝处理器（可选）
     */
    public CasLoginSuccessHandler(ActiveSessionRegistry activeSessionRegistry,
                                  AuthenticationSuccessHandler delegate,
                                  CasAccessControlManager accessControlManager,
                                  CasAccessControlDeniedHandler deniedHandler) {
        this.activeSessionRegistry = activeSessionRegistry;
        this.delegate = delegate;
        this.accessControlManager = accessControlManager;
        this.deniedHandler = deniedHandler;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        // 1. 访问控制 SPI 同步（如果启用）
        //    CAS 服务端 accessStrategy 已完成权限校验，此处只做本地用户状态同步
        if (accessControlManager != null && accessControlManager.isEnabled()) {
            Map<String, Object> casAttributes = extractCasAttributes(authentication);
            String userId = authentication.getName();

            AccessControlResult result = accessControlManager.onAuthentication(userId, casAttributes);
            if (!result.granted()) {
                deniedHandler.onAuthenticationSuccess(request, response, authentication);
                return;
            }
        }

        // 2. 仅在 principal 是我们自己的 CasUserDetails 时，才写入本地会话快照
        if (authentication != null && authentication.getPrincipal() instanceof CasUserDetails casUserDetails) {
            // 3. 提前创建 Session，并把 CAS 用户会话和 ST 一并写入注册表
            CasUserSession userSession = casUserDetails.getCasUserSession();
            String serviceTicket = request.getParameter(SERVICE_TICKET_PARAM);
            HttpSession httpSession = request.getSession(true);
            activeSessionRegistry.registerAuthenticatedSession(httpSession, userSession, serviceTicket);
            log.info("CAS 登录成功，已注册本地会话: userId={}, sessionId={}",
                    userSession.getUserId(), httpSession.getId());
        } else {
            log.debug("CAS 登录成功，但 principal 不是 CasUserDetails，跳过会话注册");
        }

        // 4. 保留 Spring Security 默认跳转逻辑
        delegate.onAuthenticationSuccess(request, response, authentication);
    }

    /**
     * 从认证信息中提取 CAS 属性（包含 memberOf 等多值属性）。
     */
    private Map<String, Object> extractCasAttributes(Authentication authentication) {
        if (authentication.getPrincipal() instanceof CasUserDetails userDetails) {
            return new HashMap<>(userDetails.getAttributes());
        }
        return Map.of();
    }
}
