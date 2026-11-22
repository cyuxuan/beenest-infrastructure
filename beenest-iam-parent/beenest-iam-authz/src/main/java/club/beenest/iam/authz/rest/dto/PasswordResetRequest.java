package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请找回口令请求。
 *
 * @param account 邮箱或手机号
 */
public record PasswordResetRequest(

        @NotBlank(message = "请输入邮箱或手机号")
        @Size(max = 200, message = "邮箱或手机号过长")
        String account
) {
}
