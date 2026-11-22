package club.beenest.iam.authz;

import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.persistence.entity.LoginPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败计数器单元测试(内存 Map 模拟 Redis)。
 */
class LoginFailureCounterTest {

    /** 极简内存 Redis 替身:只实现计数器用到的方法 */
    static class FakeRedis extends RedisUtils {
        private final Map<String, String> store = new ConcurrentHashMap<>();

        FakeRedis() {
            super(null, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public long incrementWithTtl(String key, java.time.Duration ttl) {
            String updated = store.merge(key, "1",
                    (old, ignored) -> String.valueOf(Long.parseLong(old) + 1));
            return Long.parseLong(updated);
        }

        @Override
        public String get(String key) {
            return store.get(key);
        }

        @Override
        public void set(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void set(String key, String value, java.time.Duration ttl) {
            store.put(key, value);
        }

        @Override
        public boolean hasKey(String key) {
            return store.containsKey(key);
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }

        @Override
        public Long delete(String... keys) {
            long count = 0;
            for (String key : keys) {
                if (store.remove(key) != null) {
                    count++;
                }
            }
            return count;
        }
    }

    private LoginFailureCounter counter;
    private LoginPolicy policy;

    @BeforeEach
    void setUp() {
        counter = new LoginFailureCounter(new FakeRedis());
        policy = new LoginPolicy();
        policy.setMaxFailAttempts(3);
        policy.setFailureWindowMinutes(15);
        policy.setLockDurationMinutes(30);
        policy.setCaptchaThreshold(2);
    }

    @Test
    @DisplayName("失败达到阈值才锁定,成功后清除")
    void lockAtThreshold() {
        assertFalse(counter.isLocked("default", "zhangsan", policy));
        counter.onFailure("default", "zhangsan", policy);
        assertFalse(counter.isLocked("default", "zhangsan", policy));
        counter.onFailure("default", "zhangsan", policy);
        assertFalse(counter.isLocked("default", "zhangsan", policy));
        // 第 3 次失败达到阈值 → 锁定
        counter.onFailure("default", "zhangsan", policy);
        assertTrue(counter.isLocked("default", "zhangsan", policy));
        // 成功后清除
        counter.onSuccess("default", "zhangsan");
        assertFalse(counter.isLocked("default", "zhangsan", policy));
    }

    @Test
    @DisplayName("验证码阈值按失败次数触发")
    void captchaThreshold() {
        assertFalse(counter.captchaRequired("default", "zhangsan", policy));
        counter.onFailure("default", "zhangsan", policy);
        assertFalse(counter.captchaRequired("default", "zhangsan", policy));
        counter.onFailure("default", "zhangsan", policy);
        assertTrue(counter.captchaRequired("default", "zhangsan", policy));
    }

    @Test
    @DisplayName("锁定按域+登录名维度互不影响")
    void perRealmAndUser() {
        for (int i = 0; i < 3; i++) {
            counter.onFailure("default", "zhangsan", policy);
        }
        assertTrue(counter.isLocked("default", "zhangsan", policy));
        assertFalse(counter.isLocked("default", "lisi", policy));
        assertFalse(counter.isLocked("other", "zhangsan", policy));
    }

    @Test
    @DisplayName("策略为 null 时不锁定(放开保护由调用方保证策略存在)")
    void nullPolicy() {
        for (int i = 0; i < 10; i++) {
            counter.onFailure("default", "zhangsan", null);
        }
        assertFalse(counter.isLocked("default", "zhangsan", null));
    }
}
