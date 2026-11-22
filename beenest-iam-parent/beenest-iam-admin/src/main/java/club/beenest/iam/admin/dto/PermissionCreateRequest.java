package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建权限请求。
 * <p>权限按命名空间归属:appServiceId 为空表示平台权限,
 * 非空表示应用权限,同一命名空间内权限码唯一。</p>
 *
 * @param appServiceId   所属应用主键;为空表示平台级权限
 * @param permissionCode 权限编码,格式 resource:action
 * @param permissionName 权限名称
 * @param resource       资源;为空时取编码冒号前的部分
 * @param action         动作;为空时取编码冒号后的部分
 * @param description    说明
 */
public record PermissionCreateRequest(
        Long appServiceId,

        @NotBlank(message = "权限编码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9:_-]{2,150}$",
                message = "权限编码只能包含字母、数字、下划线、中划线、冒号,长度 2-150")
        String permissionCode,

        @NotBlank(message = "权限名称不能为空")
        @Size(max = 200, message = "权限名称长度不能超过 200")
        String permissionName,

        @Size(max = 100, message = "资源长度不能超过 100")
        String resource,

        @Size(max = 50, message = "动作长度不能超过 50")
        String action,

        @Size(max = 500, message = "说明长度不能超过 500")
        String description
) {
}
