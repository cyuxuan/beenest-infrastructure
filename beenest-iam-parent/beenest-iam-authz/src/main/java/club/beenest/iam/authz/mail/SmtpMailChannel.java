package club.beenest.iam.authz.mail;

import club.beenest.iam.authz.config.IamMailProperties;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.mail.MailChannel;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SMTP 邮件通道。
 * <p>按配置构建一次发送器并复用,以 UTF-8 HTML 正文发送;未配置或发送失败时抛业务异常。</p>
 */
public class SmtpMailChannel implements MailChannel {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailChannel.class);

    /** 通道标识 */
    public static final String CHANNEL_ID = "smtp";

    private final IamMailProperties properties;
    private final JavaMailSenderImpl mailSender;

    public SmtpMailChannel(IamMailProperties properties) {
        this.properties = properties;
        this.mailSender = buildSender(properties);
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public boolean isConfigured() {
        return properties.isSmtpConfigured();
    }

    @Override
    public void send(String to, String subject, String html, String realmKey) {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.MAIL_CHANNEL_NOT_CONFIGURED);
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=false:正文是单段 HTML,不需要附件
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom(), properties.getFromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.debug("邮件已发送: realm={}, template-recipient={}", realmKey, maskAddress(to));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 失败原因带到投递记录:排查时需区分连接、认证与被收件方拒收
            log.warn("邮件发送失败: realm={}, recipient={}, error={}",
                    realmKey, maskAddress(to), e.getMessage());
            throw new BusinessException(ErrorCode.MAIL_SEND_FAILED, "邮件发送失败: " + e.getMessage());
        }
    }

    /**
     * 按配置构建发送器。
     *
     * @param properties 邮件配置
     * @return 发送器
     */
    private static JavaMailSenderImpl buildSender(IamMailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            sender.setUsername(properties.getUsername());
            sender.setPassword(properties.getPassword());
        }
        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.transport.protocol", "smtp");
        javaMailProperties.put("mail.smtp.auth",
                String.valueOf(properties.getUsername() != null && !properties.getUsername().isBlank()));
        javaMailProperties.put("mail.smtp.starttls.enable", String.valueOf(properties.isStarttls()));
        javaMailProperties.put("mail.smtp.ssl.enable", String.valueOf(properties.isSsl()));
        javaMailProperties.put("mail.smtp.connectiontimeout", String.valueOf(properties.getTimeoutMillis()));
        javaMailProperties.put("mail.smtp.timeout", String.valueOf(properties.getTimeoutMillis()));
        javaMailProperties.put("mail.smtp.writetimeout", String.valueOf(properties.getTimeoutMillis()));
        return sender;
    }

    /**
     * 邮箱脱敏:保留域名便于定位,本地部分只留首字符。
     *
     * @param address 邮箱地址
     * @return 脱敏结果
     */
    private static String maskAddress(String address) {
        if (address == null) {
            return "";
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
