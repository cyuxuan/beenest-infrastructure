package club.beenest.payment.util;

import club.beenest.payment.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 支付参数校验工具类
 * 提供链式参数校验方法，校验失败时抛出 IllegalArgumentException 或 BusinessException
 *
 * <p>使用示例：</p>
 * <pre>
 *   PaymentValidateUtils.notBlank(customerNo, "用户编号不能为空");
 *   PaymentValidateUtils.positive(amount, "金额必须大于0");
 *   PaymentValidateUtils.inRange(amount, 100L, 10000000L, "金额需在1元至10万元之间");
 *   PaymentValidateUtils.businessCheck(balance >= deductAmount, "余额不足");
 * </pre>
 *
 * @author System
 * @since 2026-01-26
 */
public final class PaymentValidateUtils {

    private PaymentValidateUtils() {
    }

    /**
     * 校验字符串非空
     *
     * @param value   待校验字符串
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果字符串为 null 或空白
     */
    public static void notBlank(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验字符串非空（延迟构造错误信息）
     *
     * @param value           待校验字符串
     * @param messageSupplier 延迟构造错误信息的 Supplier
     * @throws IllegalArgumentException 如果字符串为 null 或空白
     */
    public static void notBlank(String value, Supplier<String> messageSupplier) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(messageSupplier.get());
        }
    }

    /**
     * 校验对象非 null
     *
     * @param value   待校验对象
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果对象为 null
     */
    public static void notNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验对象非 null（延迟构造错误信息）
     *
     * @param value           待校验对象
     * @param messageSupplier 延迟构造错误信息的 Supplier
     * @throws IllegalArgumentException 如果对象为 null
     */
    public static void notNull(Object value, Supplier<String> messageSupplier) {
        if (value == null) {
            throw new IllegalArgumentException(messageSupplier.get());
        }
    }

    /**
     * 校验集合非空
     *
     * @param collection 待校验集合
     * @param message    校验失败时的错误信息
     * @throws IllegalArgumentException 如果集合为 null 或为空
     */
    public static void notEmpty(Collection<?> collection, String message) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 Map 非空
     *
     * @param map     待校验 Map
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果 Map 为 null 或为空
     */
    public static void notEmpty(Map<?, ?> map, String message) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验表达式为 true
     *
     * @param expression 待校验表达式
     * @param message    校验失败时的错误信息
     * @throws IllegalArgumentException 如果表达式为 false
     */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验表达式为 true（延迟构造错误信息）
     *
     * @param expression      待校验表达式
     * @param messageSupplier 延迟构造错误信息的 Supplier
     * @throws IllegalArgumentException 如果表达式为 false
     */
    public static void isTrue(boolean expression, Supplier<String> messageSupplier) {
        if (!expression) {
            throw new IllegalArgumentException(messageSupplier.get());
        }
    }

    /**
     * 校验表达式为 false
     *
     * @param expression 待校验表达式
     * @param message    校验失败时的错误信息
     * @throws IllegalArgumentException 如果表达式为 true
     */
    public static void isFalse(boolean expression, String message) {
        if (expression) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 Long 值为正数
     *
     * @param value   待校验值
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果值为 null 或不大于0
     */
    public static void positive(Long value, String message) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 BigDecimal 值为正数
     *
     * @param value   待校验值
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果值为 null 或不大于0
     */
    public static void positive(BigDecimal value, String message) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 Long 值非负
     *
     * @param value   待校验值
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果值为 null 或小于0
     */
    public static void nonNegative(Long value, String message) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 BigDecimal 值非负
     *
     * @param value   待校验值
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果值为 null 或小于0
     */
    public static void nonNegative(BigDecimal value, String message) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 Long 值在指定范围内
     *
     * @param value   待校验值
     * @param min     最小值（包含）
     * @param max     最大值（包含）
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果值为 null 或不在范围内
     */
    public static void inRange(Long value, Long min, Long max, String message) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验 BigDecimal 值在指定范围内
     *
     * @param value   待校验值
     * @param min     最小值（包含）
     * @param max     最大值（包含）
     * @param message 校验失败时的错误信息
     * @throws IllegalArgumentException 如果值为 null 或不在范围内
     */
    public static void inRange(BigDecimal value, BigDecimal min, BigDecimal max, String message) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 校验两个对象相等
     *
     * @param expected 期望值
     * @param actual   实际值
     * @param message  校验失败时的错误信息
     * @throws IllegalArgumentException 如果两个对象不相等
     */
    public static void equals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 业务规则校验，校验失败时抛出 BusinessException
     *
     * @param expression 待校验表达式
     * @param message    校验失败时的错误信息
     * @throws BusinessException 如果表达式为 false
     */
    public static void businessCheck(boolean expression, String message) {
        if (!expression) {
            throw new BusinessException(message);
        }
    }

    /**
     * 业务规则校验（延迟构造错误信息），校验失败时抛出 BusinessException
     *
     * @param expression      待校验表达式
     * @param messageSupplier 延迟构造错误信息的 Supplier
     * @throws BusinessException 如果表达式为 false
     */
    public static void businessCheck(boolean expression, Supplier<String> messageSupplier) {
        if (!expression) {
            throw new BusinessException(messageSupplier.get());
        }
    }
}
