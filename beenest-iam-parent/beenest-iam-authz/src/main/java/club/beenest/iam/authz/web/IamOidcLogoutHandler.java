package club.beenest.iam.authz.web;

import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.session.IamSessionCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

/**
 * OIDC 登出端点的会话清理逻辑。
 * <p>接入应用发起 RP-Initiated Logout 时,结束 IAM 会话并广播给其它应用,再清理容器会话与安全上下文。
 * 顺序不可颠倒:容器会话失效后取不到 sid。清理动作包括删除 Redis 会话快照与清除会话 Cookie。</p>
 */
public class IamOidcLogoutHandler implements LogoutHandler {

    private final IamLogoutService logoutService;
    private final SecurityContextLogoutHandler securityContextLogoutHandler =
            new SecurityContextLogoutHandler();

    public IamOidcLogoutHandler(IamLogoutService logoutService) {
        this.logoutService = logoutService;
    }

    /**
     * 结束会话并清理登录态。
     *
     * @param request        当前请求
     * @param response       当前响应
     * @param authentication 认证结果;可能为 null
     */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Authentication authentication) {
        // 1. 取 sid:会话标识随 Cookie 下发,注销后要一并清掉
        String sid = IamSessionCookie.read(request);
        // 2. 结束 IAM 会话并广播单点登出
        logoutService.logout(sid);
        // 3. 清掉会话 Cookie 与安全上下文
        IamSessionCookie.clear(response);
        securityContextLogoutHandler.logout(request, response, authentication);
    }
}
