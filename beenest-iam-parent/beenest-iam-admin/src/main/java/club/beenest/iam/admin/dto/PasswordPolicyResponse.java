package club.beenest.iam.admin.dto;

/**
 * 口令策略响应。
 *
 * @param minLength        最小长度
 * @param maxLength        最大长度
 * @param requireUppercase 要求大写字母
 * @param requireLowercase 要求小写字母
 * @param requireDigit     要求数字
 * @param requireSymbol    要求特殊符号
 * @param historyCount     禁止重复使用最近 N 个口令
 * @param expireDays       口令有效期天数(0 永不过期)
 */
public record PasswordPolicyResponse(
        Integer minLength,
        Integer maxLength,
        Boolean requireUppercase,
        Boolean requireLowercase,
        Boolean requireDigit,
        Boolean requireSymbol,
        Integer historyCount,
        Integer expireDays
) {
}
