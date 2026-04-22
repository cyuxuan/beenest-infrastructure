package club.beenest.payment.withdraw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 提现配置
 * 
 * @author System
 * @since 2026-01-27
 */
@Configuration
@ConfigurationProperties(prefix = "withdraw")
@Data
public class WithdrawConfig {
    
    /**
     * 微信提现配置
     */
    private WechatConfig wechat = new WechatConfig();
    
    /**
     * 支付宝提现配置
     */
    private AlipayConfig alipay = new AlipayConfig();
    
    /**
     * 抖音提现配置
     */
    private DouyinConfig douyin = new DouyinConfig();
    
    /**
     * 微信提现配置
     */
    @Data
    public static class WechatConfig {
        /**
         * 是否启用
         */
        private boolean enabled = false;
        
        /**
         * 小程序AppID
         */
        private String appId;
        
        /**
         * 商户号
         */
        private String mchId;
        
        /**
         * APIv3密钥
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
         * 手续费（分）
         */
        private Long feeAmount = 200L;  // 2元
        
        /**
         * 最小提现金额（分）
         */
        private Long minAmount = 10000L;  // 100元
        
        /**
         * 最大提现金额（分）
         */
        private Long maxAmount = 5000000L;  // 50000元
        
        /**
         * 转账场景ID
         * 1000-普通商家转账, 1001-佣金报销, 1002-企业报销等
         */
        private String transferSceneId = "1000";
        
        /**
         * 单日转账总额度（分）
         */
        private Long dailyTotalLimit = 10000000L;  // 10万元
        
        /**
         * 单日单用户转账额度（分）
         */
        private Long dailyUserLimit = 2000000L;  // 2万元
        
        /**
         * 单日提现次数限制
         */
        private Integer dailyCountLimit = 3;
        
        /**
         * 单小时提现次数限制
         */
        private Integer hourlyCountLimit = 1;
    }
    
    /**
     * 支付宝提现配置
     */
    @Data
    public static class AlipayConfig {
        /**
         * 是否启用
         */
        private boolean enabled = false;
        
        /**
         * 应用ID
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
         * 手续费（分）
         */
        private Long feeAmount = 200L;  // 2元
        
        /**
         * 最小提现金额（分）
         */
        private Long minAmount = 10000L;  // 100元
        
        /**
         * 最大提现金额（分）
         */
        private Long maxAmount = 5000000L;  // 50000元

        /**
         * 单日转账总额度（分）
         */
        private Long dailyTotalLimit = 10000000L;  // 10万元

        /**
         * 单日单用户转账额度（分）
         */
        private Long dailyUserLimit = 2000000L;  // 2万元
    }

    /**
     * 抖音提现配置
     */
    @Data
    public static class DouyinConfig {
        /**
         * 是否启用
         */
        private boolean enabled = false;

        /**
         * 应用ID
         */
        private String appId;

        /**
         * 应用密钥
         */
        private String appSecret;

        /**
         * 商户号
         */
        private String merchantId;

        /**
         * 手续费（分）
         */
        private Long feeAmount = 200L;  // 2元

        /**
         * 最小提现金额（分）
         */
        private Long minAmount = 10000L;  // 100元

        /**
         * 最大提现金额（分）
         */
        private Long maxAmount = 5000000L;  // 50000元

        /**
         * 单日转账总额度（分）
         */
        private Long dailyTotalLimit = 10000000L;  // 10万元

        /**
         * 单日单用户转账额度（分）
         */
        private Long dailyUserLimit = 2000000L;  // 2万元
    }
}
