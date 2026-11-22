package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 新增应用管理员请求。
 *
 * @param subjectType 主体类型:USER/GROUP
 * @param subjectId   主体主键
 */
public record AppAdminCreateRequest(
        @NotBlank(message = "主体类型不能为空")
        @Pattern(regexp = "USER|GROUP", message = "主体类型只能是 USER 或 GROUP")
        String subjectType,

        @NotNull(message = "主体主键不能为空")
        Long subjectId
) {
}
