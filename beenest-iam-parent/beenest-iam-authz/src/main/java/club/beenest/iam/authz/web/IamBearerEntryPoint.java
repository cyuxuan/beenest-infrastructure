package club.beenest.iam.authz.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Bearer 令牌被拒时记录原因,再交给框架返回 401。
 *
 * <p>框架的 {@link BearerTokenAuthenticationEntryPoint} 只写 {@code WWW-Authenticate} 头,
 * 响应体为空且不打日志,userinfo 等端点被拒时服务端看不到任何线索。这里补一条 WARN,
 * 同时记录请求是否带了 Authorization 头,用于区分"令牌本身被拒"与"代理把令牌头丢了"。</p>
 */
public class IamBearerEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(IamBearerEntryPoint.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    /**
     * 记录拒绝原因并输出 401。
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param authException 认证异常
     * @throws IOException      响应写入失败
     * @throws ServletException 委派处理失败
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        OAuth2Error error = authException instanceof OAuth2AuthenticationException oauth2
                ? oauth2.getError() : null;
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        log.warn("Bearer 令牌被拒: uri={} remote={} error={} detail={} 携带令牌={}",
                request.getRequestURI(), request.getRemoteAddr(),
                error == null ? authException.getClass().getSimpleName() : error.getErrorCode(),
                error == null ? authException.getMessage() : error.getDescription(),
                authorization != null && authorization.startsWith(BEARER_PREFIX));

        delegate.commence(request, response, authException);
    }
}
