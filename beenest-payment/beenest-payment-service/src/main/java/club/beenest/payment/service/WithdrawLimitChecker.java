package club.beenest.payment.service;

import club.beenest.payment.common.constant.PaymentRedisKeyConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;

/**
 * 提现额度检查服务
 * 检查单日总额度和单日单用户额度
 *
 * <p>依赖 Redis 实现，Redis 不可用时拒绝所有提现请求以保证资金安全。</p>
 *
 * @author System
 * @since 2026-01-27
 */
@Service
@Slf4j
public class WithdrawLimitChecker {

    /**
     * StringRedisTemplate 用于执行 Lua 脚本
     * 注意：此处保留 StringRedisTemplate 而非使用 RedisUtils，
     * 因为需要执行复杂的 Lua 脚本操作（RESERVE_SCRIPT 和 RELEASE_SCRIPT）
     */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        RESERVE_SCRIPT.setResultType(Long.class);
        RESERVE_SCRIPT.setScriptText(
                "local amount = tonumber(ARGV[1]);" +
                "local totalLimit = tonumber(ARGV[2]);" +
                "local userLimit = tonumber(ARGV[3]);" +
                "local ttl = tonumber(ARGV[4]);" +
                "local total = tonumber(redis.call('GET', KEYS[1]) or '0');" +
                "if totalLimit >= 0 and (total + amount) > totalLimit then return 2 end;" +
                "local user = tonumber(redis.call('GET', KEYS[2]) or '0');" +
                "if userLimit >= 0 and (user + amount) > userLimit then return 3 end;" +
                "redis.call('INCRBY', KEYS[1], amount);" +
                "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ttl) end;" +
                "redis.call('INCRBY', KEYS[2], amount);" +
                "if redis.call('TTL', KEYS[2]) < 0 then redis.call('EXPIRE', KEYS[2], ttl) end;" +
                "return 1;"
        );

