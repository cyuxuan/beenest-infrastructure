package club.beenest.iam.federation.provider;

import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OAuth2 授权码类提供方基类。
 * <p>子类只需要关注令牌/用户信息的获取方式;授权跳转地址由本类统一拼装。</p>
 */
public abstract class OAuth2IdentityProvider implements IdentityProvider<IdentityProviderConfig> {

    /** 提供方配置。 */
    protected final IdentityProviderConfig config;

    protected OAuth2IdentityProvider(IdentityProviderConfig config) {
        this.config = config;
    }

    @Override
    public IdentityProviderConfig getConfig() {
        return config;
    }

    @Override
    public String authorizationUrl(AuthorizationRequest request) {
        // 1. 授权端点与 client_id 为必填
        String authorizeEndpoint = config.require("authorizationUrl");
        String clientId = config.require("clientId");
        // 2. 拼接标准授权码请求参数
        StringBuilder url = new StringBuilder(authorizeEndpoint);
        url.append(appendQueryParam("", "client_id", clientId))
                .append(appendQueryParam("&", "redirect_uri", request.redirectUri()))
                .append(appendQueryParam("&", "response_type", "code"))
                .append(appendQueryParam("&", "scope", defaultScope()))
                .append(appendQueryParam("&", "state", request.state()));
        // 3. 附加各提供方特有参数(如微信的 appid 已收敛为统一 client_id)
        for (Map.Entry<String, String> entry : request.extraParams().entrySet()) {
            url.append(appendQueryParam("&", entry.getKey(), entry.getValue()));
        }
        return url.toString();
    }

    /**
     * 默认请求的授权范围:优先取配置项 scope,未配置时为 openid profile email。
     *
     * @return 授权范围串
     */
    protected String defaultScope() {
        String scope = config.get("scope");
        return (scope == null || scope.isBlank()) ? "openid profile email" : scope;
    }

    @Override
    public abstract BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx);

    /**
     * 拼接 URL 查询参数(UTF-8 编码);值为空时返回空串。
     *
     * @param prefix 分隔符(? 或 &amp;)
     * @param name   参数名
     * @param value  参数值,可为空
     * @return {@code prefix + name=value} 形态的片段;值为空时返回空串
     */
    protected static String appendQueryParam(String prefix, String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return prefix + URLEncoder.encode(name, StandardCharsets.UTF_8.name())
                    + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 必然可用,此处不会发生
            throw new IllegalStateException(e);
        }
    }
}
