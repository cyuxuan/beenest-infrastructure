package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建菜单请求。
 *
 * @param appServiceId 所属应用服务主键(必填)
 * @param parentId     父菜单主键,为空表示根菜单
 * @param menuCode     菜单编码(应用内唯一)
 * @param menuName     菜单名称
 * @param menuType     菜单类型:MENU / BUTTON
 * @param path         前端路由
 * @param icon         图标
 * @param permissionId 关联权限,挂载后随菜单授予派生该权限
 * @param sortOrder    排序号
 * @param enabled      是否启用
 */
public record MenuCreateRequest(
        @jakarta.validation.constraints.NotNull(message = "必须指定所属应用")
        Long appServiceId,

        Long parentId,

        @NotBlank(message = "菜单编码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_.:-]{2,100}$", message = "菜单编码只能包含字母、数字、下划线、点、冒号、中划线")
        String menuCode,

        @NotBlank(message = "菜单名称不能为空")
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
