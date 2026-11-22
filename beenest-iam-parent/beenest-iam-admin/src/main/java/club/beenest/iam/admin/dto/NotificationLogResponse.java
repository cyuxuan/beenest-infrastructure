package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 通知投递记录响应。
 *
 * @param id           主键
 * @param channel      投递渠道:EMAIL / SMS
 * @param recipient    收件地址(邮箱或手机号)
 * @param templateCode 模板编码
 * @param subject      邮件主题;短信或未设置时为空
 * @param status       投递结果:SENT / FAILED
 * @param errorMessage 失败原因;成功时为空
 * @param userId       关联用户主键;无关联时为空
 * @param createdTime  投递时间
 */
public record NotificationLogResponse(
        Long id,
        String channel,
        String recipient,
        String templateCode,
        String subject,
        String status,
        String errorMessage,
        Long userId,
        LocalDateTime createdTime
) {
}
