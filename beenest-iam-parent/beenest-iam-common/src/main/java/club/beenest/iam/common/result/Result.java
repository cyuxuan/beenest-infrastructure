package club.beenest.iam.common.result;

import club.beenest.iam.common.i18n.IamMessages;

import java.io.Serializable;
import java.time.Instant;

/**
 * 统一响应包装类。
 * <p>所有 REST 接口返回 {@code Result<T>},包含 {@code code/message/data/timestamp}。
 * 成功时 code 为 0;失败时 code 为 {@link ErrorCode} 对应业务码。</p>
 *
 * @param <T> 业务数据类型
 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应状态码,0 表示成功 */
    private int code;
    /** 响应消息 */
    private String message;
    /** 业务数据 */
    private T data;
    /** 响应时间戳(epoch 毫秒) */
    private long timestamp;

    public Result() {
        this.timestamp = Instant.now().toEpochMilli();
    }

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toEpochMilli();
    }

    /**
     * 构建成功响应(带数据)。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), IamMessages.message(ErrorCode.SUCCESS), data);
    }

    /**
     * 构建成功响应(无数据)。
     *
     * @param <T> 业务数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 构建失败响应,文案按当前请求语言本地化。
     *
     * @param errorCode 错误码
     * @param <T>       业务数据类型
     * @return 失败响应;语言资源缺失时回落错误码默认文案
     */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), IamMessages.message(errorCode), null);
    }

    /**
     * 构建失败响应(自定义消息)。
     *
     * @param errorCode 错误码
     * @param message   响应消息
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /**
     * 构建失败响应(自定义码与消息)。
     *
     * @param code    业务码
     * @param message 响应消息
     * @param <T>     业务数据类型
     * @return 失败响应
     */
    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 是否成功响应。
     *
     * @return true 表示业务码为成功
     */
    public boolean isSuccess() {
        return this.code == ErrorCode.SUCCESS.getCode();
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
