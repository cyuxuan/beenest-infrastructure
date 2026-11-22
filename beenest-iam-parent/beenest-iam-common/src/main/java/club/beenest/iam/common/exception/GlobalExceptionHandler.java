package club.beenest.iam.common.exception;

import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 * <p>将业务、参数校验、认证授权及兜底异常统一转换为 {@code Result} 响应;
 * HTTP 状态码表达传输层语义,业务码表达具体业务原因。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常:按错误码语义映射 HTTP 状态码。
     * <p>文案取自错误码时按请求语言解析;调用方自拟的文案原样返回。</p>
     *
     * @param ex 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = mapToHttpStatus(ex.getCode());
        ErrorCode code = ErrorCode.fromCode(ex.getCode());
        if (ex.isMessageLocalizable() && code != null) {
            return ResponseEntity.status(status).body(Result.fail(code));
        }
        return ResponseEntity.status(status).body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 参数校验异常(@Valid 失败):HTTP 400,并聚合字段级错误信息。
     *
     * @param ex 参数校验异常
     * @return 统一失败响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ErrorCode code = ErrorCode.PARAM_INVALID;
        return ResponseEntity.badRequest().body(
                detail.isEmpty() ? Result.<Void>fail(code) : Result.fail(code, detail));
    }

    /**
     * 请求参数类异常:HTTP 400。
     * <p>覆盖缺必填参数、参数类型不匹配、请求体无法解析与路径变量缺失。</p>
     *
     * @param ex 参数类异常
     * @return 统一失败响应
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingPathVariableException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception ex) {
        ErrorCode code = ErrorCode.PARAM_INVALID;
        log.debug("请求参数不合法: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Result.<Void>fail(code));
    }

    /**
     * Spring Security 访问拒绝(已认证无权限):HTTP 403。
     *
     * @param ex 访问拒绝异常
     * @return 统一失败响应
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        // 不回显异常原文:Spring 的拒绝信息可能带出被拒绝的资源与所需权限,
        // 等于把权限模型透露给无权访问它的调用方
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.<Void>fail(code));
    }

    /**
     * Spring Security 认证异常:HTTP 401,并按异常类型细化业务码。
     * <p>认证 Provider 抛出时已按当前语言生成文案;缺失时回落错误码默认文案。</p>
     *
     * @param ex 认证异常
     * @return 统一失败响应
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthentication(AuthenticationException ex) {
        ErrorCode code = mapAuthenticationError(ex);
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(code));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(code, message));
    }

    /**
     * 认证异常按类型映射业务码:口令错误、账号锁定/禁用与未认证使用不同业务码。
     *
     * @param ex 认证异常
     * @return 对应业务码
     */
    private ErrorCode mapAuthenticationError(AuthenticationException ex) {
        if (ex instanceof BadCredentialsException) {
            return ErrorCode.BAD_CREDENTIALS;
        }
        if (ex instanceof LockedException || ex instanceof DisabledException) {
            return ErrorCode.ACCOUNT_LOCKED;
        }
        return ErrorCode.UNAUTHORIZED;
    }

    /**
     * 静态资源/路径不存在:HTTP 404。
     *
     * @param ex 资源不存在异常
     * @return 统一失败响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException ex) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(code));
    }

    /**
     * 兜底异常:HTTP 500,不向前端返回内部细节。
     *
     * @param ex 未预期异常
     * @return 统一失败响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常: {}", ex.getMessage(), ex);
        ErrorCode code = ErrorCode.SYSTEM_ERROR;
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(code, code.getDefaultMessage()));
    }

    /**
     * 将业务错误码映射为 HTTP 状态码。
     * <p>逐码显式映射;新增错误码时由编译器强制补齐分支。</p>
     *
     * @param code 业务错误码
     * @return 对应的 HTTP 状态码;未知错误码按 400 处理
     */
    private HttpStatus mapToHttpStatus(int code) {
        ErrorCode errorCode = ErrorCode.fromCode(code);
        if (errorCode == null) {
            // 未知码按客户端错误处理:服务端并未故障
            return HttpStatus.BAD_REQUEST;
        }
        return switch (errorCode) {
            // 输入类:改请求即可修复,不应触发重新认证
            case PARAM_INVALID, PASSWORD_POLICY_VIOLATION, CAPTCHA_INVALID,
                 RESET_TICKET_INVALID,
                 WEBAUTHN_PAYLOAD_INVALID, WEBAUTHN_CHALLENGE_INVALID -> HttpStatus.BAD_REQUEST;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_FOUND, WEBAUTHN_CREDENTIAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_EXISTS, STATE_CONFLICT, RECOVERY_NOT_AVAILABLE,
                 WEBAUTHN_CREDENTIAL_LIMIT -> HttpStatus.CONFLICT;
            // 待办未完成:已认证但当前不允许访问该资源,与无权限同属 403
            case FORBIDDEN, REQUIRED_ACTION_PENDING -> HttpStatus.FORBIDDEN;
            // 上游依赖故障:第三方身份提供方、短信通道、邮件通道不可用,本平台自身没坏
            case IDP_UNAVAILABLE, SMS_SEND_FAILED, MAIL_SEND_FAILED -> HttpStatus.BAD_GATEWAY;
            // 凭证/状态类:前端据此跳登录或转入重新认证
            case UNAUTHORIZED, BAD_CREDENTIALS, ACCOUNT_LOCKED, ACCOUNT_DISABLED, TOKEN_REVOKED,
                 MFA_REQUIRED, MFA_CODE_INVALID, ACCOUNT_MFA_LOCKED,
                 SMS_CODE_INVALID, SMS_SEND_TOO_FREQUENT,
                 WEBAUTHN_VERIFICATION_FAILED -> HttpStatus.UNAUTHORIZED;
            // 功能未启用:属配置问题,对调用方呈现为服务不可用而非认证失败
            case SMS_CHANNEL_NOT_CONFIGURED, MAIL_CHANNEL_NOT_CONFIGURED,
                 WEBAUTHN_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SUCCESS -> HttpStatus.OK;
        };
    }
}
