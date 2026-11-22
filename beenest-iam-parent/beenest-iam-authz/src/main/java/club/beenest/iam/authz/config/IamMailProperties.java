package club.beenest.iam.authz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 邮件通道配置({@code iam.mail.*})。
 * <p>{@code channel} 决定启用的实现:缺省或未知值表示未配置,邮件能力整体不可用,
 * 依赖邮件的能力(找回口令、邮箱验证)不展示入口且接口明确报错。</p>
 */
@ConfigurationProperties(prefix = "iam.mail")
public class IamMailProperties {

    /** 通道标识;内置 {@code smtp}。留空表示未配置 */
    private String channel;

    /** SMTP 主机 */
    private String host;

    /** SMTP 端口 */
    private int port = 587;

    /** SMTP 用户名 */
    private String username;

    /** SMTP 口令(只写不读,不提供任何查询接口) */
    private String password;

    /** 发件邮箱 */
    private String from;

    /** 发件人展示名 */
    private String fromName = "统一身份认证";

    /** 是否启用 STARTTLS */
    private boolean starttls = true;

    /** 是否启用 SSL(465 端口直连 TLS 时用) */
    private boolean ssl;

    /** 连接/读取/写入超时(毫秒) */
    private int timeoutMillis = 10_000;

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public boolean isStarttls() {
        return starttls;
    }

    public void setStarttls(boolean starttls) {
        this.starttls = starttls;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * SMTP 通道是否具备最小可用配置。
     *
     * @return 主机与发件人均已配置时为 true
     */
    public boolean isSmtpConfigured() {
        return host != null && !host.isBlank()
                && from != null && !from.isBlank();
    }
}
