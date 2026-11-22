package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MFA 第二步登录请求。
 *
 * @param mfaToken 口令认证通过后签发的一次性票据
 * @param code     动态口令(6 位)或恢复码
 */
public record MfaLoginRequest(
        @NotBlank(message = "票据不能为空") String mfaToken,
        @NotBlank(message = "动态口令不能为空") String code
) {
}
