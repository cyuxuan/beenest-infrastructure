package club.beenest.iam.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis 操作工具类。
 * <p>提供字符串、对象、集合与哈希操作,以及 Lua 脚本执行。
 * 字符串键值走 {@link StringRedisTemplate};对象序列化固定使用 Jackson 2,
 * 以兼容既有缓存数据。</p>
 */
@Component
public class RedisUtils {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper jackson2ObjectMapper;

    public RedisUtils(StringRedisTemplate stringRedisTemplate, ObjectMapper jackson2ObjectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        // 独立副本,避免污染全局映射器配置
        this.jackson2ObjectMapper = jackson2ObjectMapper.copy();
    }

    // ===== 字符串操作 =====

    /**
     * 写入字符串键值(无过期时间)。
     *
     * @param key   键
     * @param value 值
     */
    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    /**
     * 写入字符串键值并设置过期时间。
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时间
     */
    public void set(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    /**
     * 原子读取并删除(GETDEL)。
     * <p>用于一次性凭证的消费,保证并发请求中只有一个能读到值。</p>
     *
     * @param key 键
     * @return 删除前的值;键不存在返回 null
     */
    public String getAndDelete(String key) {
        return stringRedisTemplate.opsForValue().getAndDelete(key);
    }

    /**
     * 仅当键不存在时写入并设置过期时间(SET NX)。
     * <p>用于一次性凭证与并发锁等需要原子抢占的场景。</p>
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时间
     * @return true 表示本次抢占成功;false 表示键已存在
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 读取字符串键值。
     *
     * @param key 键
     * @return 键对应的值;不存在返回 null
     */
    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 删除一个或多个键。
     *
     * @param keys 键
     * @return 实际删除的键数量
     */
    public Long delete(String... keys) {
        return stringRedisTemplate.delete(List.of(keys));
    }

    /**
     * 删除单个键。
     *
     * @param key 键
     * @return true 表示键存在且已删除
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
    }

    /**
     * 按模式批量删除键(基于 SCAN)。
     *
     * @param pattern 匹配模式,如 {@code iam:session:*}
     */
    public void deleteByPattern(String pattern) {
        Set<String> keys = executeScan(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /**
     * 按模式扫描键(基于 SCAN)。
     * <p>SCAN 不保证原子性与完备性,仅用于运维列举等低频操作,
     * 不得用于正确性敏感的写入路径。</p>
     *
     * @param pattern 匹配模式
     * @return 命中的键集合
     */
    public Set<String> executeScan(String pattern) {
        return stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> found = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.keyCommands()
                    .scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
                while (cursor.hasNext()) {
                    found.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return found;
        });
    }

    /**
     * 判断键是否存在。
     *
     * @param key 键
     * @return true 表示存在
     */
    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    /**
     * 设置键的过期时间。
     *
     * @param key 键
     * @param ttl 过期时间
     * @return true 表示设置成功
     */
    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(key, ttl));
    }

    /**
     * 自增并返回新值。
     *
     * @param key 键
     * @return 自增后的值
     */
    public long increment(String key) {
        Long value = stringRedisTemplate.opsForValue().increment(key);
        return value == null ? 0L : value;
    }

    /**
     * 自增并设置过期时间,仅在键首次创建时设置 TTL。
     *
     * @param key 键
     * @param ttl 过期时间
     * @return 自增后的值
     */
    public long incrementWithTtl(String key, Duration ttl) {
        long value = increment(key);
        if (value == 1L) {
            stringRedisTemplate.expire(key, ttl);
        }
        return value;
    }

    /**
     * 执行 Redis Lua 脚本。
     * <p>用于需要原子执行的复合操作,如分布式锁的"比较并删除"。</p>
     *
     * @param script Lua 脚本,必须为常量,禁止拼接外部输入
     * @param keys   脚本访问的键
     * @param args   脚本参数
     * @return 脚本返回的整数值
     */
    public Long eval(String script, List<String> keys, List<String> args) {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        return stringRedisTemplate.execute(redisScript, keys, args.toArray());
    }

    // ===== 对象操作(Jackson 2 序列化) =====

    /**
     * 写入对象(JSON 序列化)并设置过期时间。
     *
     * @param key    键
     * @param object 对象
     * @param ttl    过期时间
     * @param <T>    对象类型
     * @throws IllegalStateException 序列化失败
     */
    public <T> void setObject(String key, T object, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, jackson2ObjectMapper.writeValueAsString(object), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Redis 对象序列化失败: " + key, e);
        }
    }

    /**
     * 读取对象(JSON 反序列化)。
     *
     * @param key  键
     * @param type 目标类型
     * @param <T>  对象类型
     * @return 反序列化后的对象;键不存在返回 null
     * @throws IllegalStateException 反序列化失败
     */
    public <T> T getObject(String key, Class<T> type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return jackson2ObjectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Redis 对象反序列化失败: " + key, e);
        }
    }

    // ===== 集合操作 =====

    /**
     * 向集合追加成员。
     *
     * @param key     键
     * @param members 成员
     */
    public void addToSet(String key, String... members) {
        stringRedisTemplate.opsForSet().add(key, members);
    }

    /**
     * 从集合移除成员。
     *
     * @param key     键
     * @param members 成员
     */
    public void removeFromSet(String key, String... members) {
        stringRedisTemplate.opsForSet().remove(key, members);
    }

    /**
     * 向集合追加成员并刷新过期时间。
     *
     * @param key     键
     * @param ttl     过期时间
     * @param members 成员
     */
    public void addToSetWithTtl(String key, Duration ttl, String... members) {
        stringRedisTemplate.opsForSet().add(key, members);
        stringRedisTemplate.expire(key, ttl);
    }

    /**
     * 读取集合全部成员。
     *
     * @param key 键
     * @return 成员集合;键不存在返回空集合
     */
    public Set<String> getSet(String key) {
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        return members == null ? Set.of() : members;
    }

    /**
     * 读取集合全部成员并转为 List。
     *
     * @param key 键
     * @return 成员列表;键不存在返回空列表
     */
    public List<String> getSetAsList(String key) {
        return List.copyOf(getSet(key));
    }

    /**
     * 判断集合是否包含指定成员。
     *
     * @param key    键
     * @param member 成员
     * @return true 表示包含
     */
    public boolean isInSet(String key, String member) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, member));
    }

    // ===== 哈希操作 =====

    /**
     * 写入哈希字段。
     *
     * @param key   键
     * @param field 字段
     * @param value 值
     */
    public void putHash(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 读取哈希字段。
     *
     * @param key   键
     * @param field 字段
     * @return 字段值;不存在返回 null
     */
    public String getHash(String key, String field) {
        Object value = stringRedisTemplate.opsForHash().get(key, field);
        return value == null ? null : value.toString();
    }

    /**
     * 读取整个哈希。
     *
     * @param key 键
     * @return 字段与值的映射;键不存在返回空 Map
     */
    public Map<String, String> getAllHash(String key) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v == null ? null : v.toString()));
        return result;
    }

    /**
     * 读取哈希全部字段名。
     *
     * @param key 键
     * @return 字段名集合;键不存在返回空集合
     */
    public Set<String> getHashFields(String key) {
        Set<Object> fields = stringRedisTemplate.opsForHash().keys(key);
        if (fields == null) {
            return Set.of();
        }
        return fields.stream().map(Object::toString).collect(Collectors.toSet());
    }
}
