package org.apereo.cas.beenest.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用访问授权 DTO
 */
@Data
public class AppAccessGrantDTO {

    /** 用户 ID（单个授权时使用） */
    private String userId;

    /** 用户 ID 列表（批量授权时使用） */
    private List<String> userIds;

    /** 访问级别：BASIC / ADMIN */
    @NotBlank(message = "访问级别不能为空")
    private String accessLevel = "BASIC";

    /** 授权人 */
    private String grantedBy;

    /** 授权原因 */
    private String reason;

    /** 过期时间（null 表示永久授权） */
    private LocalDateTime expireTime;
}
