package club.beenest.payment.shared.entity;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用凭证实体（API 层）
 * 存储各业务系统的独立认证密钥，实现多租户密钥隔离
 *
 * <p>密钥存储策略：</p>
 * <ul>
 *   <li>app_secret / sign_secret — BCrypt 哈希，仅验证不可逆</li>
 *   <li>mq_secret — AES-256-GCM 加密，支付中台需用明文签发消息</li>
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
     * 内部 API 静态令牌（BCrypt 哈希存储）
     */
    private String appSecret;

    /**
     * 内部 API HMAC 签名密钥（BCrypt 哈希存储）
     */
    private String signSecret;

    /**
     * MQ 消息签名密钥（AES-256-GCM 加密存储）
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
