package club.beenest.iam.authz.notification;

import club.beenest.iam.authz.mail.MailChannelRouter;
import club.beenest.iam.core.spi.mail.MailChannel;
import club.beenest.iam.persistence.manager.IamNotificationLogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 通知服务。
 * <p>渲染邮件模板、调用邮件通道并写入投递记录;投递失败只记录不抛出,
 * 调用方拿到的响应不因投递结果而异。</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 模板:口令重置 */
    public static final String TEMPLATE_PASSWORD_RESET = "password-reset";
    /** 模板:邮箱验证 */
    public static final String TEMPLATE_EMAIL_VERIFICATION = "email-verification";
    /** 模板:安全提醒(凭证类变更) */
    public static final String TEMPLATE_SECURITY_ALERT = "security-alert";
    /** 模板:账号已开通 */
    public static final String TEMPLATE_ACCOUNT_OPENED = "account-opened";

    private final MailChannelRouter mailChannelRouter;
    private final MailTemplateRenderer templateRenderer;
    private final IamNotificationLogManager notificationLogManager;

    public NotificationService(MailChannelRouter mailChannelRouter,
                               MailTemplateRenderer templateRenderer,
                               IamNotificationLogManager notificationLogManager) {
        this.mailChannelRouter = mailChannelRouter;
        this.templateRenderer = templateRenderer;
        this.notificationLogManager = notificationLogManager;
    }

    /**
     * 指定身份域的邮件能力是否可用(域配置或全局配置任一可用即可)。
     *
     * @param realmId 身份域主键
     * @return 存在可用邮件通道时为 true
     */
    public boolean mailAvailable(Long realmId) {
        return mailChannelRouter.available(realmId);
    }

    /**
     * 邮件能力当前是否可用(全局配置口径)。
     *
     * @return 是否存在已配置的邮件通道
     */
    public boolean mailAvailable() {
        return mailChannelRouter.available();
    }

    /**
     * 发送一封模板邮件。
     *
     * @param realmId      身份域主键(投递记录归档维度)
     * @param realmKey     身份域标识(通道侧分账/日志)
     * @param userId       关联用户主键;可为空
     * @param to           收件邮箱
     * @param templateCode 模板标识
     * @param subject      主题
     * @param variables    模板变量
     * @return 是否投递成功
     */
    public boolean sendMail(Long realmId, String realmKey, Long userId, String to,
                            String templateCode, String subject, Map<String, Object> variables) {
        MailChannel channel = mailChannelRouter.resolve(realmId).orElse(null);
        if (channel == null) {
            log.warn("邮件通道未配置,跳过发送: realm={}, template={}", realmKey, templateCode);
            return false;
        }
        String html = templateRenderer.render(realmId, templateCode, variables);
        if (html == null) {
            log.warn("邮件模板不存在,跳过发送: template={}", templateCode);
            notificationLogManager.record(realmId, IamNotificationLogManager.CHANNEL_EMAIL, to,
                    templateCode, subject, IamNotificationLogManager.STATUS_FAILED,
                    "模板不存在: " + templateCode, userId);
            return false;
        }
        try {
            channel.send(to, subject, html, realmKey);
            notificationLogManager.record(realmId, IamNotificationLogManager.CHANNEL_EMAIL, to,
                    templateCode, subject, IamNotificationLogManager.STATUS_SENT, null, userId);
            return true;
        } catch (RuntimeException e) {
            // 不向上抛:调用方拿到的响应必须与"账号不存在"完全一致
            notificationLogManager.record(realmId, IamNotificationLogManager.CHANNEL_EMAIL, to,
                    templateCode, subject, IamNotificationLogManager.STATUS_FAILED, e.getMessage(), userId);
            log.warn("邮件投递失败: realm={}, template={}, error={}", realmKey, templateCode, e.getMessage());
            return false;
        }
    }
}
