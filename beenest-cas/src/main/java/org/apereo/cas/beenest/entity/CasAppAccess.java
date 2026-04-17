package org.apereo.cas.beenest.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用访问控制实体
 * <p>
 * 对应 cas_app_access 表，控制用户对特定应用的访问权限。
 * CAS 签发 ST 前校验此表。支持临时授权（过期时间）。
 */
@Data
public class CasAppAccess {

    private Long id;
    private String userId;
    private Long serviceId;
    /** 访问级别：BASIC / ADMIN */
    private String accessLevel;
    /** 授权人 */
    private String grantedBy;
    /** 授权原因 */
    private String reason;
    /** 过期时间（null 表示永久） */
    private LocalDateTime expireTime;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
}
