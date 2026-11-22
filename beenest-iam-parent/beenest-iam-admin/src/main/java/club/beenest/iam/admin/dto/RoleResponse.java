package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 角色响应。
 *
 * @param id            主键
 * @param realmId       所属域
 * @param roleCode      角色编码
 * @param roleName      角色名称
 * @param containerType 容器类型:REALM / APP / CLIENT
 * @param clientId      CLIENT 兼容类型所属客户端主键
 * @param appServiceId  APP 角色所属应用主键
 * @param isSystem      是否系统内置(内置角色不可删除)
 * @param isActive      是否启用
 * @param createdTime   创建时间
 * @param updatedTime   更新时间
 */
public record RoleResponse(
        Long id,
        Long realmId,
        String roleCode,
        String roleName,
        String containerType,
        Long clientId,
        Long appServiceId,
        Boolean isSystem,
        Boolean isActive,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
