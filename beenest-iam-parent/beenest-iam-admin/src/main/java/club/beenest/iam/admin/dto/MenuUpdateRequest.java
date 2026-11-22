package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新菜单请求(仅更新显式给出的字段)。
 *
 * @param menuName     菜单名称
 * @param menuType     菜单类型:MENU / BUTTON
 * @param path         前端路由
 * @param icon         图标
 * @param permissionId 关联权限主键,传空表示不修改
 * @param sortOrder    排序号
 * @param enabled      是否启用
 */
public record MenuUpdateRequest(
        @Size(max = 100, message = "菜单名称长度不能超过 100")
        String menuName,

        @Pattern(regexp = "MENU|BUTTON", message = "菜单类型只能是 MENU 或 BUTTON")
        String menuType,

        @Size(max = 300, message = "路由长度不能超过 300")
        String path,

        @Size(max = 100, message = "图标长度不能超过 100")
        String icon,

        Long permissionId,

        Integer sortOrder,

        Boolean enabled
) {
}
