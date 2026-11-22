package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/**
 * 凭据类登录请求。
 * <p>客户端把第三方凭据与自己的客户端凭据一起上送,平台换回一个授权码。
 * 凭据的键名由提供方自己定义(小程序是 jsCode),平台原样转交。</p>
 *
 * @param provider     提供方别名
 * @param credentials  第三方凭据(键名由提供方定义)
 * @param clientId     客户端标识
 * @param clientSecret 客户端密钥
 * @param codeChallenge PKCE 挑战值(客户端要求 PKCE 时必填)
 */
public record ProviderCodeRequest(
        @NotBlank(message = "提供方别名不能为空") String provider,
        @NotEmpty(message = "第三方凭据不能为空") Map<String, String> credentials,
        @NotBlank(message = "客户端标识不能为空") String clientId,
        @NotBlank(message = "客户端密钥不能为空") String clientSecret,
        String codeChallenge
) {
}
