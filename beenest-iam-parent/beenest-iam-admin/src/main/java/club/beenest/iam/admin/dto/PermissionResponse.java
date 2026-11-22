package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 权限响应。
 *
 * @param id             主键
 * @param realmId        所属域
 * @param appServiceId   所属应用主键;为空表示平台级权限
 * @param permissionCode 权限编码(resource:action)
 * @param permissionName 权限名称
 * @param resource       资源
 * @param action         动作
 * @param description    说明
 * @param createdTime    创建时间
 * @param updatedTime    更新时间
 */
public record PermissionResponse(
        Long id,
        Long realmId,
        Long appServiceId,
        String permissionCode,
        String permissionName,
        String resource,
        String action,
        String description,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
