package club.beenest.iam.authz.mail;

import club.beenest.iam.authz.config.IamMailProperties;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.spi.mail.MailChannel;
import club.beenest.iam.persistence.entity.RealmMailConfig;
import club.beenest.iam.persistence.manager.IamRealmMailConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件通道路由器。
 * <p>优先使用身份域自己的发信配置,域未启用或配置不完整时回落到 {@code iam.mail.*} 全局配置;
 * 两者都没有,返回空,由调用方报"功能未启用"。</p>
 */
@Component
public class MailChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(MailChannelRouter.class);

    /** 域级口令的机密加密用途标识(管理端写入与发信读取共用) */
    public static final String PASSWORD_PURPOSE = "iam-realm-mail-password";
    /** 端口未配置时的兜底值(STARTTLS 提交口) */
    private static final int DEFAULT_PORT = 587;
    /** 超时未配置时的兜底值(毫秒) */
    private static final int DEFAULT_TIMEOUT_MILLIS = 10_000;

    private final List<MailChannel> channels;
    private final IamMailProperties properties;
    private final IamRealmMailConfigStore realmMailConfigStore;
    private final IamSecretCrypto secretCrypto;
    /** 域级通道缓存:发送器构建包含连接参数解析,按配置更新时间判断是否需要重建 */
    private final Map<Long, CachedChannel> realmChannels = new ConcurrentHashMap<>();

    public MailChannelRouter(List<MailChannel> channels, IamMailProperties properties,
                             IamRealmMailConfigStore realmMailConfigStore,
                             IamSecretCrypto secretCrypto) {
        this.channels = channels;
        this.properties = properties;
        this.realmMailConfigStore = realmMailConfigStore;
        this.secretCrypto = secretCrypto;
    }

    /**
     * 指定身份域生效的通道:域配置优先,缺省回落全局配置。
     *
     * @param realmId 身份域主键;为空时只看全局配置
     * @return 通道;域与全局都不可用时返回空
     */
    public Optional<MailChannel> resolve(Long realmId) {
        if (realmId != null) {
            MailChannel scoped = resolveRealm(realmId);
            if (scoped != null) {
                return Optional.of(scoped);
            }
        }
        return resolveGlobal();
    }

    /**
     * 全局生效的通道。
     *
     * @return 通道;全局未配置或配置不完整时返回空
     */
    public Optional<MailChannel> resolve() {
        return resolveGlobal();
    }

    /**
     * 指定身份域是否具备邮件能力(界面据此决定是否展示"忘记口令"入口)。
     *
     * @param realmId 身份域主键
     * @return 域配置或全局配置可用时为 true
     */
    public boolean available(Long realmId) {
        return resolve(realmId).isPresent();
    }

    /**
     * 全局是否具备邮件能力。
     *
     * @return 全局配置可用时为 true
     */
    public boolean available() {
        return resolveGlobal().isPresent();
    }

    /**
     * 丢弃域级通道缓存。
     * <p>配置更新后调用;通道缓存本身按更新时间比对,这里只是让旧发送器尽快释放。</p>
     *
     * @param realmId 身份域主键
     */
    public void evict(Long realmId) {
        if (realmId != null) {
            realmChannels.remove(realmId);
        }
    }

    /**
     * 逐域解析通道:未启用、配置不完整或读取失败时返回 null(交由全局兜底)。
     *
     * @param realmId 身份域主键
     * @return 域级通道;不可用时返回 null
     */
    private MailChannel resolveRealm(Long realmId) {
        RealmMailConfig config;
        try {
            config = realmMailConfigStore.getConfig(realmId);
        } catch (RuntimeException e) {
            // 配置读不出来不能连累发信:回落到全局配置
            log.warn("读取域发信配置失败,回落全局配置: realmId={}, error={}", realmId, e.getMessage());
            return null;
        }
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return null;
        }
        IamMailProperties scoped = toProperties(config);
        if (!scoped.isSmtpConfigured()) {
            log.warn("域发信配置不完整(主机或发件人为空),回落全局配置: realmId={}", realmId);
            return null;
        }
        CachedChannel cached = realmChannels.get(realmId);
        if (cached != null && Objects.equals(cached.updatedTime(), config.getUpdatedTime())) {
            return cached.channel();
        }
        MailChannel channel = new SmtpMailChannel(scoped);
        realmChannels.put(realmId, new CachedChannel(config.getUpdatedTime(), channel));
        return channel;
    }

    /**
     * 全局通道:按 {@code iam.mail.channel} 从内置实现中选出已配置的一个。
     *
     * @return 通道;未配置时返回空
     */
    private Optional<MailChannel> resolveGlobal() {
        String configured = properties.getChannel();
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        return channels.stream()
                .filter(channel -> channel.channelId().equalsIgnoreCase(configured.trim()))
                .filter(MailChannel::isConfigured)
                .findFirst();
    }

    /**
     * 域配置转通道参数,口令在此解密(明文只存在于内存中的发送器里)。
     *
     * @param config 域发信配置
     * @return 通道参数
     */
    private IamMailProperties toProperties(RealmMailConfig config) {
        IamMailProperties scoped = new IamMailProperties();
        scoped.setChannel(SmtpMailChannel.CHANNEL_ID);
        scoped.setHost(config.getHost());
        scoped.setPort(config.getPort() == null ? DEFAULT_PORT : config.getPort());
        String username = config.getUsername() == null || config.getUsername().isBlank()
                ? null : config.getUsername();
        scoped.setUsername(username);
        scoped.setPassword(username == null ? null
                : secretCrypto.decrypt(PASSWORD_PURPOSE, String.valueOf(config.getRealmId()),
                config.getPassword()));
        scoped.setFrom(config.getFromAddress());
        scoped.setFromName(config.getFromName());
        scoped.setStarttls(!Boolean.FALSE.equals(config.getStarttls()));
        scoped.setSsl(Boolean.TRUE.equals(config.getSsl()));
        scoped.setTimeoutMillis(config.getTimeoutMillis() == null
                ? DEFAULT_TIMEOUT_MILLIS : config.getTimeoutMillis());
        return scoped;
    }

    /**
     * 域级通道缓存项。
     *
     * @param updatedTime 配置更新时间
     * @param channel     已构建的通道
     */
    private record CachedChannel(LocalDateTime updatedTime, MailChannel channel) {
    }
}
