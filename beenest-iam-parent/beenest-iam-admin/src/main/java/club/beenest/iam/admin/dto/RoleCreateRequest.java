package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建角色请求。
 * <p>REALM 为域级角色(可跨应用使用),APP 为应用专属角色,CLIENT 仅保留兼容存量。
 * 角色编码在"域 + 容器 + 应用/客户端"范围内唯一。</p>
 *
 * @param roleCode      角色编码
 * @param roleName      角色名称
 * @param containerType 容器类型:REALM / APP / CLIENT,缺省 REALM
 * @param clientId      CLIENT 兼容类型所属客户端主键
 * @param appServiceId  APP 类型所属应用主键,APP 容器必填
 */
public record RoleCreateRequest(
        @NotBlank(message = "角色编码不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_-]{2,100}$", message = "角色编码只能包含字母、数字、下划线、中划线,长度 2-100")
        String roleCode,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 100, message = "角色名称长度不能超过 100")
        String roleName,

        String containerType,

        Long clientId,

        Long appServiceId
) {
}
