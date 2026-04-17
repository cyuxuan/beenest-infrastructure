package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * APP 登录请求 DTO
 */
@Data
public class AppLoginRequestDTO {

    private String principal;
    private String password;
    private String otpCode;
    private String loginMethod;
    private String deviceId;
    private Boolean rememberMe;
    private String refreshToken;
}
