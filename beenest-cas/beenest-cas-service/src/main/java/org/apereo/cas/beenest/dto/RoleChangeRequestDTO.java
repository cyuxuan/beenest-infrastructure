package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 用户角色变更请求 DTO（添加/移除角色共用）
 */
@Data
public final class RoleChangeRequestDTO {

    /** 用户 ID */
    private String userId;

    /** 角色名称 */
    private String role;
}