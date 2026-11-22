package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 邮件模板覆盖更新请求。
 *
 * @param content 模板原文(HTML,{@code {{变量名}}} 为占位)
 */
public record MailTemplateUpdateRequest(
        @NotBlank(message = "模板内容不能为空")
        @Size(max = 200_000, message = "模板内容不能超过 200000 个字符")
        String content
) {
}
