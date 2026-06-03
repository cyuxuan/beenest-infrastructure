package org.apereo.cas.beenest.vo;

import lombok.Data;

/**
 * 服务信息响应 VO
 */
@Data
public final class ServiceInfoVO {

    /** 服务自增 ID */
    private long id;

    /** 服务标识 */
    private String serviceId;

    /** 服务名称 */
    private String name;

    /** 服务描述 */
    private String description;

    /** 访问所需角色 */
    private String requiredRole;
}