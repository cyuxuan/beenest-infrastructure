package club.beenest.iam.common.event;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志事件。
 * <p>由 {@code AuditLogAspect} 在 {@code @LogAudit} 方法成功返回后发布,
 * 由 authz 模块的监听器消费并写入 {@code iam_audit_log}。</p>
 *
 * @param action      操作类型
 * @param description 操作描述
 * @param operatorId  操作人主键(系统操作时为 null)
 * @param operator    操作人用户名
 * @param realmKey    所属身份域
 * @param targetType  目标对象类型(如 USER/ROLE/CLIENT)
 * @param targetId    目标对象主键
 * @param ip          来源 IP
 * @param occurredAt  发生时间
 */
public record AuditLogEvent(
        String action,
        String description,
        Long operatorId,
        String operator,
        String realmKey,
        String targetType,
        Long targetId,
        String ip,
        LocalDateTime occurredAt
) implements Serializable {
}
