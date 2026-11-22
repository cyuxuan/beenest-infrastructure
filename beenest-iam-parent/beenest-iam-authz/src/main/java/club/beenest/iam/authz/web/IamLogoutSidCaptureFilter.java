package club.beenest.iam.authz.web;

import club.beenest.iam.authz.session.IamSessionCookie;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 注销会话标识捕获过滤器。
 * <p>排在 {@code LogoutFilter} 之前:注销链路先执行 {@code SecurityContextLogoutHandler} 使容器会话失效,
 * 成功处理器中再取会话只能拿到 null。sid 取自会话 Cookie,在注销请求进入时写入请求属性。</p>
 */
public class IamLogoutSidCaptureFilter extends OncePerRequestFilter {

    /** 请求属性名:注销请求的会话标识 */
    public static final String SID_ATTRIBUTE = "club.beenest.iam.LOGOUT_SID";

    private final RequestMatcher logoutMatcher;

    public IamLogoutSidCaptureFilter(RequestMatcher logoutMatcher) {
        this.logoutMatcher = logoutMatcher;
    }

    /**
     * 注销请求上捕获 sid 并写入请求属性。
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 过滤器链
     * @throws ServletException 后续过滤器处理失败
     * @throws IOException      后续过滤器处理失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 只在注销请求上取 sid:其余请求不需要,也避免多一次 Cookie 解析
        if (logoutMatcher.matches(request)) {
            String sid = IamSessionCookie.read(request);
            if (sid != null) {
                request.setAttribute(SID_ATTRIBUTE, sid);
            }
        }
        filterChain.doFilter(request, response);
    }
}
