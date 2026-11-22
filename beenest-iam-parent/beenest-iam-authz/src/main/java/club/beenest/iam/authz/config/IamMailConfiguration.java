package club.beenest.iam.authz.config;

import club.beenest.iam.authz.mail.SmtpMailChannel;
import club.beenest.iam.core.spi.mail.MailChannel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 邮件通道配置:注册属性与内置通道实现。
 * <p>{@code iam.mail.channel} 选择启用的通道;新增通道时在此注册一个 {@link MailChannel} bean。</p>
 */
@Configuration
@EnableConfigurationProperties(IamMailProperties.class)
public class IamMailConfiguration {

    /**
     * 注册 SMTP 邮件通道。
     *
     * @param properties 邮件通道配置
     * @return SMTP 通道实现
     */
    @Bean
    public MailChannel smtpMailChannel(IamMailProperties properties) {
        return new SmtpMailChannel(properties);
    }
}
