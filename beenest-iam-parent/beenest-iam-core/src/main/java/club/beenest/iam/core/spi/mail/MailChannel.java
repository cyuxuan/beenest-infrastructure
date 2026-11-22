package club.beenest.iam.core.spi.mail;

import java.util.Map;

/**
 * 邮件通道 SPI。
 * <p>内置 SMTP 实现;更换邮件服务商时实现本接口并配置 {@code iam.mail.channel}。
 * 实现失败时抛出异常,由调用方记录投递失败并提示,通道不得吞掉错误。</p>
 */
public interface MailChannel {

    /**
     * 通道标识。
     *
     * @return 通道标识,与配置项 {@code iam.mail.channel} 对应,如 {@code smtp}
     */
    String channelId();

    /**
     * 发送一封 HTML 邮件。
     *
     * @param to       收件邮箱
     * @param subject  主题(已由调用方渲染)
     * @param html     正文 HTML(已由调用方渲染)
     * @param realmKey 身份域标识,多租户场景用于发件人区分与日志
     * @throws club.beenest.iam.common.exception.BusinessException 发送失败,消息面向运维
     */
    void send(String to, String subject, String html, String realmKey);

    /**
     * 通道配置是否完整到可以发送。
     *
     * @return 配置完整时为 true
     */
    default boolean isConfigured() {
        return true;
    }

    /**
     * 通道附加参数。
     *
     * @return 供模板渲染使用的参数,如发件人展示名;默认无
     */
    default Map<String, String> attributes() {
        return Map.of();
    }
}
