package club.beenest.iam.authz.web;

import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 账户待办拦截过滤器。
 * <p>会话挂着待办事项时,除办理待办所需的入口外一律拦住:页面重定向到待办页,
 * 接口返回 403 与专用错误码;用户不必重新登录,待办办理完成前也不能访问业务接口。</p>
 * <p>放行清单按前缀匹配,只覆盖办理待办本身需要的路径:改密、绑定二步验证、
 * 验证邮箱、查看待办、注销。</p>
 */
public class IamRequiredActionFilter extends OncePerRequestFilter {

    /** 待办页与办理接口的前缀(放行) */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/required-action",
            // 用户中心:待办里的绑定动态口令/Passkey 要在这里办理,页面本身也给出待办入口
            "/account",
            // 认证侧:查看待办、改密、二步验证、邮箱验证、注销
            "/api/v1/auth/required-actions",
            "/api/v1/auth/password",
            "/api/v1/auth/mfa",
            "/api/v1/auth/email-verification",
            "/api/v1/auth/logout",
            "/api/v1/auth/me",
            // 账户中心侧:办理待办需要的入口 + 权限码(前端外壳渲染用)
            "/api/v1/console/password",
            "/api/v1/console/mfa",
            "/api/v1/console/webauthn",
            "/api/v1/console/profile",
            "/api/v1/console/my-permissions",
            "/verify-email",
            "/logout",
            "/error",
            "/theme-static/", "/console-static/", "/portal-static/",
            "/assets/", "/css/", "/js/", "/themes/", "/favicon.ico"
    );

    /** 待办页路径 */
    private static final String REQUIRED_ACTION_PAGE = "/required-action";

    private final ObjectMapper objectMapper;

    public IamRequiredActionFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 按会话待办状态拦截请求。
     * <p>无会话或会话无待办时直接放行(Bearer 令牌请求不经过会话,没有待办语义);
     * 命中放行清单时放行;其余请求中 {@code /api/} 前缀返回 403 与
     * {@code REQUIRED_ACTION_PENDING},其它路径重定向到待办页。</p>
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 过滤器链
     * @throws ServletException 后续过滤器链抛出
     * @throws IOException      写出响应或重定向失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        IamSessionPayload payload = IamRedisSecurityContextRepository.currentPayload(request);
        if (payload == null || !payload.hasRequiredActions()) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (isAllowed(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/")) {
            ResultJsonWriter.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                    Result.fail(ErrorCode.REQUIRED_ACTION_PENDING));
            return;
        }
        response.sendRedirect(request.getContextPath() + REQUIRED_ACTION_PAGE);
    }

    /**
     * 判定请求路径是否命中放行清单。
     *
     * @param path 请求路径
     * @return true 表示放行
     */
    private static boolean isAllowed(String path) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
