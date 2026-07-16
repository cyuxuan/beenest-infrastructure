package club.beenest.payment.shared.entity;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用凭证实体（API 层）
 *
 * <p>密钥体系（2 secret 模型）：</p>
 * <ul>
 *   <li>app_secret — 令牌认证 + HMAC 签名共用（明文存储，DB 访问控制保护）</li>
 *   <li>mq_secret — MQ 消息签名（明文存储，DB 访问控制保护）</li>
 * </ul>
 *
 * @author System
 * @since 2026-07-16
 */
@Data
public class AppCredential {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 业务系统标识（DRONE/SHOP），与业务表 app_id 对应
     */
    @NotBlank(message = "app_id 不能为空")
    private String appId;

    /**
     * 应用名称
     */
    @NotBlank(message = "应用名称不能为空")
    private String appName;

    /**
     * 内部 API 密钥（令牌认证 + HMAC 签名共用，明文存储）
     */
    private String appSecret;

    /**
     * MQ 消息签名密钥（明文存储）
     */
    private String mqSecret;

    /**
     * 允许的 IP/CIDR 网段列表（逗号分隔，如 10.0.0.0/8,172.16.0.0/12）
     * 为空时不做 IP 限制（所有 IP 可访问）
     */
    private String allowedNetworks;

    /**
     * 状态: ACTIVE(正常), DISABLED(停用)
     */
    private String status;

    /**
     * 描述信息
     */
    private String description;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