        RELEASE_SCRIPT.setResultType(Long.class);
        RELEASE_SCRIPT.setScriptText(
                "local amount = tonumber(ARGV[1]);" +
                "local ttl = tonumber(ARGV[2]);" +
                "local total = tonumber(redis.call('GET', KEYS[1]) or '0');" +
                "local user = tonumber(redis.call('GET', KEYS[2]) or '0');" +
                "local newTotal = total - amount; if newTotal < 0 then newTotal = 0 end;" +
                "local newUser = user - amount; if newUser < 0 then newUser = 0 end;" +
                "redis.call('SET', KEYS[1], newTotal);" +
                "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ttl) end;" +
                "redis.call('SET', KEYS[2], newUser);" +
                "if redis.call('TTL', KEYS[2]) < 0 then redis.call('EXPIRE', KEYS[2], ttl) end;" +
                "return 1;"
        );
    }

    public enum ReserveCode {
        OK(1),
        DAILY_TOTAL_EXCEEDED(2),
        DAILY_USER_EXCEEDED(3);

        private final int code;

        ReserveCode(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static ReserveCode from(Long value) {
            if (value == null) {
                return DAILY_TOTAL_EXCEEDED;
            }
            if (value.intValue() == OK.code) {
                return OK;
            }
            if (value.intValue() == DAILY_TOTAL_EXCEEDED.code) {
                return DAILY_TOTAL_EXCEEDED;
            }
            return DAILY_USER_EXCEEDED;
        }
    }

    /**
     * 检查单日总额度
     * 
     * @param platform 平台
     * @param amount 提现金额
     * @param limit 额度限制
     * @return true表示通过，false表示超限
     */
    public boolean checkDailyTotalLimit(String platform, Long amount, Long limit) {
        if (stringRedisTemplate == null) {
            log.error("Redis不可用，单日总额度检查无法执行，拒绝请求");
            return false;
        }
        String key = getDailyTotalRedisKey(platform);
        Long total = readLong(key);
        if (limit != null && total + amount > limit) {
            log.warn("超过单日总额度 - platform: {}, total: {}, amount: {}, limit: {}",
                    platform, total, amount, limit);
            return false;
        }
        log.debug("单日总额度检查通过 - platform: {}, total: {}, amount: {}, limit: {}",
                platform, total, amount, limit);
        return true;
    }
    
    /**
     * 检查单日单用户额度
     * 
     * @param platform 平台
     * @param customerNo 用户编号
     * @param amount 提现金额
     * @param limit 额度限制
     * @return true表示通过，false表示超限
     */
    public boolean checkDailyUserLimit(String platform, String customerNo, Long amount, Long limit) {
        if (stringRedisTemplate == null) {
            log.error("Redis不可用，单日单用户额度检查无法执行，拒绝请求");
            return false;
        }
        String key = getDailyUserRedisKey(platform, customerNo);
        Long total = readLong(key);
        if (limit != null && total + amount > limit) {
            log.warn("超过单日单用户额度 - platform: {}, customerNo: {}, total: {}, amount: {}, limit: {}",
                    platform, customerNo, total, amount, limit);
            return false;
        }
        log.debug("单日单用户额度检查通过 - platform: {}, customerNo: {}, total: {}, amount: {}, limit: {}",
                platform, customerNo, total, amount, limit);
        return true;
    }
    
    /**
     * 增加今日提现金额
     * 
     * @param platform 平台
     * @param customerNo 用户编号
     * @param amount 提现金额
     */
    public void incrementTodayAmount(String platform, String customerNo, Long amount) {
        if (stringRedisTemplate == null) {
            log.error("Redis不可用，无法记录提现金额");
            return;
        }
        int ttlSeconds = secondsUntilEndOfDay();
        String totalKey = getDailyTotalRedisKey(platform);
        Long total = stringRedisTemplate.opsForValue().increment(totalKey, amount);
        if (total != null && Objects.equals(total, amount)) {
            stringRedisTemplate.expire(totalKey, java.time.Duration.ofSeconds(ttlSeconds));
        }

        String userKey = getDailyUserRedisKey(platform, customerNo);
        Long user = stringRedisTemplate.opsForValue().increment(userKey, amount);
        if (user != null && Objects.equals(user, amount)) {
            stringRedisTemplate.expire(userKey, java.time.Duration.ofSeconds(ttlSeconds));
        }

        log.info("记录今日提现金额 - platform: {}, customerNo: {}, amount: {}",
                platform, customerNo, amount);
    }
    
    /**
     * 获取今日总提现金额
     * 
     * @param platform 平台
     * @return 今日总提现金额
     */
    public Long getTodayTotal(String platform) {
        if (stringRedisTemplate == null) {
            return 0L;
        }
        return readLong(getDailyTotalRedisKey(platform));
    }
    
    /**
     * 获取用户今日提现金额
     * 
     * @param platform 平台
     * @param customerNo 用户编号
     * @return 用户今日提现金额
     */
    public Long getUserTodayTotal(String platform, String customerNo) {
        if (stringRedisTemplate == null) {
            return 0L;
        }
        return readLong(getDailyUserRedisKey(platform, customerNo));
    }

    public ReserveCode reserveDailyAmount(String platform,
                                         String customerNo,
                                         Long amount,
                                         Long dailyTotalLimit,
                                         Long dailyUserLimit) {
        if (stringRedisTemplate == null) {
            // Redis 不可用时拒绝提现，防止多实例部署额度限制失效
            log.error("Redis不可用，提现额度检查无法执行，拒绝提现请求");
            return ReserveCode.DAILY_TOTAL_EXCEEDED;
        }

        int ttlSeconds = secondsUntilEndOfDay();
        String totalKey = getDailyTotalRedisKey(platform);
        String userKey = getDailyUserRedisKey(platform, customerNo);
        Long totalLimitValue = dailyTotalLimit == null ? -1L : dailyTotalLimit;
        Long userLimitValue = dailyUserLimit == null ? -1L : dailyUserLimit;

        Long result = stringRedisTemplate.execute(
                RESERVE_SCRIPT,
                Arrays.asList(totalKey, userKey),
                String.valueOf(amount),
                String.valueOf(totalLimitValue),
                String.valueOf(userLimitValue),
                String.valueOf(ttlSeconds)
        );

        return ReserveCode.from(result);
    }

    public void releaseDailyAmount(String platform, String customerNo, Long amount) {
        if (stringRedisTemplate == null) {
            log.warn("Redis不可用，跳过额度释放（额度预留本身已被拒绝）");
            return;
        }

        int ttlSeconds = secondsUntilEndOfDay();
        String totalKey = getDailyTotalRedisKey(platform);
        String userKey = getDailyUserRedisKey(platform, customerNo);
        stringRedisTemplate.execute(
                RELEASE_SCRIPT,
                Arrays.asList(totalKey, userKey),
                String.valueOf(amount),
                String.valueOf(ttlSeconds)
        );
    }
    
    private String getDailyTotalRedisKey(String platform) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return PaymentRedisKeyConstants.buildWithdrawDailyTotalKey(platform, today);
    }

    private String getDailyUserRedisKey(String platform, String customerNo) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return PaymentRedisKeyConstants.buildWithdrawDailyUserKey(platform, customerNo, today);
    }

    private Long readLong(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int secondsUntilEndOfDay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.MIDNIGHT);
        long seconds = java.time.Duration.between(now, end).getSeconds();
        if (seconds <= 0) {
            return 60;
        }
        return (int) seconds;
    }
}
