package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 用户级权限特批请求。
 *
 * @param grantType   GRANT 授予 / REVOKE 撤销
 * @param reason      操作原因(必填)
 * @param expiredTime 过期时间,为空表示长期有效
 */
public record PermissionOverrideRequest(
        @NotBlank(message = "授权类型不能为空")
        @Pattern(regexp = "GRANT|REVOKE", message = "授权类型只能是 GRANT 或 REVOKE")
        String grantType,
        @NotBlank(message = "操作原因不能为空")
        @Size(max = 500, message = "操作原因不能超过 500 字")
        String reason,
        LocalDateTime expiredTime
) {
}
