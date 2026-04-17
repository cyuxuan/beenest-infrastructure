package club.beenest.payment.object.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 支付渠道配置实体
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
    private String channelName;

    /**
     * 渠道代码 (WECHAT, ALIPAY, DOUYIN)
     */
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
