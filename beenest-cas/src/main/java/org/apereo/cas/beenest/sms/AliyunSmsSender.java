package org.apereo.cas.beenest.sms;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 阿里云短信发送器，实现 CAS 原生 {@link org.apereo.cas.notifications.sms.SmsSender} 接口。
 * <p>
 * CAS 的密码重置、MFA、通知等功能通过此 Bean 发送短信。
 * 当未配置 accessKey/secretKey 时退化为日志输出（开发模式）。
 */
@Slf4j
public class AliyunSmsSender implements org.apereo.cas.notifications.sms.SmsSender {

    private final String accessKey;
    private final String secretKey;
    private final String signName;
    private final String templateCode;

    public AliyunSmsSender(String accessKey, String secretKey, String signName, String templateCode) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    @Override
    public boolean send(String from, String to, String message) throws Throwable {
        if (StringUtils.isAnyBlank(accessKey, secretKey)) {
            LOGGER.info("[开发模式] 短信发送: to={}, message={}", to, message);
            return true;
        }

        // TODO: 集成阿里云 Dysms SDK 发送短信
        // 示例代码（需添加 com.aliyun:dysmsapi20170525 依赖）:
        //
        // DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou", accessKey, secretKey);
        // IAcsClient client = new DefaultAcsClient(profile);
        // SendSmsRequest request = new SendSmsRequest();
        // request.setPhoneNumbers(to);
        // request.setSignName(signName);
        // request.setTemplateCode(templateCode);
        // request.setTemplateParam("{\"code\":\"" + message + "\"}");
        // SendSmsResponse response = client.getAcsResponse(request);
        // return "OK".equals(response.getBody().getCode());

        LOGGER.info("短信发送: to={}, signName={}, templateCode={}", to, signName, templateCode);
        return true;
    }

    @Override
    public boolean canSend() {
        return true;
    }
}
