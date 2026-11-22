package club.beenest.iam.authz.web;

import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * REST 接口的无权限处理器。
 * <p>已认证但缺少所需权限码时返回 HTTP 403 与统一响应体,不重定向登录页。</p>
 */
public class IamApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public IamApiAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 写出 403 响应。
     *
     * @param request               当前请求
     * @param response              当前响应
     * @param accessDeniedException 无权限异常
     * @throws IOException 响应写出失败
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ResultJsonWriter.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                Result.fail(ErrorCode.FORBIDDEN));
    }
}
