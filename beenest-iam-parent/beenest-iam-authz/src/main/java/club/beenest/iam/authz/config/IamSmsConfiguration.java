package club.beenest.iam.authz.config;

import club.beenest.iam.authz.sms.AliyunSmsChannel;
import club.beenest.iam.core.spi.sms.SmsChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信通道配置:注册属性与内置通道实现。
 * <p>{@code iam.sms.channel} 选择启用的通道;新增通道时在此注册一个 {@link SmsChannel} bean。</p>
 */
@Configuration
@EnableConfigurationProperties(IamSmsProperties.class)
public class IamSmsConfiguration {

    /**
     * 注册阿里云短信通道。
     *
     * @param properties   短信通道配置
     * @param objectMapper JSON 映射器
     * @return 阿里云短信通道
     */
    @Bean
    public SmsChannel aliyunSmsChannel(IamSmsProperties properties, ObjectMapper objectMapper) {
        return new AliyunSmsChannel(properties, objectMapper);
    }
}
