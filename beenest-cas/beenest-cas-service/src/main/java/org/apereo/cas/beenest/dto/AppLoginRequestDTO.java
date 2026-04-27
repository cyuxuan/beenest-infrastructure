package org.apereo.cas.beenest.dto;

import lombok.Data;

/**
 * APP 登录请求 DTO。
 * <p>
 * 支持用户名密码与短信验证码两种模式。
 */
@Data
public final class AppLoginRequestDTO {

    private String principal;
    private String password;
    private String otpCode;
    private String loginMethod;
    private String deviceId;
    private Boolean rememberMe;
    private String refreshToken;
}
