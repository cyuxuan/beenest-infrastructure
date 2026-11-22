package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 用户会话记录实体(iam_user_session),供审计与会话管理列表使用。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class UserSession extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 会话标识(sid,全局唯一) */
    private String sid;
    /** 用户主键 */
    private Long userId;
    /** 登录方式:PASSWORD/WECHAT/OIDC 等 */
    private String loginType;
    /** 登录来源 IP */
    private String ip;
    /** User-Agent */
    private String userAgent;
    /** 会话状态:ACTIVE 活跃 / LOGGED_OUT 已登出 / EXPIRED 已过期 */
    private String state;
    /** 最近活跃时间 */
    private LocalDateTime lastActiveTime;
    /** 过期时间 */
    private LocalDateTime expiredTime;
}
