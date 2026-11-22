package club.beenest.iam.persistence.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体(iam_audit_log),只允许插入,不允许修改与删除。
 */
@Data
public class AuditLog {

    /** 主键 */
    private Long id;
    /** 所属域(可为空,如系统级操作) */
    private Long realmId;
    /** 操作人主键 */
    private Long operatorId;
    /** 操作人用户名 */
    private String operatorName;
    /** 操作动作,如 user:create */
    private String action;
    /** 操作说明 */
    private String description;
    /** 目标类型 */
    private String targetType;
    /** 目标主键 */
    private Long targetId;
    /** 来源 IP */
    private String ip;
    /** 发生时间 */
    private LocalDateTime occurredTime;
}
