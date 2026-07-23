package club.beenest.payment.shared.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用凭证视图对象（密钥脱敏）
 *
 * @author System
 * @since 2026-07-16
 */
@Data
public class AppCredentialVO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 业务系统标识
     */
    private String appId;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 内部 API 密钥（脱敏，如 ****abcd）
     */
    private String appSecret;

    /**
     * MQ 消息签名密钥（脱敏）
     */
    private String mqSecret;

    /**
     * 允许的 IP/CIDR 网段列表
     */
    private String allowedNetworks;

    /**
     * 状态
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
