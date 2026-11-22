package club.beenest.iam.authz.sms;

import club.beenest.iam.authz.config.IamSmsProperties;
import club.beenest.iam.core.spi.sms.SmsChannel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 短信通道路由器。
 * <p>按 {@code iam.sms.channel} 从内置通道中选出已配置的实现;未配置或配置不完整时返回空,
 * 由调用方报"功能未启用"。</p>
 */
@Component
public class SmsChannelRouter {

    private final List<SmsChannel> channels;
    private final IamSmsProperties properties;

    public SmsChannelRouter(List<SmsChannel> channels, IamSmsProperties properties) {
        this.channels = channels;
        this.properties = properties;
    }

    /**
     * 当前生效的通道。
     *
     * @return 通道;未配置或配置不完整时返回空
     */
    public Optional<SmsChannel> resolve() {
        String configured = properties.getChannel();
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        return channels.stream()
                .filter(channel -> channel.channelId().equalsIgnoreCase(configured.trim()))
                // 配置不完整不算可用
                .filter(SmsChannel::isConfigured)
                .findFirst();
    }
}
