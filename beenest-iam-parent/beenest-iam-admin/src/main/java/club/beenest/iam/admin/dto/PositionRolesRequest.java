package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 岗位角色分配请求(覆盖式)。
 *
 * @param roleIds 目标角色主键集合(空集合表示清空岗位角色)
 */
public record PositionRolesRequest(
        @NotNull(message = "角色列表不能为空") List<Long> roleIds
) {
}
