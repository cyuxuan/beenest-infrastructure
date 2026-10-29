package club.beenest.payment.common.utils;

import org.springframework.util.StringUtils;

/**
 * 手机号规范化工具。
 * <p>
 * 支付侧用手机号作为钱包 {@code customer_no}（IAM 文档推荐的跨渠道归并锚点），
 * 因此同一个号码必须只产生一种写法：不同渠道、不同客户端可能给出
 * {@code +86 138-0000-0000}、{@code 008613800000000}、{@code 13800000000} 等形式，
 * 若不归一，同一用户会被开成多个钱包。
 *
 * <p>归一规则：去掉空格、横线、括号等分隔符与前导 {@code +}；
 * 去掉 {@code 0086} / {@code 86} 国际区号；结果必须为 6~20 位纯数字。</p>
 *
 * @since 2026-10-29
 */
public final class PhoneNumbers {

    /** 中国区号（不含前导 +） */
    private static final String CN_AREA_CODE = "86";

    /** 带 00 前缀的国际区号写法 */
    private static final String INTERNATIONAL_PREFIX = "0086";

    /** 归一后的最小长度 */
    private static final int MIN_LENGTH = 6;

    /** 归一后的最大长度 */
    private static final int MAX_LENGTH = 20;

    private PhoneNumbers() {
    }

    /**
     * 归一化手机号。
     *
     * @param raw 原始手机号，可为空
     * @return 归一后的纯数字手机号；为空或不合法时返回 null
     */
    public static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        // 1. 去掉分隔符与前导 +，只保留数字
        String digits = raw.trim()
                .replace("+", "")
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (!StringUtils.hasText(digits)) {
            return null;
        }

        // 2. 去掉国际区号：0086 优先于 86，避免把 86 开头的 11 位号码误裁
        if (digits.startsWith(INTERNATIONAL_PREFIX) && digits.length() > INTERNATIONAL_PREFIX.length()) {
            digits = digits.substring(INTERNATIONAL_PREFIX.length());
        } else if (digits.startsWith(CN_AREA_CODE) && digits.length() == 13) {
            digits = digits.substring(CN_AREA_CODE.length());
        }

        // 3. 长度与字符校验：不合格一律视为"没有手机号"，交由调用方决定拒绝还是放行
        if (digits.length() < MIN_LENGTH || digits.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) {
                return null;
            }
        }
        return digits;
    }

    /**
     * 判断是否是可用的手机号。
     *
     * @param raw 原始手机号
     * @return true 表示归一后合法
     */
    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }

    /**
     * 脱敏展示手机号（日志与审计用）。
     *
     * @param phone 归一后的手机号
     * @return 形如 {@code 138****0000} 的脱敏结果；无法脱敏时原样返回
     */
    public static String mask(String phone) {
        String normalized = normalize(phone);
        if (normalized == null) {
            return phone;
        }
        if (normalized.length() < 7) {
            return "****";
        }
        return normalized.substring(0, 3)
                + "****"
                + normalized.substring(normalized.length() - 4);
    }
}
