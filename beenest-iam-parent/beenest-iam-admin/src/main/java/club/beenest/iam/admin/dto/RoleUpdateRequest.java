package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 更新角色请求。
 * <p>角色编码不可修改;归属(容器类型与所属应用/客户端)只在角色尚未承载任何授权关系时
 * 允许变更,其余情况由服务层拒绝并说明原因。字段为 null 表示不更新该项。</p>
 *
 * @param roleName      角色名称
 * @param isActive      是否启用
 * @param containerType 目标容器类型 REALM / APP / CLIENT(null 表示不修改)
 * @param appServiceId  目标所属应用(APP 角色必填,REALM 角色忽略)
 * @param clientId      目标所属客户端(CLIENT 角色必填,其余容器忽略)
 */
public record RoleUpdateRequest(
        @Size(max = 100, message = "角色名称长度不能超过 100")
        String roleName,

        Boolean isActive,

        @Pattern(regexp = "REALM|APP|CLIENT", message = "容器类型只能是 REALM、APP 或 CLIENT")
        String containerType,

        @Positive(message = "所属应用主键必须为正整数")
        Long appServiceId,

        @Positive(message = "所属客户端主键必须为正整数")
        Long clientId
) {
}
