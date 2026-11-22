package club.beenest.iam.core.spi.federation;

import java.util.Map;

/**
 * 凭据上下文:调用方提交的凭据与回调参数。
 * <p>code/state/redirectUri 为标准 OAuth2 参数;提供方额外需要的凭据放在
 * {@code credentials} 中,键名由提供方定义,如小程序登录的 code、自建票据的 ticket。</p>
 *
 * @param code        浏览器授权码(跳转类提供方使用)
 * @param state       回传状态,用于校验防伪值
 * @param realmKey    身份域
 * @param redirectUri 发起授权时使用的回调地址(令牌交换需与授权请求一致)
 * @param credentials 提供方自定的凭据键值
 */
public record CallbackContext(
        String code,
        String state,
        String realmKey,
        String redirectUri,
        Map<String, String> credentials
) {

    /**
     * 取一个提供方自定的凭据。
     *
     * @param name 凭据名
     * @return 凭据值;未提供时返回 null
     */
    public String credential(String name) {
        if (credentials == null || name == null) {
            return null;
        }
        return credentials.get(name);
    }
}
