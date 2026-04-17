package org.apereo.cas.beenest.dto;

import lombok.Data;

import java.util.Map;

/**
 * 统一 Token 响应
 */
@Data
public class TokenResponseDTO {

    private String accessToken;
    private String tgt;
    private String refreshToken;
    private Long expiresIn;
    private String userId;
    private Map<String, ?> attributes;
}
