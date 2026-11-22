package club.beenest.iam.admin.scim;

import org.springframework.http.HttpStatus;

/**
 * SCIM 协议异常。
 * <p>携带 HTTP 状态与 SCIM 的 {@code scimType},由 {@link ScimExceptionHandler} 渲染为
 * RFC 7644 规定的错误响应体。业务异常(如登录名重复)在服务层转成本异常,
 * 这样 SCIM 调用方拿到的是标准错误结构而不是平台的统一响应体。</p>
 */
public class ScimException extends RuntimeException {

    private final HttpStatus status;
    private final String scimType;

    /**
     * 构造异常。
     *
     * @param status   HTTP 状态
     * @param scimType SCIM 错误类型,可为 null
     * @param detail   面向调用方的说明
     */
    public ScimException(HttpStatus status, String scimType, String detail) {
        super(detail);
        this.status = status;
        this.scimType = scimType;
    }

    /**
     * 资源不存在。
     *
     * @param detail 说明
     * @return 404 异常
     */
    public static ScimException notFound(String detail) {
        return new ScimException(HttpStatus.NOT_FOUND, null, detail);
    }

    /**
     * 请求体或取值非法。
     *
     * @param scimType SCIM 错误类型
     * @param detail   说明
     * @return 400 异常
     */
    public static ScimException badRequest(String scimType, String detail) {
        return new ScimException(HttpStatus.BAD_REQUEST, scimType, detail);
    }

    /**
     * 唯一性冲突。
     *
     * @param detail 说明
     * @return 409 异常
     */
    public static ScimException conflict(String detail) {
        return new ScimException(HttpStatus.CONFLICT, ScimConstants.ERROR_UNIQUENESS, detail);
    }

    /**
     * 读取 HTTP 状态。
     *
     * @return HTTP 状态
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * 读取 SCIM 错误类型。
     *
     * @return 错误类型;无对应类型时返回 null
     */
    public String getScimType() {
        return scimType;
    }
}
