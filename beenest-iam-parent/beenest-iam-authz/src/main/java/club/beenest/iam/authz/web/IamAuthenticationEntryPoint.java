package club.beenest.iam.authz.web;

import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * 协议端点未认证入口。
 * <p>授权、用户信息等端点要求已登录,未登录时跳转登录页,透传当前域、回跳地址与目标客户端。</p>
 */
public class IamAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * 跳转登录页并携带回跳参数。
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param authException 认证异常
     * @throws IOException 跳转失败
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        // 1. 回跳地址:原始请求的路径与查询串原样拼接。
        //    不解析查询串,解析后参数顺序与转义可能变化
        String returnTo = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            returnTo = returnTo + "?" + query;
        }
        // 2. 跳转登录页,携带域标识与回跳地址。
        //    编码放在最后统一做,回跳地址中的 & ? = 作为参数值必须转义
        UriComponentsBuilder target = UriComponentsBuilder.fromPath("/login")
                .queryParam("return_to", returnTo);
        RealmContext context = RealmContextHolder.get();
        if (context != null) {
            target.queryParam("realm", context.realmKey());
        }
        // 3. 目标客户端:登录页据此按客户端白名单收窄展示。
        //    取自本入口收到的请求参数,不从 return_to 中反解;仅透传,不在此校验是否存在
        String clientId = request.getParameter("client_id");
        if (clientId != null && !clientId.isBlank()) {
            target.queryParam("client_id", clientId);
        }
        response.sendRedirect(target.build().encode().toUriString());
    }
}
