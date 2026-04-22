package club.beenest.payment.util;

import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Map 取值工具类
 * 提供类型安全的 Map 值提取方法，替代散落在各 ServiceImpl 中的 private 工具方法。
 *
 * @author System
 * @since 2026-04-22
 */
public final class MapValueUtils {

    private MapValueUtils() {
    }

    /**
     * 安全地将 Map 中的值转为 String
     *
     * @param map   数据源
     * @param key   键名
     * @return 字符串值，null 或不存在时返回 null
     */
    public static String stringValue(Map<String, ?> map, String key) {
        if (map == null) {
            return null;
        }
        return stringValue(map.get(key));
    }

    /**
     * 安全地将 Object 转为 String
     *
     * @param value 原始值
     * @return 字符串表示，null 时返回 null
     */
    public static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 安全地将 Map 中的值转为 Long
     *
     * @param map   数据源
     * @param key   键名
     * @return Long 值，null 或转换失败时返回 null
     */
    public static Long longValue(Map<String, ?> map, String key) {
        if (map == null) {
            return null;
        }
        return longValue(map.get(key));
    }

    /**
     * 安全地将 Object 转为 Long
     *
     * @param value 原始值
     * @return Long 值，null 或转换失败时返回 null
     */
    public static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 返回第一个非空白字符串
     *
     * @param values 候选值列表
     * @return 第一个非空白的值，全部为空时返回 null
     */
    public static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
