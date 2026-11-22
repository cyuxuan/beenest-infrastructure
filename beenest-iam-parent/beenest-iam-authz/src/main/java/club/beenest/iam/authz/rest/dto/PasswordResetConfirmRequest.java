package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用票据设置新口令请求。
 *
 * @param ticket      重置票据(来自邮件链接或页面参数)
 * @param newPassword 新口令明文
 */
public record PasswordResetConfirmRequest(

        @NotBlank(message = "重置票据不能为空")
        String ticket,

        @NotBlank(message = "新口令不能为空")
        @Size(min = 8, max = 128, message = "口令长度需在 8 到 128 个字符之间")
        String newPassword
) {
}
