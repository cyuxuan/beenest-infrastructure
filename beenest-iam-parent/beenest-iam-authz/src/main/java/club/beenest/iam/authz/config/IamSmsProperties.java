package club.beenest.iam.authz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短信通道配置({@code iam.sms.*})。
 * <p>{@code channel} 决定启用的通道:缺省或未知值表示未配置,短信验证码整体不可用,
 * 域策略中的开关不生效,发送接口明确报错而非静默失败。</p>
 */
@ConfigurationProperties(prefix = "iam.sms")
public class IamSmsProperties {

    /** 通道标识;内置 {@code aliyun}。留空表示未配置 */
    private String channel;

    /** 阿里云 AccessKey ID */
    private String accessKeyId;

    /** 阿里云 AccessKey Secret(只写不读,不提供任何查询接口) */
    private String accessKeySecret;

    /** 短信签名(阿里云控制台审核通过的签名名) */
    private String signName;

    /** 短信模板 CODE;模板必须含变量 {@code ${code}} */
    private String templateCode;

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        this.accessKeyId = accessKeyId;
    }

    public String getAccessKeySecret() {
        return accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        this.accessKeySecret = accessKeySecret;
    }

    public String getSignName() {
        return signName;
    }

    public void setSignName(String signName) {
        this.signName = signName;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    /**
     * 阿里云通道是否具备最小可用配置。
     *
     * @return AccessKey、签名与模板均已配置时为 true
     */
    public boolean isAliyunConfigured() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank()
                && signName != null && !signName.isBlank()
                && templateCode != null && !templateCode.isBlank();
    }
}
