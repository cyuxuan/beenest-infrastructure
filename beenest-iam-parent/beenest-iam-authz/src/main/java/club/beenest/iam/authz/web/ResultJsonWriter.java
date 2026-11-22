package club.beenest.iam.authz.web;

import club.beenest.iam.common.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * REST 接口错误响应的统一写出器。
 * <p>过滤器层的异常(未认证/无权限)不经过 MVC 异常处理器,需在此手动写出
 * {@code Result} 结构,保证接口错误响应与业务响应的报文形态一致。</p>
 */
final class ResultJsonWriter {

    private ResultJsonWriter() {
    }

    /**
     * 写出 JSON 响应。
     *
     * @param response     响应对象
     * @param objectMapper Jackson 2 映射器(与 Redis 序列化复用同一 Bean)
     * @param httpStatus   HTTP 状态码
     * @param body         响应体
     */
    static void write(HttpServletResponse response, ObjectMapper objectMapper,
                      int httpStatus, Result<?> body) throws IOException {
        // 1. 覆盖全局响应头,避免被默认字符集/类型干扰
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // 2. 序列化统一响应结构
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
