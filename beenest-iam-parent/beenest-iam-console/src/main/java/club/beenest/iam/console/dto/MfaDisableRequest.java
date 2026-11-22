package club.beenest.iam.console.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MFA 关闭请求。
 *
 * @param code 动态口令或恢复码(二选一通过即可)
 */
public record MfaDisableRequest(
        @NotBlank(message = "动态口令或恢复码不能为空") String code
) {
}
