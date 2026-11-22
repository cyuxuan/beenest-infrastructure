package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.entity.PasswordPolicy;
import club.beenest.iam.persistence.mapper.LoginPolicyMapper;
import club.beenest.iam.persistence.mapper.PasswordPolicyMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 安全策略存储(iam_password_policy / iam_login_policy,每域一行)。
 * <p>登录策略读走 Redis 缓存(5 分钟),更新后需调用 {@link #evictLoginPolicyCache} 失效。</p>
 */
@Component
public class IamSecurityPolicyManager {

    /** 登录策略缓存时长 */
    private static final Duration LOGIN_POLICY_CACHE_TTL = Duration.ofMinutes(5);

    private final PasswordPolicyMapper passwordPolicyMapper;
    private final LoginPolicyMapper loginPolicyMapper;
    private final RedisUtils redisUtils;

    public IamSecurityPolicyManager(PasswordPolicyMapper passwordPolicyMapper,
                                    LoginPolicyMapper loginPolicyMapper,
                                    RedisUtils redisUtils) {
        this.passwordPolicyMapper = passwordPolicyMapper;
        this.loginPolicyMapper = loginPolicyMapper;
        this.redisUtils = redisUtils;
    }

    // ===== 口令策略 =====

    /**
     * 查询域口令策略。
     *
     * @param realmId 身份域主键
     * @return 口令策略;不存在返回 null
     */
    public PasswordPolicy getPasswordPolicy(Long realmId) {
        return passwordPolicyMapper.selectByRealmId(realmId);
    }

    /**
     * 确保域存在口令策略行,不存在时按缺省值插入。
     *
     * @param realmId 身份域主键
     * @return 口令策略
     */
    public PasswordPolicy ensurePasswordPolicy(Long realmId) {
        PasswordPolicy existing = passwordPolicyMapper.selectByRealmId(realmId);
        if (existing != null) {
            return existing;
        }
        PasswordPolicy policy = new PasswordPolicy();
        policy.setRealmId(realmId);
        passwordPolicyMapper.insert(policy);
        return passwordPolicyMapper.selectByRealmId(realmId);
    }

    /**
     * 更新口令策略。
     *
     * @param policy 口令策略实体,需含主键
     * @return 受影响行数
     */
    public int updatePasswordPolicy(PasswordPolicy policy) {
        return passwordPolicyMapper.updateById(policy);
    }

    // ===== 登录策略 =====

    /**
     * 查询域登录策略,带 Redis 缓存。
     *
     * @param realmId 身份域主键
     * @param realmKey 域标识(缓存键)
     * @return 登录策略;未配置返回 null
     */
    public LoginPolicy getLoginPolicy(Long realmId, String realmKey) {
        String key = String.format(IamRedisKeyConstants.LOGIN_POLICY_CACHE, realmKey);
        Object cached = redisUtils.getObject(key, LoginPolicy.class);
        if (cached instanceof LoginPolicy policy) {
            return policy;
        }
        LoginPolicy policy = loginPolicyMapper.selectByRealmId(realmId);
        if (policy != null) {
            redisUtils.setObject(key, policy, LOGIN_POLICY_CACHE_TTL);
        }
        return policy;
    }

    /**
     * 确保域存在登录策略行,不存在时按缺省值插入。
     *
     * @param realmId 身份域主键
     * @return 登录策略
     */
    public LoginPolicy ensureLoginPolicy(Long realmId) {
        LoginPolicy existing = loginPolicyMapper.selectByRealmId(realmId);
        if (existing != null) {
            return existing;
        }
        LoginPolicy policy = new LoginPolicy();
        policy.setRealmId(realmId);
        loginPolicyMapper.insert(policy);
        return loginPolicyMapper.selectByRealmId(realmId);
    }

    /**
     * 更新登录策略,不失效缓存。
     *
     * @param policy 登录策略实体,需含主键
     * @return 受影响行数
     */
    public int updateLoginPolicy(LoginPolicy policy) {
        int updated = loginPolicyMapper.updateById(policy);
        return updated;
    }

    /**
     * 失效登录策略缓存。
     *
     * @param realmKey 域标识(缓存键)
     */
    public void evictLoginPolicyCache(String realmKey) {
        redisUtils.delete(String.format(IamRedisKeyConstants.LOGIN_POLICY_CACHE, realmKey));
    }
}
