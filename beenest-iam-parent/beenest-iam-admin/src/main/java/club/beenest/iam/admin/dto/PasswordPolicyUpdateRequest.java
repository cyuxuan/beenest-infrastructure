package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 口令策略更新请求。
 *
 * @param minLength        最小长度(4-128)
 * @param maxLength        最大长度(4-128)
 * @param requireUppercase 要求大写字母
 * @param requireLowercase 要求小写字母
 * @param requireDigit     要求数字
 * @param requireSymbol    要求特殊符号
 * @param historyCount     禁止重复使用最近 N 个口令(0-24)
 * @param expireDays       口令有效期天数(0 永不过期,1-3650)
 */
public record PasswordPolicyUpdateRequest(
        @NotNull(message = "最小长度不能为空") @Min(4) @Max(128) Integer minLength,
        @NotNull(message = "最大长度不能为空") @Min(4) @Max(128) Integer maxLength,
        Boolean requireUppercase,
        Boolean requireLowercase,
        Boolean requireDigit,
        Boolean requireSymbol,
        @Min(0) @Max(24) Integer historyCount,
        @Min(0) @Max(3650) Integer expireDays
) {
}
