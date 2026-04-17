package club.beenest.payment.common.exception;

import club.beenest.payment.common.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理 @Valid 校验异常、业务异常、参数异常等，返回标准 Response 格式
 *
 * <p>异常优先级（从具体到通用）：</p>
 * <ol>
 *   <li>MethodArgumentNotValidException - @RequestBody @Valid 校验失败</li>
 *   <li>ConstraintViolationException - @RequestParam / @PathVariable @Validated 校验失败</li>
 *   <li>BindException - 表单绑定校验失败</li>
 *   <li>MethodArgumentTypeMismatchException - 参数类型不匹配</li>
 *   <li>MissingServletRequestParameterException - 缺少必要请求参数</li>
 *   <li>RiskControlException - 风控拦截</li>
 *   <li>BusinessException - 业务异常</li>
 *   <li>IllegalArgumentException - 参数非法</li>
 *   <li>Exception - 兜底异常</li>
 * </ol>
 *
 * @author System
 * @since 2026-04-13
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理 @RequestBody @Valid 校验失败
     * 当请求体中的 JSON 绑定到对象后，JSR-303 校验不通过时触发
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("请求参数校验失败: {}", message);
        return Response.fail(400, message);
    }

    /**
     * 处理 @RequestParam / @PathVariable 上 @Validated 校验失败
     * 当方法参数上的约束注解（@NotBlank, @Min 等）校验不通过时触发
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", message);
        return Response.fail(400, message);
    }

    /**
     * 处理表单绑定校验失败
     * 当表单提交或查询参数绑定到对象后校验不通过时触发
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleBindException(BindException ex) {
        String message = ex.getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定校验失败: {}", message);
        return Response.fail(400, message);
    }

    /**
     * 处理参数类型不匹配
     * 例如期望 Integer 但传入了非数字字符串
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        Class<?> requiredTypeClass = ex.getRequiredType();
        String requiredType = requiredTypeClass != null ? requiredTypeClass.getSimpleName() : "未知";
        String message = String.format("参数 '%s' 类型错误，期望类型: %s", paramName, requiredType);
        log.warn("参数类型不匹配: {}", message);
        return Response.fail(400, message);
    }

    /**
     * 处理缺少必要请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleMissingParam(MissingServletRequestParameterException ex) {
        String message = String.format("缺少必要参数: %s", ex.getParameterName());
        log.warn("缺少请求参数: {}", message);
        return Response.fail(400, message);
    }

    /**
     * 处理风控异常
     */
    @ExceptionHandler(RiskControlException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Response<Void> handleRiskControl(RiskControlException ex) {
        log.warn("风控拦截: {}", ex.getMessage());
        return Response.fail(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<Void> handleBusiness(BusinessException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return Response.fail(ex.getCode(), ex.getMessage());
    }

    /**
     * 处理参数非法异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数非法: {}", ex.getMessage());
        return Response.fail(400, ex.getMessage());
    }

    /**
     * 兜底异常处理
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<Void> handleException(Exception ex) {
        log.error("系统异常: {}", ex.getMessage(), ex);
        return Response.fail(500, "系统繁忙，请稍后重试");
    }

    /**
     * 格式化字段校验错误信息
     */
    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
