package club.beenest.payment.paymentorder.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 支付配置类
 * 统一管理各支付平台的配置信息
 *
 * <p>支持平台：</p>
 * <ul>
 *   <li>微信支付 - 小程序支付</li>
 *   <li>支付宝 - 小程序支付</li>
 *   <li>抖音支付 - 小程序支付</li>
 * </ul>
 *
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>敏感信息加密存储</li>
 *   <li>配置文件分离</li>
 *   <li>环境隔离</li>
 * </ul>
 *
 * @author System
 * @since 2026-01-26
 */
@Configuration
@ConfigurationProperties(prefix = "payment")
@Data
public class PaymentConfig {

    /**
     * 微信支付配置
     */
    private WechatConfig wechat = new WechatConfig();

    /**
     * 支付宝配置
     */
    private AlipayConfig alipay = new AlipayConfig();

    /**
     * 抖音支付配置
     */
    private DouyinConfig douyin = new DouyinConfig();

    /**
     * 支付分配置（信用免押）
     */
    private PayScoreConfig payscore = new PayScoreConfig();

    /**
     * 通用配置
     */
    private CommonConfig common = new CommonConfig();

    /**
     * 微信支付配置
     */
    @Data
    public static class WechatConfig {
        /**
         * 小程序AppID
         */
        private String appId;

        /**
         * 商户号
         */
        private String mchId;

        /**
         * API密钥
         */
        private String apiKey;

        /**
         * API v3密钥
         */
        private String apiV3Key;

        /**
         * 商户证书序列号
         */
        private String serialNo;

        /**
         * 商户私钥路径
         */
        private String privateKeyPath;

        /**
         * 微信支付公钥路径 (可选，若使用公钥模式则必填)
         */
        private String publicKeyPath;

        /**
         * 微信支付公钥ID (可选，若使用公钥模式则必填)
         */
        private String publicKeyId;

        /**
         * 支付回调地址
         */
        private String notifyUrl;

        /**
         * 退款回调地址
         */
        private String refundNotifyUrl;

        /**
         * 是否启用
         */
        private boolean enabled = true;
    }

    /**
     * 支付宝配置
     */
    @Data
    public static class AlipayConfig {
        /**
         * 小程序AppID
         */
        private String appId;

        /**
         * 应用私钥
         */
        private String privateKey;

        /**
         * 支付宝公钥
         */
        private String alipayPublicKey;

        /**
         * 支付网关地址
         */
        private String gatewayUrl = "https://openapi.alipay.com/gateway.do";

        /**
         * 签名类型
         */
        private String signType = "RSA2";

        /**
         * 字符编码
         */
        private String charset = "UTF-8";

        /**
         * 数据格式
         */
        private String format = "json";

        /**
         * 支付回调地址
         */
        private String notifyUrl;

        /**
         * 是否启用
         */
        private boolean enabled = true;
    }

    /**
     * 抖音支付配置
     */
    @Data
    public static class DouyinConfig {
        /**
         * 小程序AppID
         */
        private String appId;

        /**
         * 小程序Secret
         */
        private String appSecret;

        /**
         * 商户号
         */
        private String mchId;

        /**
         * 支付密钥
         */
        private String paymentKey;

        /**
         * 支付回调地址
         */
        private String notifyUrl;

        /**
         * 是否启用
         */
        private boolean enabled = true;
    }

    /**
     * 支付分配置（信用免押 - 微信支付分 / 支付宝芝麻信用）
     */
    @Data
    public static class PayScoreConfig {
        /**
         * 微信支付分配置
         */
        private WechatPayScoreConfig wechat = new WechatPayScoreConfig();

        /**
         * 支付宝芝麻信用配置
         */
        private AlipayZhimaConfig alipay = new AlipayZhimaConfig();

        /**
         * 支付分通用配置
         */
        private PayScoreCommonConfig common = new PayScoreCommonConfig();

        /**
         * 微信支付分配置
         */
        @Data
        public static class WechatPayScoreConfig {
            /**
             * 是否启用微信支付分
             */
            private boolean enabled = false;

            /**
             * 微信支付分服务ID（由微信支付运营分配）
             */
            private String serviceId;

            /**
             * 小程序AppID（复用微信支付配置）
             */
            private String appId;

            /**
             * 商户号（复用微信支付配置）
             */
            private String mchId;

            /**
             * 授权回调地址
             */
            private String notifyUrl;

            /**
             * 完结回调地址（默认复用授权回调地址）
             */
            private String completeNotifyUrl;
        }

        /**
         * 支付宝芝麻信用配置
         */
        @Data
        public static class AlipayZhimaConfig {
            /**
             * 是否启用支付宝芝麻信用免押
             */
            private boolean enabled = false;

            /**
             * 芝麻信用产品码（由支付宝分配）
             */
            private String productCode;

            /**
             * 小程序AppID（复用支付宝支付配置）
             */
            private String appId;

            /**
             * 授权回调地址
             */
            private String notifyUrl;
        }

        /**
         * 支付分通用配置
         */
        @Data
        public static class PayScoreCommonConfig {
            /**
             * 授权超时时间（分钟）
             */
            private int authExpireMinutes = 30;

            /**
             * 默认保证金金额（分）
             */
            private long depositDefaultAmount = 100000;

            /**
             * 信用免押最低信用分阈值（低于此分数不享受免押）
             */
            private int minCreditScore = 600;
        }
    }

    /**
     * 通用配置
     */
    @Data
    public static class CommonConfig {
        /**
         * 订单过期时间（分钟）
         */
        private int orderExpireMinutes = 30;

        /**
         * 支付超时时间（秒）
         */
        private int paymentTimeoutSeconds = 300;

        /**
         * 回调重试次数
         */
        private int callbackRetryTimes = 3;

        /**
         * 是否启用沙箱环境
         */
        private boolean sandbox = false;

        /**
         * 是否启用退款状态自动同步扫描
         */
        private boolean refundSyncEnabled = true;

        /**
         * 退款状态自动同步固定间隔（毫秒）
         */
        private long refundSyncDelayMs = 30000L;

        /**
         * 退款状态自动同步首次延迟（毫秒）
         */
        private long refundSyncInitialDelayMs = 15000L;

        /**
         * 单次自动同步的退款单数量
         */
        private int refundSyncBatchSize = 20;
    }
}
