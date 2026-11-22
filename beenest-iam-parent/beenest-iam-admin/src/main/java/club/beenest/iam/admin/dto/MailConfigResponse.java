package club.beenest.iam.admin.dto;

/**
 * 域级发信配置响应。
 * <p>不含 SMTP 口令,仅以 {@code passwordConfigured} 表示是否已配置;
 * 更新时口令传空串表示保持原值。</p>
 *
 * @param enabled            本域发信开关
 * @param host               SMTP 主机
 * @param port               SMTP 端口
 * @param username           SMTP 用户名
 * @param passwordConfigured 是否已配置 SMTP 口令
 * @param fromAddress        发件邮箱
 * @param fromName           发件人展示名
 * @param starttls           是否启用 STARTTLS
 * @param ssl                是否启用 SSL
 * @param timeoutMillis      连接/读取/写入超时(毫秒)
 * @param globalAvailable    全局配置是否可用(域配置关闭时由它兜底)
 */
public record MailConfigResponse(
        boolean enabled,
        String host,
        int port,
        String username,
        boolean passwordConfigured,
        String fromAddress,
        String fromName,
        boolean starttls,
        boolean ssl,
        int timeoutMillis,
        boolean globalAvailable
) {
}
