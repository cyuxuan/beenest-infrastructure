package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 用户级权限特批响应。
 *
 * @param id             特批记录主键
 * @param userId         用户主键
 * @param permissionId   权限主键
 * @param permissionCode 权限编码
 * @param permissionName 权限名称
 * @param appServiceId   所属应用主键
 * @param appName        所属应用名称
 * @param grantType      特批类型:GRANT / REVOKE
 * @param reason         操作原因
 * @param expiredTime    过期时间,为空表示长期有效
 * @param active         当前是否生效(未过期);过期记录保留用于追溯
 * @param grantedBy      操作人用户主键
 * @param createdTime    创建时间
 * @param updatedTime    更新时间
 */
public record PermissionOverrideResponse(
        Long id,
        Long userId,
        Long permissionId,
        String permissionCode,
        String permissionName,
        Long appServiceId,
        String appName,
        String grantType,
        String reason,
        LocalDateTime expiredTime,
        boolean active,
        Long grantedBy,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
