package org.apereo.cas.beenest.common.exception;

import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 用于业务逻辑校验失败时抛出，由全局异常处理器统一捕获返回错误响应。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 500;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }
}
