package club.beenest.payment.common.aspect;

import club.beenest.payment.common.annotation.RateLimiter;
import club.beenest.payment.common.exception.RiskControlException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * 分布式限流切面（基于 Redis + Lua 滑动窗口）
 *
 * <p>解析 @RateLimiter 注解，通过 Redis + Lua 脚本实现分布式滑动窗口限流。
 * 支持按用户、IP、全局维度限流。</p>
 *
 * @author System
 * @since 2026-04-02
 */
@Aspect
@Component
@Slf4j
public class RateLimiterAspect {

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimiterAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Lua 滑动窗口限流脚本
     * KEYS[1] = 限流 key
     * ARGV[1] = 窗口大小（秒）
     * ARGV[2] = 最大请求数
     * ARGV[3] = 当前时间戳（毫秒）
     * 返回: 0=放行, 1=限流
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local window = tonumber(ARGV[1])\n" +
            "local limit = tonumber(ARGV[2])\n" +
            "local now = tonumber(ARGV[3])\n" +
            "local windowStart = now - window * 1000\n" +
            "redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)\n" +
            "local count = redis.call('ZCARD', key)\n" +
            "if count < limit then\n" +
            "    redis.call('ZADD', key, now, now .. ':' .. math.random(1, 1000000))\n" +
            "    redis.call('EXPIRE', key, window + 1)\n" +
            "    return 0\n" +
            "end\n" +
            "return 1";

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint point, RateLimiter rateLimiter) throws Throwable {
        String key = generateKey(point, rateLimiter);
        int limit = rateLimiter.limit();
        int period = rateLimiter.period();

        try {
            Long result = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(period),
                    String.valueOf(limit),
                    String.valueOf(System.currentTimeMillis())
            );

            if (result != null && result == 1L) {
                log.warn("请求被限流 - key: {}, limit: {}/{}s", key, limit, period);
                throw new RiskControlException("操作过于频繁，请稍后再试");
            }
        } catch (RiskControlException e) {
            throw e;
        } catch (Exception e) {
            // Redis 不可用时降级为放行，避免影响主流程
            log.warn("限流检查异常（降级放行）- key: {}, error: {}", key, e.getMessage());
        }

        return point.proceed();
    }

    private String generateKey(ProceedingJoinPoint point, RateLimiter rateLimiter) {
        MethodSignature signature = (MethodSignature) point.getSignature();
        String methodName = signature.getDeclaringType().getSimpleName() + "." + signature.getMethod().getName();

        String keyValue = rateLimiter.key();
        Object[] args = point.getArgs();

        // 解析 #{paramName} 占位符
        if (args.length > 0 && keyValue.contains("#{")) {
            String paramName = keyValue.substring(keyValue.indexOf("#{") + 2, keyValue.indexOf("}"));
            String[] paramNames = signature.getParameterNames();
            for (int i = 0; i < paramNames.length; i++) {
                if (paramNames[i].equals(paramName)) {
                    keyValue = keyValue.replace("#{" + paramName + "}", String.valueOf(args[i]));
                    break;
                }
            }
        }

        return "payment:rate_limit:" + methodName + ":" + keyValue;
    }
}
