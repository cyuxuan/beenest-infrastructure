package org.apereo.cas.beenest.service;

import lombok.extern.slf4j.Slf4j;

/**
 * 认证审计服务（已停用，由 CAS Inspektr 审计框架替代）。
 * <p>
 * 保留此类的空壳实现以确保 Controller 编译通过。
 * 所有审计数据现由 Inspektr（cas-server-support-audit-jdbc）自动记录到 COM_AUDIT_TRAIL 表。
 * 后续 Phase 会重构 Controller，届时可完全移除此类。
 */
@Slf4j
public class AuthAuditService {

    /**
     * 记录认证审计日志（no-op，由 Inspektr 替代）
     */
    public void record(String userId, String principal, String authType,
                       String authResult, String failureReason,
                       String clientIp, String userAgent, String deviceId,
                       String serviceUrl, String handlerName) {
        // 审计已由 CAS Inspektr 框架自动处理
        LOGGER.debug("审计委托给 Inspektr: userId={}, authType={}, authResult={}",
                userId, authType, authResult);
    }
}
