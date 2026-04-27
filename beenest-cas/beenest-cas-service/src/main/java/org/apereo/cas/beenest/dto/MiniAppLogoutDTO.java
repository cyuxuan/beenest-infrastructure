package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * 小程序登出请求 DTO
 */
@Data
public final class MiniAppLogoutDTO {

    /** refreshToken */
    private String refreshToken;

    /** accessToken（TGT） */
    private String accessToken;
}
