package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增应用角色映射请求。
 *
 * @param sourceType     来源类型:GROUP/REALM_ROLE
 * @param sourceId       来源主体主键
 * @param targetRoleCode 目标应用角色码(应用侧自有命名)
 * @param enabled        是否启用,缺省启用
 */
public record AppRoleMappingCreateRequest(
        @NotBlank(message = "来源类型不能为空")
        @Pattern(regexp = "GROUP|REALM_ROLE", message = "来源类型只能是 GROUP 或 REALM_ROLE")
        String sourceType,

        @NotNull(message = "来源主体主键不能为空")
        Long sourceId,

        @NotBlank(message = "目标角色码不能为空")
        @Size(max = 150, message = "目标角色码长度不能超过 150")
        @Pattern(regexp = "^[A-Za-z0-9_.:-]+$", message = "目标角色码只能包含字母、数字、下划线、点、冒号、中划线")
        String targetRoleCode,

        Boolean enabled
) {
}
