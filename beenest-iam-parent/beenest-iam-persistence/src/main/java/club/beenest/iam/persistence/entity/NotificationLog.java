package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 通知投递记录(iam_notification_log)。
 * <p>只追加,不提供更新语句;status 与 errorMessage 在发送时写入。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class NotificationLog extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 投递渠道:EMAIL/SMS */
    private String channel;
    /** 收件地址(邮箱或手机号) */
    private String recipient;
    /** 模板标识,对应 themes/{theme}/email/{code}.html 或短信模板 */
    private String templateCode;
    /** 主题(邮件用) */
    private String subject;
    /** 投递结果:SENT/FAILED */
    private String status;
    /** 失败原因;成功时为空 */
    private String errorMessage;
    /** 关联用户主键;无关联时为空 */
    private Long userId;
}
