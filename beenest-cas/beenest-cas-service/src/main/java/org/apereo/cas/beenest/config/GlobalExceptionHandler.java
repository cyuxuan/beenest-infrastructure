package org.apereo.cas.beenest.config;

import org.apereo.cas.beenest.common.exception.BizException;
import org.apereo.cas.beenest.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * <p>
 * 捕获 BusinessException 并转为统一响应格式。
 * 注意：CAS 内部的认证异常由 Apereo CAS 框架自行处理，此处仅处理自定义 REST API 的异常。
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.apereo.cas.beenest.controller")
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBusinessException(BizException e) {
        LOGGER.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null || !status.isError() && e.getCode() >= 400) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthenticationException(AuthenticationException e) {
        LOGGER.warn("认证异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(401, "未登录或登录已过期"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDeniedException(AccessDeniedException e) {
        LOGGER.warn("权限不足: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(403, "权限不足"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e) {
        LOGGER.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(500, "系统内部错误"));
    }
}
