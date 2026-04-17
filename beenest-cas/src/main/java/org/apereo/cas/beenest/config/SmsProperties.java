package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信服务配置属性
 */
@Data
@ConfigurationProperties(prefix = "beenest.sms")
public class SmsProperties {

    /** 短信服务商: aliyun / tencent */
    private String provider = "aliyun";
    private String accessKey;
    private String secretKey;
    private String signName;
    private String templateCode;
    /** 验证码有效期（秒） */
    private int otpTtl = 300;
    /** 发送间隔限制（秒） */
    private int sendInterval = 60;
    /** 每日最大发送次数 */
    private int dailyLimit = 10;
}
