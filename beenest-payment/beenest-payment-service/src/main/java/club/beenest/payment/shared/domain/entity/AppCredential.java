package club.beenest.payment.shared.domain.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用凭证实体（Service Domain 层）
 * 存储各业务系统的独立认证密钥，实现多租户密钥隔离
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
     * 业务系统标识（DRONE/SHOP）
     */
    private String appId;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 内部 API 静态令牌（BCrypt 哈希）
     */
    private String appSecret;

    /**
     * 内部 API HMAC 签名密钥（BCrypt 哈希）
     */
    private String signSecret;

    /**
     * MQ 消息签名密钥（AES-256-GCM 加密）
     */
    private String mqSecret;

    /**
     * 允许的 IP/CIDR 网段列表（逗号分隔），为空时不做 IP 限制
     */
    private String allowedNetworks;

    /**
     * 状态: ACTIVE / DISABLED
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
