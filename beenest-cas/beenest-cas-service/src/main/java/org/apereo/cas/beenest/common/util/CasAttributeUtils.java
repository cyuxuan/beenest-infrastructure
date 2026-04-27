package org.apereo.cas.beenest.common.util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAS 属性转换工具。
 * <p>
 * CAS Principal 默认使用多值属性模型，单个字段也会以 List 形式返回。
 * 对外 REST 接口为了便于客户端消费，需要将单值属性拍平成标量值，
 * 同时保留真正的多值属性。
 */
public final class CasAttributeUtils {

    private CasAttributeUtils() {
    }

    /**
     * 将 CAS 原始属性转换为“单值优先，多值保留”的返回结构。
     *
     * @param rawAttributes CAS Principal 原始属性
     * @return 拍平后的属性
     */
    public static Map<String, Object> flattenAttributes(Map<String, ?> rawAttributes) {
        Map<String, Object> flattenedAttributes = new LinkedHashMap<>();
        if (rawAttributes == null || rawAttributes.isEmpty()) {
            return flattenedAttributes;
        }

        rawAttributes.forEach((key, value) -> flattenedAttributes.put(key, normalizeAttributeValue(value)));
        return flattenedAttributes;
    }

    /**
     * 规范化单个属性值。
     *
     * @param value 原始属性值
     * @return 标量值或多值集合
     */
    public static Object normalizeAttributeValue(Object value) {
        // 1. Collection 是 CAS 属性最常见的承载形式，单元素时拍平，多元素时保留列表。
        if (value instanceof Collection<?> collection) {
            if (collection.isEmpty()) {
                return null;
            }

            List<Object> normalizedValues = collection.stream()
                    .map(CasAttributeUtils::normalizeAttributeValue)
                    .toList();
            return normalizedValues.size() == 1 ? normalizedValues.get(0) : normalizedValues;
        }

        // 2. 兼容数组属性，避免序列化时继续暴露为嵌套数组结构。
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length == 0) {
                return null;
            }

            List<Object> normalizedValues = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                normalizedValues.add(normalizeAttributeValue(Array.get(value, index)));
            }
            return normalizedValues.size() == 1 ? normalizedValues.get(0) : normalizedValues;
        }

        // 3. 标量值原样返回，由 Jackson 按真实类型序列化。
        return value;
    }
}
