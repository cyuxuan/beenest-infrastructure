package org.apereo.cas.beenest.client.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer 权限版本服务。
 * <p>
 * 用于记录每个用户当前已知的权限版本号，帮助资源服务在本地命中认证缓存时，
 * 仍能感知跨节点传播过来的权限变更。
 * <p>
 * 存储策略：
 * <ul>
 *   <li>本地内存作为兜底，保证单实例也能工作</li>
 *   <li>若业务系统提供共享 {@link CacheManager}，则同时写入共享缓存</li>
 * </ul>
 */
@Slf4j
public class BearerAuthorityVersionService {

    private static final String AUTHORITY_VERSION_CACHE_NAME = "casBearerAuthorityVersions";

    private final Cache authorityVersionCache;
    private final ConcurrentHashMap<String, VersionRecord> localVersions = new ConcurrentHashMap<>();

    public BearerAuthorityVersionService(CacheManager cacheManager) {
        this.authorityVersionCache = cacheManager != null ? cacheManager.getCache(AUTHORITY_VERSION_CACHE_NAME) : null;
    }

    /**
     * 更新用户权限版本。
     *
     * @param userId 用户 ID
     * @param version 新的权限版本
     */
    public void updateUserVersion(String userId, String version) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(version)) {
            return;
        }

        VersionRecord record = new VersionRecord(version);
        localVersions.put(userId, record);
        if (authorityVersionCache != null) {
            authorityVersionCache.put(userId, record);
        }
    }

    /**
     * 获取用户当前已知的权限版本。
     *
     * @param userId 用户 ID
     * @return 权限版本，未知时返回 null
     */
    public String getUserVersion(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }

        VersionRecord local = localVersions.get(userId);
        if (local != null && StringUtils.hasText(local.version())) {
            return local.version();
        }

        if (authorityVersionCache == null) {
            return null;
        }

        Cache.ValueWrapper wrapper = authorityVersionCache.get(userId);
        if (wrapper == null) {
            return null;
        }

        Object cachedValue = wrapper.get();
        if (!(cachedValue instanceof VersionRecord record) || !StringUtils.hasText(record.version())) {
            authorityVersionCache.evict(userId);
            return null;
        }

        localVersions.put(userId, record);
        return record.version();
    }

    /**
     * 判断缓存中的权限版本是否已过期。
     *
     * @param userId 用户 ID
     * @param currentVersion 当前认证结果里的权限版本
     * @return true 表示存在更高优先级的最新版本
     */
    public boolean isVersionStale(String userId, String currentVersion) {
        String latestVersion = getUserVersion(userId);
        if (!StringUtils.hasText(latestVersion) || !StringUtils.hasText(currentVersion)) {
            return false;
        }
        return !latestVersion.equals(currentVersion);
    }

    /**
     * 清理本地缓存的权限版本。
     *
     * @param userId 用户 ID
     */
    public void evictUserVersion(String userId) {
        if (!StringUtils.hasText(userId)) {
            return;
        }

        localVersions.remove(userId);
        if (authorityVersionCache != null) {
            authorityVersionCache.evict(userId);
        }
    }

    /**
     * 当前本地版本缓存数量。
     *
     * @return 数量
     */
    public int localVersionCount() {
        cleanupInvalid(localVersions);
        return localVersions.size();
    }

    /**
     * 清理本地异常记录。
     */
    private void cleanupInvalid(ConcurrentHashMap<String, VersionRecord> registry) {
        for (Map.Entry<String, VersionRecord> entry : registry.entrySet()) {
            VersionRecord record = entry.getValue();
            if (record == null || !StringUtils.hasText(record.version())) {
                registry.remove(entry.getKey(), record);
            }
        }
    }

    /**
     * 权限版本记录。
     *
     * @param version 权限版本号
     */
    public record VersionRecord(String version) implements Serializable {
    }
}
