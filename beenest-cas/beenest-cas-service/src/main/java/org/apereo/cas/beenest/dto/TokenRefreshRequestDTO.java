package org.apereo.cas.beenest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 统一 Token 刷新请求。
 * <p>
 * APP 与小程序共用同一个 refresh 入口，只携带 refreshToken 即可。
 */
@Data
public final class TokenRefreshRequestDTO {

    /** 刷新令牌 */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
