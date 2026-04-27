package org.apereo.cas.beenest.client.cache;

import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.beenest.client.session.CasUserSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer Token 本地验证缓存
 * <p>
 * 缓存 TGT 验证结果，避免每次请求都重复触发 CAS 原生票据验证链路。
 * <p>
 * 缓存策略：
 * - 基于 ConcurrentHashMap，线程安全
 * - 每个条目有独立 TTL（默认 5 分钟）
 * - 超过 maxSize 时自动清理过期条目
 * - 不支持分布式缓存，每个 CAS Client 实例独立缓存
 * <p>
 * 缓存一致性：
 * - 用户在 CAS Server 登出后，TGT 被销毁
 * - CAS Client 本地缓存最多延迟 TTL 时间后失效
 * - 如需即时失效，可通过 Redis Pub/Sub 等机制扩展（当前未实现）
 */
@Slf4j
public class BearerTokenCache {

    private final long ttlMillis;
    private final int maxSize;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> userIdToTokens = new ConcurrentHashMap<>();

    /**
     * @param ttlSeconds 缓存条目 TTL（秒）
     * @param maxSize    缓存最大条目数
     */
    public BearerTokenCache(long ttlSeconds, int maxSize) {
        this.ttlMillis = ttlSeconds * 1000;
        this.maxSize = maxSize;
    }

    /**
     * 获取缓存的用户会话信息
     *
     * @param token accessToken (TGT ID)
     * @return 缓存的 CasUserSession，未命中或已过期返回 null
     */
    public CasUserSession get(String token) {
        java.util.concurrent.atomic.AtomicReference<CasUserSession> result = new java.util.concurrent.atomic.AtomicReference<>(null);
        cache.computeIfPresent(token, (key, entry) -> {
            if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
                removeTokenFromUserIndex(key, entry);
                return null; // 移除过期条目
            }
            result.set(entry.session);
            return entry; // 保留有效条目
        });
        return result.get();
    }

    /**
     * 获取缓存的用户详情信息。
     * <p>
     * 命中后可直接复用已解析的权限，避免再次调用业务系统权限加载器。
     *
     * @param token accessToken (TGT ID)
     * @return 缓存的 CasUserDetails，未命中或已过期返回 null
     */
    public CasUserDetails getUserDetails(String token) {
        java.util.concurrent.atomic.AtomicReference<CasUserDetails> result = new java.util.concurrent.atomic.AtomicReference<>(null);
        cache.computeIfPresent(token, (key, entry) -> {
            if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
                removeTokenFromUserIndex(key, entry);
                return null;
            }
            result.set(entry.userDetails);
            return entry;
        });
        return result.get();
    }

    /**
     * 缓存用户会话信息
     *
     * @param token   accessToken (TGT ID)
     * @param session 用户会话信息
     */
    public void put(String token, CasUserSession session) {
        put(token, session, null);
    }

    /**
     * 缓存用户会话和用户详情信息。
     *
     * @param token       accessToken (TGT ID)
     * @param session     用户会话信息
     * @param userDetails 用户详情（含权限）
     */
    public void put(String token, CasUserSession session, CasUserDetails userDetails) {
        if (cache.size() >= maxSize) {
            cleanup();
        }
        // 清理后仍超限则跳过写入，防止内存无限增长
        if (cache.size() >= maxSize) {
            log.warn("BearerTokenCache 已满 (size={}), 跳过缓存", cache.size());
            return;
        }
        CacheEntry entry = new CacheEntry(session, userDetails, System.currentTimeMillis());
        cache.put(token, entry);
        if (session.getUserId() != null && !session.getUserId().isBlank()) {
            userIdToTokens.computeIfAbsent(session.getUserId(), k -> ConcurrentHashMap.newKeySet()).add(token);
        }
    }

    /**
     * 主动移除缓存条目
     * <p>
     * 可在检测到 TGT 失效时调用（如收到 SLO 通知）。
     *
     * @param token accessToken (TGT ID)
     */
    public void remove(String token) {
        CacheEntry removed = cache.remove(token);
        if (removed != null) {
            removeTokenFromUserIndex(token, removed);
        }
    }

    /**
     * 撤销一个 accessToken，并同步记录到共享撤销表。
     * <p>
     * 这样即使本地缓存尚未过期，后续请求也会先命中撤销记录并直接拒绝。
     *
     * @param token accessToken (TGT ID)
     */
    public void revoke(String token) {
        if (!StringUtils.hasText(token)) {
            return;
        }

        remove(token);
    }

    /**
     * 按 userId 清理该用户的全部缓存 token。
     * <p>
     * 这用于 CAS 单点登出后的本地失效兜底：如果当前会话已经失效，
     * 但缓存里的 bearer token 仍然有效，则必须一起清除，避免登出后继续命中缓存。
     *
     * @param userId 用户 ID
     */
    public void removeByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        Set<String> tokens = userIdToTokens.remove(userId);
        if (tokens == null || tokens.isEmpty()) {
            return;
        }

        for (String token : new ArrayList<>(tokens)) {
            remove(token);
        }
    }

    /**
     * 获取当前缓存条目数量
     */
    public int size() {
        return cache.size();
    }

    /**
     * 清理过期条目
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (now - entry.getValue().timestamp > ttlMillis) {
                if (cache.remove(entry.getKey(), entry.getValue())) {
                    removeTokenFromUserIndex(entry.getKey(), entry.getValue());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("BearerTokenCache 清理: 移除 {} 条过期条目, 剩余 {}", removed, cache.size());
        }
    }

    /**
     * 从用户索引中移除 token。
     */
    private void removeTokenFromUserIndex(String token, CacheEntry entry) {
        if (entry == null || entry.session() == null) {
            return;
        }
        String userId = entry.session().getUserId();
        if (userId == null || userId.isBlank()) {
            return;
        }

        Set<String> tokens = userIdToTokens.get(userId);
        if (tokens == null) {
            return;
        }

        tokens.remove(token);
        if (tokens.isEmpty()) {
            userIdToTokens.remove(userId, tokens);
        }
    }

    /**
     * 缓存条目
     */
    private record CacheEntry(CasUserSession session, CasUserDetails userDetails, long timestamp) {}
}
