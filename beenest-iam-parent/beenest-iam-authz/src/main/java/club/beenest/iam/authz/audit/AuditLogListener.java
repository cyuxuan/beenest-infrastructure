package club.beenest.iam.authz.audit;

import club.beenest.iam.common.event.AuditLogEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.AuditLog;
import club.beenest.iam.persistence.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 审计日志落库监听器。
 * <p>审计写入失败不应影响业务操作本身:审计是旁路记录,
 * 为丢一条日志而让业务回滚得不偿失,故此处吞掉异常并告警。</p>
 */
@Component
public class AuditLogListener {

    private static final Logger log = LoggerFactory.getLogger(AuditLogListener.class);

    private final AuditLogMapper auditLogMapper;
    private final RealmProvider realmProvider;

    public AuditLogListener(AuditLogMapper auditLogMapper, RealmProvider realmProvider) {
        this.auditLogMapper = auditLogMapper;
        this.realmProvider = realmProvider;
    }

    /**
     * 消费审计事件并写入 {@code iam_audit_log}。
     * <p>审计日志仅追加,不做更新或删除;写入失败只记日志,不影响业务操作。</p>
     *
     * @param event 审计事件,携带域标识、操作人、动作与目标对象
     */
    @EventListener
    public void onAuditLog(AuditLogEvent event) {
        try {
            // 1. 域主键由域标识回查,事件只携带标识便于跨模块传递
            AuditLog auditLog = new AuditLog();
            auditLog.setRealmId(resolveRealmId(event.realmKey()));
            auditLog.setOperatorId(event.operatorId());
            auditLog.setOperatorName(event.operator());
            auditLog.setAction(event.action());
            auditLog.setDescription(event.description());
            auditLog.setTargetType(event.targetType());
            auditLog.setTargetId(event.targetId());
            auditLog.setIp(event.ip());
            auditLog.setOccurredTime(event.occurredAt());
            // 2. 仅追加,不做任何更新或删除
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.error("审计日志写入失败: action={}, operator={}", event.action(), event.operator(), e);
        }
    }

    /**
     * 按域标识回查域主键。
     *
     * @param realmKey 域标识,可为空
     * @return 域主键;标识为空或域不存在时返回 null,记录为系统级操作
     */
    private Long resolveRealmId(String realmKey) {
        if (realmKey == null || realmKey.isBlank()) {
            return null;
        }
        return realmProvider.findByRealmKey(realmKey).map(RealmModel::id).orElse(null);
    }
}
