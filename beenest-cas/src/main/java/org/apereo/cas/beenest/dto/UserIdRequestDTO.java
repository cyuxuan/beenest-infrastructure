package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 仅包含用户 ID 的请求体
 */
@Data
public class UserIdRequestDTO {

    private String userId;
}
