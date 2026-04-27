package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * APP 登出请求 DTO。
 */
@Data
public final class AppLogoutRequestDTO {

    private String refreshToken;
    private String accessToken;
}
