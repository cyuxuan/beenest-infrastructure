package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token 有效期配置
 * <p>
 * 控制小程序/APP 登录场景下 accessToken 和 refreshToken 的有效期。
 * 通过 {@code beenest.token.*} 前缀配置，支持环境变量覆盖。
 * <p>
 * 默认策略：accessToken 短期（7天），refreshToken 长期（1年）。
 * 客户端应在 accessToken 过期前通过 refreshToken 静默刷新。
 *
 * <pre>
 * beenest:
 *   token:
 *     access-token-ttl-seconds: 604800       # 7 天
 *     refresh-token-ttl-seconds: 31536000     # 365 天
 *     refresh-token-rotation: true            # 每次刷新轮换 refreshToken
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "beenest.token")
public class TokenTtlProperties {

    /**
     * accessToken (TGT) 有效期（秒）
     * <p>
     * 用于返回给客户端的 expiresIn 字段值。
     * TGT 实际过期由 Apereo CAS 的 remember-me TTL 控制（应大于等于此值）。
     * 默认 7 天（604800 秒）。
     */
    private long accessTokenTtlSeconds = 7L * 24 * 3600;

    /**
     * refreshToken 有效期（秒）
     * <p>
     * refreshToken 存储在 Redis 中，过期后用户需要重新登录。
     * 默认 365 天（31536000 秒），可通过环境变量 {@code REFRESH_TOKEN_TTL} 覆盖。
     */
    private long refreshTokenTtlSeconds = 365L * 24 * 3600;

    /**
     * 是否启用 refreshToken 轮换
     * <p>
     * 开启后，每次 refresh 操作会：
     * 1. 原子删除旧 refreshToken（Redis getAndDelete，防重放攻击）
     * 2. 生成全新的 refreshToken 返回给客户端
     * 关闭后，refresh 不删除旧 refreshToken，新旧并存直到过期。
     * 默认开启（推荐）。
     */
    private boolean refreshTokenRotation = true;
}
