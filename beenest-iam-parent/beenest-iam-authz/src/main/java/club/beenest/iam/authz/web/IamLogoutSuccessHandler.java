package club.beenest.iam.authz.web;

import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.session.IamSessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

import java.io.IOException;

/**
 * 注销成功处理器。
 * <p>结束会话并广播单点登出(见 {@link IamLogoutService}),清除会话 Cookie 后回到登录页。</p>
 */
public class IamLogoutSuccessHandler implements LogoutSuccessHandler {

    private final IamLogoutService logoutService;

    public IamLogoutSuccessHandler(IamLogoutService logoutService) {
        this.logoutService = logoutService;
    }

    /**
     * 结束会话、清除 Cookie 并跳转登录页。
     *
     * @param request        当前请求
     * @param response       当前响应
     * @param authentication 认证结果;可能为 null
     * @throws IOException 跳转失败
     */
    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                 Authentication authentication) throws IOException {
        // 1. 会话标识由 IamLogoutSidCaptureFilter 提前捕获
        String sid = (String) request.getAttribute(IamLogoutSidCaptureFilter.SID_ATTRIBUTE);
        if (sid == null) {
            sid = IamSessionCookie.read(request);
        }
        // 2. 结束会话并广播给接入应用
        if (sid != null) {
            logoutService.logout(sid);
        }
        // 3. 清掉会话 Cookie:浏览器不再携带已注销的会话标识
        IamSessionCookie.clear(response);
        // 4. 回到登录页
        response.sendRedirect("/login");
    }
}
