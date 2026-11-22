package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 分配用户角色请求(覆盖式:以本列表为准)。
 *
 * @param roleIds 目标角色主键集合,空集合表示清空用户全部角色
 */
public record AssignRolesRequest(
        @NotNull(message = "角色集合不能为空,清空请传空数组")
        List<Long> roleIds
) {
}
