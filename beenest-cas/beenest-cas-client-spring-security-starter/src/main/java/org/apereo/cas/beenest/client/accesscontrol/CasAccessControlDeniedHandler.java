package org.apereo.cas.beenest.client.accesscontrol;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CAS 访问控制拒绝处理器。
 * <p>
 * 当访问控制检查未通过时，清除用户会话并返回 403 响应。
 * 在 Web Session 模式下使用。
 */
@Slf4j
@RequiredArgsConstructor
public class CasAccessControlDeniedHandler implements AuthenticationSuccessHandler {

    private final CasAccessControlProperties properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        log.warn("访问控制: 用户 {} 被拒绝访问, 清除会话", authentication.getName());

        // 1. 清除会话
        if (properties.isForceLogoutOnDisable()) {
            var session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }

        // 2. 返回 403 JSON 响应
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":403,\"message\":\"访问权限已变更，请联系管理员\"}");
    }
}