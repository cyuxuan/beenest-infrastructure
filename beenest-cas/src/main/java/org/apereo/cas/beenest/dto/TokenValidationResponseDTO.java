package org.apereo.cas.beenest.dto;

import lombok.Data;

import java.util.Map;

/**
 * Token 验证响应 DTO
 */
@Data
public class TokenValidationResponseDTO {

    private String userId;
    private Map<String, ?> attributes;
}
