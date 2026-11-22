package club.beenest.iam.admin.scim;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SCIM 错误响应(RFC 7644 {@code urn:ietf:params:scim:api:messages:2.0:Error})。
 * <p>SCIM 调用方按 {@code scimType} 决定重试策略,因此业务错误必须落到标准错误体,
 * 不能复用平台统一的 {@code Result} 结构。</p>
 *
 * @param schemas  固定为 Error schema
 * @param status   HTTP 状态码(字符串形式,协议要求)
 * @param scimType 错误类型,可为 null
 * @param detail   面向调用方的说明
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScimErrorResponse(List<String> schemas, String status, String scimType, String detail) {

    /**
     * 构造错误响应。
     *
     * @param status   状态码
     * @param scimType 错误类型,可为 null
     * @param detail   说明
     * @return 错误响应
     */
    public static ScimErrorResponse of(int status, String scimType, String detail) {
        return new ScimErrorResponse(List.of(ScimConstants.SCHEMA_ERROR),
                String.valueOf(status), scimType, detail);
    }
}
