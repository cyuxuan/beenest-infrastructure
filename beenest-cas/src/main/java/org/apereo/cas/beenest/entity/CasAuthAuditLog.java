package org.apereo.cas.beenest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 认证审计日志实体
 * <p>
 * 记录所有认证事件（成功/失败/锁定），用于安全追溯和风控分析。
 */
@Data
public class CasAuthAuditLog {

    private Long id;
    private String userId;
    private String principal;
    private String authType;
    private String authResult;
    private String failureReason;
    private String clientIp;
    private String userAgent;
    private String deviceId;
    private String serviceUrl;
    private String handlerName;
    private LocalDateTime createdTime;
}
