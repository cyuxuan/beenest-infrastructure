package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 测试邮件发送请求。
 *
 * @param to 收件邮箱
 */
public record MailConfigTestRequest(
        @NotBlank(message = "收件邮箱不能为空") @Email(message = "收件邮箱格式不正确") String to
) {
}
