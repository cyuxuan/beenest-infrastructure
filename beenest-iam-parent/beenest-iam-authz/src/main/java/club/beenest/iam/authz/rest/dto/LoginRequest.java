package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 账号密码登录请求。
 * <p>身份域通过查询参数 {@code realm} 指定(缺省 default),与登录页保持一致;
 * captchaId/captchaCode 在失败次数达到域策略阈值后必填。</p>
 *
 * @param username       登录名
 * @param password       明文口令
 * @param captchaId      验证码标识
 * @param captchaCode    验证码答案
 * @param challengeToken 准入挑战令牌(补验场景由挑战页带回,普通登录为空)
 */
public record LoginRequest(
        @NotBlank(message = "登录名不能为空") String username,
        @NotBlank(message = "口令不能为空") String password,
        String captchaId,
        String captchaCode,
        String challengeToken
) {
}
