package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新权限请求。
 * <p>权限码、资源、动作与所属应用不可修改;字段为 null 表示不更新该项。</p>
 *
 * @param permissionName 权限名称
 * @param description    说明
 */
public record PermissionUpdateRequest(
        @Size(max = 200, message = "权限名称长度不能超过 200")
        String permissionName,

        @Size(max = 500, message = "说明长度不能超过 500")
        String description
) {
}
