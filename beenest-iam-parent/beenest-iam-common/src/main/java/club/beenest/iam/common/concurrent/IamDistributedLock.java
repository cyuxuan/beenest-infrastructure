package club.beenest.iam.common.concurrent;

import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的分布式锁。
 * <p>用于多实例环境下需要互斥执行的任务,如定时任务、批量重建与密钥轮换。
 * 加锁通过 {@code SET key token NX PX ttl} 原子完成;释放时用 Lua 脚本
 * 比较持有令牌后再删除,避免误删其他实例持有的锁。</p>
 */
@Component
public class IamDistributedLock {

    /** 比较并删除:值与持有令牌一致时删除并返回 1,不一致返回 0 */
    private static final String COMPARE_AND_DELETE =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final RedisUtils redisUtils;

    public IamDistributedLock(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 尝试加锁。
     *
     * @param key  锁键
     * @param ttl  锁的自动过期时长(必须大于任务本身的执行时间)
     * @return 持有令牌;返回 null 表示锁已被别的实例持有
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        return redisUtils.setIfAbsent(key, token, ttl) ? token : null;
    }

    /**
     * 释放锁(只释放自己持有的那把)。
     *
     * @param key   锁键
     * @param token {@link #tryLock} 返回的持有令牌
     */
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            redisUtils.eval(COMPARE_AND_DELETE, List.of(key), List.of(token));
        } catch (RuntimeException e) {
            // 释放失败只意味着锁会等到 TTL 自然过期,不影响正确性
            org.slf4j.LoggerFactory.getLogger(IamDistributedLock.class)
                    .warn("释放分布式锁失败: key={}, error={}", key, e.getMessage());
        }
    }
}
