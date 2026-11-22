package club.beenest.iam.admin.scim;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SCIM 端点的异常渲染。
 * <p>SCIM 调用方按 {@code scimType} 决定重试与告警策略,因此错误必须是 RFC 7644 的错误体,
 * 而不是平台统一的 {@code Result}。本通知只作用于 SCIM 包下的控制器,
 * 优先级最高,避免被全局异常处理器的兜底分支抢先渲染。</p>
 */
@RestControllerAdvice(basePackages = "club.beenest.iam.admin.scim")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScimExceptionHandler {

    /**
     * 渲染 SCIM 协议异常。
     *
     * @param exception 协议异常
     * @return 标准 SCIM 错误响应
     */
    @ExceptionHandler(ScimException.class)
    public ResponseEntity<ScimErrorResponse> handleScim(ScimException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(ScimErrorResponse.of(exception.getStatus().value(), exception.getScimType(),
                        exception.getMessage()));
    }

    /**
     * 渲染请求体解析失败。
     *
     * @param exception 解析异常
     * @return 400 与 {@code invalidSyntax}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ScimErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(ScimErrorResponse.of(400, ScimConstants.ERROR_INVALID_SYNTAX, "请求体不是合法的 SCIM 报文"));
    }
}
