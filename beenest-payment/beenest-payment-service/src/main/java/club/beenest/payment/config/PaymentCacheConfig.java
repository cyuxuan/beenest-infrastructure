package club.beenest.payment.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * 支付服务 Spring Cache 配置。
 * <p>
 * 配置 CAS Bearer Token 相关缓存，使撤销态和权限版本在多实例间共享：
 * <ul>
 *   <li>{@code casBearerAccessTokenRevocations} — accessToken 撤销态，TTL 7 天</li>
 *   <li>{@code casBearerRefreshTokenRevocations} — refreshToken 撤销态，TTL 365 天</li>
 *   <li>{@code casBearerAuthorityVersions} — 用户权限版本，TTL 7 天</li>
 * </ul>
 *
 * @see org.springframework.data.redis.cache.RedisCacheManager
 */
@Configuration
public class PaymentCacheConfig {

    /**
     * 定制 RedisCacheManager，预创建 CAS Bearer Token 相关缓存。
     *
     * @return RedisCacheManagerBuilderCustomizer
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer paymentRedisCacheCustomizer() {
        return builder -> builder
                .withCacheConfiguration("casBearerAccessTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofDays(7)))
                .withCacheConfiguration("casBearerRefreshTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofDays(365)))
                .withCacheConfiguration("casBearerAuthorityVersions",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofDays(7)));
    }
}
