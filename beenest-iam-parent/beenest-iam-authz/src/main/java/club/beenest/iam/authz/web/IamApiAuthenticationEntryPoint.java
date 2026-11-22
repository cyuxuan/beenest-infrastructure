package club.beenest.iam.authz.web;

import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * REST 接口的未认证入口点。
 * <p>{@code /api/v1/**} 面向前后端分离的前端与第三方调用方,未登录时返回 HTTP 401 与统一响应体,不跳转登录页。</p>
 */
public class IamApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public IamApiAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出 401 响应。
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param authException 认证异常
     * @throws IOException 响应写出失败
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ResultJsonWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED.value(),
                Result.fail(ErrorCode.UNAUTHORIZED));
    }
}
