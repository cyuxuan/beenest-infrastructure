package club.beenest.payment.common.exception;

/**
 * 业务异常类
 * 用于表示业务逻辑处理过程中的异常，携带错误码和错误信息
 *
 * <p>与 IllegalArgumentException 的区别：BusinessException 表示业务规则不满足（如余额不足、订单状态异常），
 * IllegalArgumentException 表示输入参数格式不合法。</p>
 *
 * @author System
 * @since 2026-01-26
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
