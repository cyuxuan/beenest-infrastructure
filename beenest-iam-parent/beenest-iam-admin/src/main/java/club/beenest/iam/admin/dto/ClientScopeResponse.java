package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 客户端作用域响应。
 *
 * @param id          主键
 * @param scopeKey    作用域键
 * @param scopeName   作用域显示名
 * @param isDefault   是否默认作用域
 * @param createdTime 创建时间
 */
public record ClientScopeResponse(
        Long id,
        String scopeKey,
        String scopeName,
        Boolean isDefault,
        LocalDateTime createdTime
) {
}
