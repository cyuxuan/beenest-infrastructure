package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * APP Token 刷新请求 DTO
 */
@Data
public class AppRefreshRequestDTO {

    private String refreshToken;
}
