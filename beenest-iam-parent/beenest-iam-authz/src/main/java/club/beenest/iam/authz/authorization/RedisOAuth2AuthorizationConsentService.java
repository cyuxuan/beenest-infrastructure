package club.beenest.iam.authz.authorization;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis 授权同意存储。
 * <p>键 {@code iam:consent:{registeredClientId}:{principalName}} 存已批准的
 * scope/权限集合;不存在即视为未同意。首次授予时间单独存在
 * {@code iam:consent-granted:*} 键上,与同意记录同生命周期,供用户中心展示。</p>
 * <p>列举走键扫描而非额外索引:同意记录量级小,且列举属低频操作。</p>
 */
@Component
public class RedisOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {

    /** 同意记录有效期;过期后用户需重新确认 */
    private static final Duration CONSENT_TTL = Duration.ofDays(30);

    /** 键名中 {@code :consent:} 之后的起始位置,用于解析 clientId 与登录名 */
    private static final int CONSENT_SEGMENT_OFFSET =
            IamRedisKeyConstants.PREFIX.length() + ":consent:".length();

    private final RedisUtils redisUtils;

    public RedisOAuth2AuthorizationConsentService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    @Override
    public void save(OAuth2AuthorizationConsent consent) {
        // 1. 同意集合转为字符串集合存储
        Set<String> authorities = consent.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        String key = key(consent.getRegisteredClientId(), consent.getPrincipalName());
        redisUtils.setObject(key, authorities, CONSENT_TTL);
        // 2. 首次授予时间只写一次:再次同意只刷新 scope,不改变"从何时起授权"这一事实
        redisUtils.setIfAbsent(String.format(IamRedisKeyConstants.OAUTH2_CONSENT_GRANTED,
                        consent.getRegisteredClientId(), consent.getPrincipalName()),
                String.valueOf(System.currentTimeMillis()), CONSENT_TTL);
    }

    @Override
    public void remove(OAuth2AuthorizationConsent consent) {
        delete(consent.getRegisteredClientId(), consent.getPrincipalName());
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        // 1. 读取原始集合,不存在即未同意
        Set<?> raw = redisUtils.getObject(key(registeredClientId, principalName), Set.class);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        // 2. 回填为同意对象
        Set<GrantedAuthority> granted = new HashSet<>();
        raw.forEach(a -> granted.add(new SimpleGrantedAuthority(String.valueOf(a))));
        return OAuth2AuthorizationConsent.withId(registeredClientId, principalName)
                .authorities(set -> set.addAll(granted))
                .build();
    }

    /**
     * 列举指定登录名的全部同意记录。
     *
     * @param principalName 登录名,按完全相等匹配
     * @return key 为客户端主键,value 为同意的 scope 集合;无记录时返回空 Map
     */
    public Map<String, Set<String>> findByPrincipal(String principalName) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (principalName == null || principalName.isBlank()) {
            return result;
        }
        for (String redisKey : scanKeys(IamRedisKeyConstants.OAUTH2_CONSENT.replace("%s:%s", "*"))) {
            String rest = redisKey.substring(CONSENT_SEGMENT_OFFSET);
            int split = rest.indexOf(':');
            if (split < 0 || !rest.substring(split + 1).equals(principalName)) {
                continue;
            }
            Set<?> raw = redisUtils.getObject(redisKey, Set.class);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            result.put(rest.substring(0, split),
                    raw.stream().map(String::valueOf).collect(Collectors.toCollection(HashSet::new)));
        }
        return result;
    }

    /**
     * 读取同意记录的首次授予时间。
     *
     * @param registeredClientId 客户端主键
     * @param principalName      授权主体名
     * @return 授予时间(epoch 毫秒);记录缺失或格式非法时返回 null
     */
    public Long grantedAt(String registeredClientId, String principalName) {
        String raw = redisUtils.get(String.format(IamRedisKeyConstants.OAUTH2_CONSENT_GRANTED,
                registeredClientId, principalName));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 列举同意记录(管理端)。
     *
     * @param principalNameFilter 登录名过滤;为空时返回全部
     * @return key 为 clientId,value 为该客户端下的"登录名 → 同意的 scope 集合"
     */
    public Map<String, Map<String, Set<String>>> listAll(String principalNameFilter) {
        Map<String, Map<String, Set<String>>> result = new LinkedHashMap<>();
        for (String redisKey : scanKeys(IamRedisKeyConstants.OAUTH2_CONSENT.replace("%s:%s", "*"))) {
            // 1. 从键名解析 clientId 与 principalName:iam:consent:{clientId}:{principalName}
            String rest = redisKey.substring(CONSENT_SEGMENT_OFFSET);
            int split = rest.indexOf(':');
            if (split < 0) {
                continue;
            }
            String clientId = rest.substring(0, split);
            String principalName = rest.substring(split + 1);
            if (principalNameFilter != null && !principalNameFilter.isBlank()
                    && !principalName.contains(principalNameFilter)) {
                continue;
            }
            // 2. 读取同意的 scope 集合
            Set<?> raw = redisUtils.getObject(redisKey, Set.class);
            if (raw == null) {
                continue;
            }
            Set<String> authorities = raw.stream().map(String::valueOf).collect(Collectors.toSet());
            result.computeIfAbsent(clientId, k -> new LinkedHashMap<>())
                    .put(principalName, authorities);
        }
        return result;
    }

    /**
     * 撤销指定用户对指定客户端的同意(管理端)。
     *
     * @param registeredClientId 客户端主键
     * @param principalName      授权主体名
     * @return true 表示同意记录存在且已删除
     */
    public boolean revoke(String registeredClientId, String principalName) {
        String redisKey = key(registeredClientId, principalName);
        if (!redisUtils.hasKey(redisKey)) {
            return false;
        }
        delete(registeredClientId, principalName);
        return true;
    }

    /**
     * 删除同意记录及其授予时间。
     *
     * @param registeredClientId 客户端主键
     * @param principalName      授权主体名
     */
    private void delete(String registeredClientId, String principalName) {
        redisUtils.delete(key(registeredClientId, principalName),
                String.format(IamRedisKeyConstants.OAUTH2_CONSENT_GRANTED, registeredClientId, principalName));
    }

    /**
     * 按模式扫描同意记录键,使用 SCAN 代替 KEYS。
     *
     * @param pattern 键匹配模式
     * @return 命中的键集合;无结果时返回空集合
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = redisUtils.executeScan(pattern);
        return keys == null ? Set.of() : keys;
    }

    /**
     * 拼装同意记录的 Redis 键。
     *
     * @param registeredClientId 客户端主键
     * @param principalName      授权主体名
     * @return 同意记录键
     */
    private String key(String registeredClientId, String principalName) {
        return String.format(IamRedisKeyConstants.OAUTH2_CONSENT, registeredClientId, principalName);
    }
}
