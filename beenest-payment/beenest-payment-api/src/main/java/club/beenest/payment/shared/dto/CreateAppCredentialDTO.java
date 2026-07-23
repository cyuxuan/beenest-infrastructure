package club.beenest.payment.shared.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建应用凭证请求 DTO
 *
 * @author System
 * @since 2026-07-16
 */
@Data
public class CreateAppCredentialDTO {

    /**
     * 业务系统标识（如 DRONE、SHOP）
     */
    @NotBlank(message = "app_id 不能为空")
    private String appId;

    /**
     * 应用名称
     */
    @NotBlank(message = "应用名称不能为空")
    private String appName;

    /**
     * 允许的 IP/CIDR 网段列表（逗号分隔，如 10.0.0.0/8,172.16.0.0/12）
     * 为空时不做 IP 限制
     */
    private String allowedNetworks;

    /**
     * 描述信息
     */
    private String description;
}
