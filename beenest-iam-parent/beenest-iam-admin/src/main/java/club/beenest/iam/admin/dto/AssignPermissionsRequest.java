package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色权限分配请求。
 * <p>整体覆盖:提交的集合即角色的最终直接权限,空集合表示清空。</p>
 *
 * @param permissionIds 权限主键集合
 */
public record AssignPermissionsRequest(
        @NotNull(message = "权限集合不能为空,清空请传空数组")
        List<Long> permissionIds
) {
}
