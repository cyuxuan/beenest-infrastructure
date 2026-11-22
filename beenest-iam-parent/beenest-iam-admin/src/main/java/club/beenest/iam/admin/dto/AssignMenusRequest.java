package club.beenest.iam.admin.dto;

import java.util.List;

/**
 * 角色菜单授予请求(整体覆盖式)。
 *
 * @param menuIds 目标菜单主键集合;空集合表示清空菜单授予
 */
public record AssignMenusRequest(
        List<Long> menuIds
) {
}
