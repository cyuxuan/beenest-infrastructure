package org.apereo.cas.beenest.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 浏览器登录成功响应。
 */
@Getter
@Setter
public class WebLoginResponseDTO {

    private String redirectUrl;

    private String ticket;

    private String userId;
}
