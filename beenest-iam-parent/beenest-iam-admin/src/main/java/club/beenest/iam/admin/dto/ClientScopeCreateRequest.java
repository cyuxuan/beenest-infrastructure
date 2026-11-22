package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 客户端作用域创建请求。
 *
 * @param scopeKey  作用域键(域内唯一,如 email)
 * @param scopeName 作用域显示名
 * @param isDefault 是否默认作用域
 */
public record ClientScopeCreateRequest(
        @NotBlank(message = "作用域键不能为空") String scopeKey,
        @NotBlank(message = "作用域名称不能为空") String scopeName,
        Boolean isDefault
) {
}
