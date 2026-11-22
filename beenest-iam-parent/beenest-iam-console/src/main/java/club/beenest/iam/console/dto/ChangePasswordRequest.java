package club.beenest.iam.console.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改本人登录口令请求。
 * <p>必须携带原口令,用于校验改密人身份。</p>
 *
 * @param oldPassword 原口令明文
 * @param newPassword 新口令明文,长度 8-64
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原口令不能为空")
        String oldPassword,

        @NotBlank(message = "新口令不能为空")
        @Size(min = 8, max = 64, message = "口令长度需在 8-64 之间")
        String newPassword
) {
}
