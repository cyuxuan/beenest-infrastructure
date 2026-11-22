package club.beenest.iam.console.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MFA 绑定确认请求。
 *
 * @param code authenticator 当前生成的 6 位动态口令
 */
public record MfaEnrollRequest(
        @NotBlank(message = "动态口令不能为空") String code
) {
}
