package club.beenest.iam.authz.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证入口点分流:接口返回 JSON,页面跳转登录页。
 * <p>业务链同时承载浏览器页面与 REST 接口,按请求路径前缀区分两类调用方。</p>
 */
public class IamPageAwareAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** 接口前缀:与安全配置中的 API 匹配规则保持一致 */
    private static final String API_PREFIX = "/api/";

    private final AuthenticationEntryPoint apiEntryPoint;
    private final AuthenticationEntryPoint pageEntryPoint;

    public IamPageAwareAuthenticationEntryPoint(AuthenticationEntryPoint apiEntryPoint,
                                                AuthenticationEntryPoint pageEntryPoint) {
        this.apiEntryPoint = apiEntryPoint;
        this.pageEntryPoint = pageEntryPoint;
    }

    /**
     * 按路径前缀交由接口或页面入口点处理。
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param authException 认证异常
     * @throws IOException      响应写出或跳转失败
     * @throws ServletException 跳转失败
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        // 只看路径前缀:查询串不影响分流判定
        if (request.getRequestURI().startsWith(API_PREFIX)) {
            apiEntryPoint.commence(request, response, authException);
        } else {
            pageEntryPoint.commence(request, response, authException);
        }
    }
}
