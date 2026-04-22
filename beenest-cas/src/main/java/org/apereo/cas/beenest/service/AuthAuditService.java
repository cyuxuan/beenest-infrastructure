package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.entity.CasAuthAuditLog;
import org.apereo.cas.beenest.mapper.CasAuthAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;


/**
 * 认证审计服务
 * <p>
 * 异步记录所有认证事件到 cas_auth_audit_log 表，
 * 不影响认证主流程性能。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthAuditService {

    private final CasAuthAuditLogMapper auditLogMapper;

    /**
     * 异步记录认证审计日志
     *
     * @param userId        用户ID（失败时可能为空）
     * @param principal     登录标识
     * @param authType      认证方式
     * @param authResult    认证结果 (SUCCESS/FAILED/LOCKED/DISABLED)
     * @param failureReason 失败原因
     * @param clientIp      客户端 IP
     * @param userAgent     User-Agent
     * @param deviceId      设备标识
     * @param serviceUrl    请求的 service URL
     * @param handlerName   处理器名称
     */
    @Async
    public void record(String userId, String principal, String authType,
                       String authResult, String failureReason,
                       String clientIp, String userAgent, String deviceId,
                       String serviceUrl, String handlerName) {
        try {
            CasAuthAuditLog auditLog = new CasAuthAuditLog();
            auditLog.setUserId(userId);
            auditLog.setPrincipal(principal);
            auditLog.setAuthType(authType);
            auditLog.setAuthResult(authResult);
            auditLog.setFailureReason(failureReason);
            auditLog.setClientIp(clientIp);
            auditLog.setUserAgent(userAgent);
            auditLog.setDeviceId(deviceId);
            auditLog.setServiceUrl(serviceUrl);
            auditLog.setHandlerName(handlerName);
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            // 审计日志写入失败不应影响认证流程
            LOGGER.error("审计日志写入失败: userId={}, authType={}, authResult={}",
                    userId, authType, authResult, e);
        }
    }
}
