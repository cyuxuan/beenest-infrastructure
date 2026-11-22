package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.mapper.LdapConfigMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LDAP 联合配置存储(iam_realm_ldap_config,每域一行)。
 * <p>配置读走 Redis 缓存(5 分钟),更新时主动失效缓存。</p>
 */
@Component
public class IamLdapConfigStore {

    /** 配置缓存时长 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final LdapConfigMapper ldapConfigMapper;
    private final RedisUtils redisUtils;

    public IamLdapConfigStore(LdapConfigMapper ldapConfigMapper, RedisUtils redisUtils) {
        this.ldapConfigMapper = ldapConfigMapper;
        this.redisUtils = redisUtils;
    }

    /**
     * 查询域 LDAP 配置,带 Redis 缓存。
     *
     * @param realmId 身份域主键
     * @param realmKey 域标识(缓存键)
     * @return 配置;未配置返回 null
     */
    public LdapConfig getConfig(Long realmId, String realmKey) {
        String key = String.format(IamRedisKeyConstants.LDAP_CONFIG_CACHE, realmKey);
        Object cached = redisUtils.getObject(key, LdapConfig.class);
        if (cached instanceof LdapConfig config) {
            return config;
        }
        LdapConfig config = ldapConfigMapper.selectByRealmId(realmId);
        if (config != null) {
            redisUtils.setObject(key, config, CACHE_TTL);
        }
        return config;
    }

    /**
     * 确保域存在配置行,不存在时按缺省值插入。
     *
     * @param realmId 身份域主键
     * @return 配置行
     */
    public LdapConfig ensureConfig(Long realmId) {
        LdapConfig existing = ldapConfigMapper.selectByRealmId(realmId);
        if (existing != null) {
            return existing;
        }
        LdapConfig config = new LdapConfig();
        config.setRealmId(realmId);
        ldapConfigMapper.insert(config);
        return ldapConfigMapper.selectByRealmId(realmId);
    }

    /**
     * 更新配置并失效缓存。
     *
     * @param config 配置实体,需含主键
     * @param realmKey 域标识(缓存键)
     * @return 受影响行数
     */
    public int updateConfig(LdapConfig config, String realmKey) {
        int updated = ldapConfigMapper.updateById(config);
        evictCache(realmKey);
        return updated;
    }

    /**
     * 失效配置缓存。
     *
     * @param realmKey 域标识(缓存键)
     */
    public void evictCache(String realmKey) {
        redisUtils.delete(String.format(IamRedisKeyConstants.LDAP_CONFIG_CACHE, realmKey));
    }
}
