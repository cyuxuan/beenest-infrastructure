package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置口令请求。
 *
 * @param newPassword 新口令明文
 */
public record ResetPasswordRequest(
        @NotBlank(message = "新口令不能为空")
        @Size(min = 8, max = 64, message = "口令长度需在 8-64 之间")
        String newPassword
) {
}
