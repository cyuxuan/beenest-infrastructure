package club.beenest.iam.authz.rest.dto;

/**
 * MFA 第二步登录成功响应。
 *
 * @param user        登录用户信息与权限集
 * @param redirectUrl 回跳地址(取登录前保存的原始请求,无则回落 /)
 */
public record MfaLoginResponse(CurrentUserResponse user, String redirectUrl) {
}
