package club.beenest.iam.authz.authentication;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.persistence.entity.LoginPolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败计数器(策略驱动)。
 * <p>按 realmKey 与 username 维度计数:窗口内失败达到
 * {@link LoginPolicy#getMaxFailAttempts()} 次后写入独立锁定键,锁定时长取
 * {@link LoginPolicy#getLockDurationMinutes()};计数键与锁定键分离,失败窗口与
 * 锁定时长各自独立过期。</p>
 * <p>{@code policy} 为 null 时按未启用策略处理:不计数、不锁定、不要求验证码。</p>
 */
@Component
public class LoginFailureCounter {

    /** 策略未配置时的失败窗口默认值(分钟) */
    private static final int DEFAULT_FAILURE_WINDOW_MINUTES = 15;
    /** 策略未配置时的最大失败次数默认值 */
    private static final int DEFAULT_MAX_FAIL_ATTEMPTS = 5;
    /** 策略未配置时的锁定时长默认值(分钟) */
    private static final int DEFAULT_LOCK_MINUTES = 15;

    private final RedisUtils redisUtils;

    public LoginFailureCounter(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 判断账号是否处于锁定状态。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     * @param policy   登录策略;为 null 时视为未启用
     * @return true 表示锁定键存在
     */
    public boolean isLocked(String realmKey, String username, LoginPolicy policy) {
        if (policy == null) {
            return false;
        }
        return Boolean.TRUE.equals(redisUtils.hasKey(lockKey(realmKey, username)));
    }

    /**
     * 记录一次账号维度失败,累计达到阈值时写入锁定键。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     * @param policy   登录策略;为 null 时不计数
     * @return 本次失败后的累计次数;policy 为 null 时返回 0
     */
    public long onFailure(String realmKey, String username, LoginPolicy policy) {
        if (policy == null) {
            return 0L;
        }
        String countKey = countKey(realmKey, username);
        Duration window = Duration.ofMinutes(
                policy.getFailureWindowMinutes() == null
                        ? DEFAULT_FAILURE_WINDOW_MINUTES : policy.getFailureWindowMinutes());
        long count = redisUtils.incrementWithTtl(countKey, window);
        int maxAttempts = policy.getMaxFailAttempts() == null
                ? DEFAULT_MAX_FAIL_ATTEMPTS : policy.getMaxFailAttempts();
        if (count >= maxAttempts) {
            Duration lock = Duration.ofMinutes(
                    policy.getLockDurationMinutes() == null
                            ? DEFAULT_LOCK_MINUTES : policy.getLockDurationMinutes());
            redisUtils.set(lockKey(realmKey, username), "1", lock);
        }
        return count;
    }

    /**
     * 登录成功后清除该账号的失败计数与锁定。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     */
    public void onSuccess(String realmKey, String username) {
        redisUtils.delete(countKey(realmKey, username), lockKey(realmKey, username));
    }

    /**
     * 读取账号当前失败次数,供验证码阈值判断使用。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     * @return 当前计数;计数键不存在时返回 0
     */
    public long failureCount(String realmKey, String username) {
        String value = redisUtils.get(countKey(realmKey, username));
        return value == null ? 0L : Long.parseLong(value);
    }

    /**
     * 判断当前失败次数是否已达到验证码阈值。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     * @param policy   登录策略;阈值未配置或非正数时返回 false
     * @return true 表示需要验证码
     */
    public boolean captchaRequired(String realmKey, String username, LoginPolicy policy) {
        if (policy == null || policy.getCaptchaThreshold() == null || policy.getCaptchaThreshold() <= 0) {
            return false;
        }
        return failureCount(realmKey, username) >= policy.getCaptchaThreshold();
    }

    /**
     * 管理员手动解除该账号的失败计数与锁定。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     */
    public void unlock(String realmKey, String username) {
        redisUtils.delete(countKey(realmKey, username), lockKey(realmKey, username));
    }

    /**
     * 判断来源 IP 是否处于锁定状态。
     * <p>用于防范密码喷洒:同一 IP 轮换账号时账号维度不会被锁定,IP 维度单独计数。</p>
     *
     * @param realmKey realm 标识
     * @param ip       来源 IP
     * @param policy   登录策略;IP 阈值未配置或非正数时返回 false
     * @return true 表示该 IP 已被锁定
     */
    public boolean isIpLocked(String realmKey, String ip, LoginPolicy policy) {
        if (policy == null || ip == null || ip.isBlank()) {
            return false;
        }
        if (policy.getIpMaxFailAttempts() == null || policy.getIpMaxFailAttempts() <= 0) {
            return false;
        }
        return Boolean.TRUE.equals(redisUtils.hasKey(ipLockKey(realmKey, ip)));
    }

    /**
     * 记录一次来源 IP 的失败,累计达到阈值时写入 IP 锁定键。
     * <p>IP 计数不因登录成功清零,仅随失败窗口自然过期。</p>
     *
     * @param realmKey realm 标识
     * @param ip       来源 IP;为空时忽略
     * @param policy   登录策略;为 null 时忽略
     */
    public void onIpFailure(String realmKey, String ip, LoginPolicy policy) {
        if (policy == null || ip == null || ip.isBlank()) {
            return;
        }
        int maxAttempts = policy.getIpMaxFailAttempts() == null
                ? DEFAULT_MAX_FAIL_ATTEMPTS : policy.getIpMaxFailAttempts();
        if (maxAttempts <= 0) {
            return;
        }
        Duration window = Duration.ofMinutes(
                policy.getFailureWindowMinutes() == null
                        ? DEFAULT_FAILURE_WINDOW_MINUTES : policy.getFailureWindowMinutes());
        long count = redisUtils.incrementWithTtl(ipCountKey(realmKey, ip), window);
        if (count >= maxAttempts) {
            Duration lock = Duration.ofMinutes(
                    policy.getIpLockDurationMinutes() == null
                            ? DEFAULT_LOCK_MINUTES : policy.getIpLockDurationMinutes());
            redisUtils.set(ipLockKey(realmKey, ip), "1", lock);
        }
    }

    /**
     * 管理员手动解除该 IP 的失败计数与锁定。
     *
     * @param realmKey realm 标识
     * @param ip       来源 IP
     */
    public void unlockIp(String realmKey, String ip) {
        redisUtils.delete(ipCountKey(realmKey, ip), ipLockKey(realmKey, ip));
    }

    /**
     * 账号失败计数键。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     * @return Redis 键
     */
    private static String countKey(String realmKey, String username) {
        return String.format(IamRedisKeyConstants.LOGIN_FAIL_COUNT, realmKey, username);
    }

    /**
     * 账号锁定键。
     *
     * @param realmKey realm 标识
     * @param username 登录名
     * @return Redis 键
     */
    private static String lockKey(String realmKey, String username) {
        return String.format(IamRedisKeyConstants.LOGIN_LOCK, realmKey, username);
    }

    /**
     * 来源 IP 失败计数键。
     *
     * @param realmKey realm 标识
     * @param ip       来源 IP
     * @return Redis 键
     */
    private static String ipCountKey(String realmKey, String ip) {
        return String.format(IamRedisKeyConstants.LOGIN_FAIL_COUNT_IP, realmKey, ip);
    }

    /**
     * 来源 IP 锁定键。
     *
     * @param realmKey realm 标识
     * @param ip       来源 IP
     * @return Redis 键
     */
    private static String ipLockKey(String realmKey, String ip) {
        return String.format(IamRedisKeyConstants.LOGIN_LOCK_IP, realmKey, ip);
    }
}
