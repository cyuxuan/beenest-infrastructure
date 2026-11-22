package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 短信验证码登录请求(手机号 + 验证码,独立第一因素)。
 *
 * @param phone          手机号
 * @param code           短信验证码
 * @param challengeToken 准入挑战令牌(补验场景由挑战页带回,普通登录为空)
 */
public record SmsLoginRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "验证码不能为空")
        @Size(min = 4, max = 10, message = "验证码长度不正确")
        String code,

        String challengeToken
) {
}
