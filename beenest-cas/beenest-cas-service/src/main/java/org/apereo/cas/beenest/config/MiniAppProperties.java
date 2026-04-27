package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 小程序 SDK 配置属性
 * <p>
 * 集中管理微信、抖音、支付宝小程序的 appid/secret 等配置。
 */
@Data
@ConfigurationProperties(prefix = "beenest.miniapp")
public class MiniAppProperties {

    private WechatConfig wechat;
    private DouyinConfig douyin;
    private AlipayConfig alipay;

    @Data
    public static class WechatConfig {
        private String appid;
        private String secret;
    }

    @Data
    public static class DouyinConfig {
        private String appid;
        private String secret;
    }

    @Data
    public static class AlipayConfig {
        private String appid;
        private String privateKey;
        /** 支付宝公钥（用于验签，非应用公钥） */
        private String publicKey;
        /** 内容加密密钥（用于手机号解密，在支付宝开放平台配置） */
        private String aesKey;
    }
}
