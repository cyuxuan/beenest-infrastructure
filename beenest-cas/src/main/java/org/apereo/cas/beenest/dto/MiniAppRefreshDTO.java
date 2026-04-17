package org.apereo.cas.beenest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Token 刷新请求 DTO
 */
@Data
public class MiniAppRefreshDTO {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
