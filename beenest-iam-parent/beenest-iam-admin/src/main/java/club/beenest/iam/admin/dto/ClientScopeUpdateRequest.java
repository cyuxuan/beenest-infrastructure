package club.beenest.iam.admin.dto;

/**
 * 客户端作用域更新请求。
 *
 * @param scopeName 作用域显示名
 * @param isDefault 是否默认作用域
 */
public record ClientScopeUpdateRequest(
        String scopeName,
        Boolean isDefault
) {
}
