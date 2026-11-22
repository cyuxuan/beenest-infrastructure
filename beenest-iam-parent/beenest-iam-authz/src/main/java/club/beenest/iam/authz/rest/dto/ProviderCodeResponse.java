package club.beenest.iam.authz.rest.dto;

/**
 * 凭据类登录响应:一个授权码。
 * <p>客户端拿它到标准令牌端点换令牌(grant_type=authorization_code),
 * 后续的签发、刷新、吊销全部走标准协议。</p>
 *
 * @param code        授权码
 * @param redirectUri 换令牌时必须携带的同一个回调地址
 */
public record ProviderCodeResponse(
        String code,
        String redirectUri
) {
}
