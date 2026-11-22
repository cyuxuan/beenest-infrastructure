package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 组角色分配请求(覆盖式)。
 *
 * @param roleIds 目标角色主键集合(空集合表示清空)
 */
public record GroupRolesRequest(
        @NotNull(message = "角色列表不能为空") List<Long> roleIds
) {
}
