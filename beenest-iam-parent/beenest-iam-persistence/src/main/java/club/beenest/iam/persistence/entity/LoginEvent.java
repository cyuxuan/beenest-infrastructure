package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 登录/认证事件实体(iam_login_event)。
 * <p>只追加,不提供更新语句;清理历史数据通过逻辑删除(deleted)完成,不做物理删除。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class LoginEvent extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 用户主键;账号不存在时为 null */
    private Long userId;
    /** 尝试的登录名 */
    private String username;
    /** 事件类型:LOGIN_SUCCESS/LOGIN_FAILURE/MFA_SUCCESS/MFA_FAILURE/LOGOUT */
    private String eventType;
    /** 登录方式:PASSWORD/WECHAT/OIDC 等 */
    private String loginType;
    /** 来源 IP */
    private String ip;
    /** User-Agent */
    private String userAgent;
    /** 失败原因 */
    private String failReason;
    /** 发生时间 */
    private LocalDateTime occurredTime;
}
