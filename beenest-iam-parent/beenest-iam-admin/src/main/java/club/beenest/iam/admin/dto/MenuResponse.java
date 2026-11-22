package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 菜单响应。
 *
 * @param id            主键
 * @param realmId       所属域
 * @param appServiceId  所属应用服务
 * @param parentId      父菜单
 * @param menuCode      菜单编码
 * @param menuName      菜单名称
 * @param menuType      菜单类型:MENU / BUTTON
 * @param path          前端路由
 * @param icon          图标
 * @param permissionId  关联权限
 * @param permissionCode 关联权限编码
 * @param sortOrder     排序号
 * @param enabled       是否启用
 * @param createdTime   创建时间
 * @param updatedTime   更新时间
 */
public record MenuResponse(
        Long id,
        Long realmId,
        Long appServiceId,
        Long parentId,
        String menuCode,
        String menuName,
        String menuType,
        String path,
        String icon,
        Long permissionId,
        String permissionCode,
        Integer sortOrder,
        Boolean enabled,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
