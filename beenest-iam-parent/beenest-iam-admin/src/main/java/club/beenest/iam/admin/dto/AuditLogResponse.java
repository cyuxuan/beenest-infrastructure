package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 审计日志响应。
 *
 * @param id           主键
 * @param operatorId   操作人主键
 * @param operatorName 操作人登录名
 * @param action       动作编码
 * @param description  描述
 * @param targetType   目标类型
 * @param targetId     目标主键
 * @param ip           来源 IP
 * @param occurredTime 发生时间
 */
public record AuditLogResponse(
        Long id,
        Long operatorId,
        String operatorName,
        String action,
        String description,
        String targetType,
        Long targetId,
        String ip,
        LocalDateTime occurredTime
) {
}
