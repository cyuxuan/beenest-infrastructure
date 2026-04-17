package org.apereo.cas.beenest.client.cache;

import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer Token 撤销服务。
 * <p>
 * 负责记录 accessToken 和 refreshToken 的撤销态，并在认证前提供查询。
 * <p>
 * 默认使用本地内存作为兜底；如果业务系统提供了共享 {@link CacheManager}，
 * 会自动将撤销态写入共享缓存，从而让多实例节点都能感知同一条注销事件。
 */
@Slf4j
public class BearerTokenRevocationService {

    private static final String ACCESS_TOKEN_CACHE_NAME = "casBearerAccessTokenRevocations";
    private static final String REFRESH_TOKEN_CACHE_NAME = "casBearerRefreshTokenRevocations";

    private final long accessTokenRevocationTtlMillis;
    private final long refreshTokenRevocationTtlMillis;
    private final Cache accessTokenCache;
    private final Cache refreshTokenCache;
    private final ConcurrentHashMap<String, RevocationRecord> localAccessTokenRevocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RevocationRecord> localRefreshTokenRevocations = new ConcurrentHashMap<>();

    /**
     * 构造 Bearer Token 撤销服务。
     *
     * @param properties  CAS Starter 配置
     * @param cacheManager 共享缓存管理器（可选）
     */
    public BearerTokenRevocationService(CasSecurityProperties properties, CacheManager cacheManager) {
        this.accessTokenRevocationTtlMillis = properties.getTokenAuth().getAccessTokenRevocationTtlSeconds() * 1000;
        this.refreshTokenRevocationTtlMillis = properties.getTokenAuth().getRefreshTokenRevocationTtlSeconds() * 1000;
        this.accessTokenCache = cacheManager != null ? cacheManager.getCache(ACCESS_TOKEN_CACHE_NAME) : null;
        this.refreshTokenCache = cacheManager != null ? cacheManager.getCache(REFRESH_TOKEN_CACHE_NAME) : null;
    }

    /**
     * 撤销 accessToken。
     *
     * @param token accessToken
     */
    public void revokeAccessToken(String token) {
        revokeToken(token, accessTokenRevocationTtlMillis, localAccessTokenRevocations, accessTokenCache);
    }

    /**
     * 撤销 refreshToken。
     *
     * @param token refreshToken
     */
    public void revokeRefreshToken(String token) {
        revokeToken(token, refreshTokenRevocationTtlMillis, localRefreshTokenRevocations, refreshTokenCache);
    }

    /**
     * 判断 accessToken 是否已撤销。
     *
     * @param token accessToken
     * @return true 表示已撤销
     */
    public boolean isAccessTokenRevoked(String token) {
        return isTokenRevoked(token, localAccessTokenRevocations, accessTokenCache);
    }

    /**
     * 判断 refreshToken 是否已撤销。
     *
     * @param token refreshToken
     * @return true 表示已撤销
     */
    public boolean isRefreshTokenRevoked(String token) {
        return isTokenRevoked(token, localRefreshTokenRevocations, refreshTokenCache);
    }

    /**
     * 获取当前本地 accessToken 撤销记录数。
     *
     * @return 记录数
     */
    public int accessTokenRevocationCount() {
        cleanupExpired(localAccessTokenRevocations);
        return localAccessTokenRevocations.size();
    }

    /**
     * 获取当前本地 refreshToken 撤销记录数。
     *
     * @return 记录数
     */
    public int refreshTokenRevocationCount() {
        cleanupExpired(localRefreshTokenRevocations);
        return localRefreshTokenRevocations.size();
    }

    /**
     * 撤销指定 token。
     *
     * @param token       token 字符串
     * @param ttlMillis   撤销态保留时间
     * @param localRegistry 本地记录表
     * @param cache       共享缓存
     */
    private void revokeToken(String token,
                             long ttlMillis,
                             ConcurrentHashMap<String, RevocationRecord> localRegistry,
                             Cache cache) {
        if (!StringUtils.hasText(token)) {
            return;
        }

        long now = System.currentTimeMillis();
        RevocationRecord record = new RevocationRecord(now, now + ttlMillis);
        localRegistry.put(token, record);
        if (cache != null) {
            cache.put(token, record);
        }
    }

    /**
     * 判断 token 是否已撤销。
     *
     * @param token         token 字符串
     * @param localRegistry 本地记录表
     * @param cache         共享缓存
     * @return true 表示已撤销
     */
    private boolean isTokenRevoked(String token,
                                   ConcurrentHashMap<String, RevocationRecord> localRegistry,
                                   Cache cache) {
        if (!StringUtils.hasText(token)) {
            return false;
        }

        RevocationRecord local = localRegistry.get(token);
        if (isActive(local)) {
            return true;
        }
        if (local != null) {
            localRegistry.remove(token, local);
        }

        if (cache == null) {
            return false;
        }

        Cache.ValueWrapper wrapper = cache.get(token);
        if (wrapper == null) {
            return false;
        }

        Object cachedValue = wrapper.get();
        if (!(cachedValue instanceof RevocationRecord record)) {
            cache.evict(token);
            return false;
        }

        if (!isActive(record)) {
            cache.evict(token);
            return false;
        }

        localRegistry.put(token, record);
        return true;
    }

    /**
     * 判断撤销记录是否仍然有效。
     *
     * @param record 撤销记录
     * @return true 表示仍在有效期内
     */
    private boolean isActive(RevocationRecord record) {
        return record != null && System.currentTimeMillis() < record.expiresAtMillis();
    }

    /**
     * 清理本地过期记录。
     *
     * @param registry 记录表
     */
    private void cleanupExpired(ConcurrentHashMap<String, RevocationRecord> registry) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, RevocationRecord> entry : registry.entrySet()) {
            RevocationRecord record = entry.getValue();
            if (record == null || now >= record.expiresAtMillis()) {
                registry.remove(entry.getKey(), record);
            }
        }
    }

    /**
     * 撤销记录。
     *
     * @param revokedAtMillis 撤销时间
     * @param expiresAtMillis 失效时间
     */
    public record RevocationRecord(long revokedAtMillis, long expiresAtMillis) implements Serializable {
    }
}
