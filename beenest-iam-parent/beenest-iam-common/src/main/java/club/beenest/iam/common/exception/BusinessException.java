package club.beenest.iam.common.exception;

import club.beenest.iam.common.result.ErrorCode;

/**
 * 业务异常。
 * <p>业务代码抛出后由 {@link GlobalExceptionHandler} 统一转换为 {@code Result} 响应。
 * 文案可取自错误码默认值(可按请求语言本地化),也可由调用方自拟(原样返回)。</p>
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final int code;

    /** 文案是否取自错误码默认值(可本地化) */
    private final boolean messageLocalizable;

    /**
     * 用错误码的默认文案构造。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.code = errorCode.getCode();
        this.messageLocalizable = true;
    }

    /**
     * 用错误码 + 自拟文案构造。
     *
     * @param errorCode 错误码
     * @param message   面向用户的文案
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.messageLocalizable = false;
    }

    /**
     * 用裸错误码 + 自拟文案构造(用于错误码未收录的场景)。
     *
     * @param code    错误码数值
     * @param message 面向用户的文案
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.messageLocalizable = false;
    }

    /**
     * 错误码数值。
     *
     * @return 错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 文案是否可本地化。
     *
     * @return true 表示文案取自错误码默认值,可替换为当前语言
     */
    public boolean isMessageLocalizable() {
        return messageLocalizable;
    }
}
