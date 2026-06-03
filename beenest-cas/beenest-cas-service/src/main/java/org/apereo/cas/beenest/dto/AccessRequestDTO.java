package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 服务授权/撤销请求 DTO
 */
@Data
public final class AccessRequestDTO {

    /** 服务 ID */
    private Long serviceId;

    /** 用户 ID */
    private String userId;
}