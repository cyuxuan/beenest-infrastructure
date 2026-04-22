package club.beenest.payment.shared.entity;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 支付渠道配置实体
 *
 * @author System
 * @since 2026-02-11
 */
@Data
public class PaymentChannelConfig {
    /**
     * 主键ID
     */
    private Long id;

    /**
     * 渠道名称
     */
    @NotBlank(message = "渠道名称不能为空")
    private String channelName;

    /**
     * 渠道代码 (WECHAT, ALIPAY, DOUYIN)
     */
    @NotBlank(message = "渠道代码不能为空")
    private String channelCode;

    /**
     * AppID
     */
    private String appId;

    /**
     * 商户号
     */
    private String merchantId;

    /**
     * 公钥
     */
    private String publicKey;

    /**
     * 私钥
     */
    private String privateKey;

    /**
     * 回调地址
     */
    private String notifyUrl;

    /**
     * 是否启用
     */
    private Boolean isEnable;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
