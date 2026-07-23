package club.beenest.payment.shared.dto;

import lombok.Data;

/**
 * 更新应用凭证请求 DTO
 *
 * @author System
 * @since 2026-07-16
 */
@Data
public class UpdateAppCredentialDTO {

    /**
     * 业务系统标识（不可修改，用于定位记录）
     */
    private String appId;

    /**
     * 应用名称（null 不更新）
     */
    private String appName;

    /**
     * 允许的 IP/CIDR 网段列表（null 不更新，逗号分隔）
     * 为空字符串时不做 IP 限制
     */
    private String allowedNetworks;

    /**
     * 描述信息（null 不更新）
     */
    private String description;
}
