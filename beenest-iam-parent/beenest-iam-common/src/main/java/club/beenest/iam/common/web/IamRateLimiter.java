package club.beenest.iam.common.web;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的接口限流器。
 * <p>按场景与来源地址累计请求次数,窗口内超过阈值时抛出业务异常。
 * 计数存于共享 Redis,多实例使用同一份计数。</p>
 */
@Component
public class IamRateLimiter {

    private final RedisUtils redisUtils;

    public IamRateLimiter(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 按来源地址限速。
     *
     * @param scene  场景标识(不同场景各计一份)
     * @param ip     来源地址
     * @param limit  窗口内允许的最大次数
     * @param window 计数窗口
     */
    public void check(String scene, String ip, int limit, Duration window) {
        if (ip == null || ip.isBlank() || limit <= 0) {
            return;
        }
        String key = String.format(IamRedisKeyConstants.RATE_LIMIT, scene, ip);
        long count = redisUtils.incrementWithTtl(key, window);
        if (count > limit) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "请求过于频繁,请稍后再试");
        }
    }
}
