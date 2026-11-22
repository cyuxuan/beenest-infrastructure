package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 域级发信配置更新请求。
 *
 * @param enabled       本域发信开关;关闭时一律使用全局配置
 * @param host          SMTP 主机
 * @param port          SMTP 端口(1-65535)
 * @param username      SMTP 用户名;留空表示不认证
 * @param password      SMTP 口令;留空表示不修改已存口令(密文不回显)
 * @param fromAddress   发件邮箱
 * @param fromName      发件人展示名
 * @param starttls      是否启用 STARTTLS
 * @param ssl           是否启用 SSL(465 端口直连 TLS 时用)
 * @param timeoutMillis 连接/读取/写入超时(毫秒,1000-60000)
 */
public record MailConfigUpdateRequest(
        @NotNull(message = "发信开关不能为空") Boolean enabled,
        @NotBlank(message = "SMTP 主机不能为空") String host,
        @NotNull(message = "SMTP 端口不能为空") @Min(1) @Max(65535) Integer port,
        String username,
        String password,
        @NotBlank(message = "发件邮箱不能为空") @Email(message = "发件邮箱格式不正确") String fromAddress,
        String fromName,
        @NotNull(message = "STARTTLS 开关不能为空") Boolean starttls,
        @NotNull(message = "SSL 开关不能为空") Boolean ssl,
        @NotNull(message = "超时时间不能为空") @Min(1000) @Max(60000) Integer timeoutMillis
) {
}
