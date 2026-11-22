package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.federation.provider.OAuth2IdentityProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信开放平台扫码登录提供方。
 * <p>浏览器跳到微信的扫码授权页,微信渲染二维码,用户扫一扫授权后
 * 微信回调本平台的 broker 回调地址,平台用授权码换 openid 再取昵称头像。</p>
 * <p>授权参数按位置解析:appid 必须是第一个参数,地址末尾必须带 #wechat_redirect
 * 片段,缺少任一条微信不渲染二维码。</p>
 */
public class WeChatWebProvider extends OAuth2IdentityProvider {

    /** 扫码授权地址默认值。 */
    private static final String DEFAULT_AUTHORIZATION_URL = "https://open.weixin.qq.com/connect/qrconnect";
    /** 令牌端点默认值。 */
    private static final String DEFAULT_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    /** 用户信息端点默认值。 */
    private static final String DEFAULT_USER_INFO_URL = "https://api.weixin.qq.com/sns/userinfo";
    /** 微信要求授权地址带的片段,没有它不渲染二维码。 */
    private static final String WECHAT_REDIRECT_FRAGMENT = "#wechat_redirect";
    /** 默认授权范围:snsapi_login 才会返回昵称头像。 */
    private static final String DEFAULT_SCOPE = "snsapi_login";
    /** 外部调用超时(秒)。 */
    private static final int TIMEOUT_SECONDS = 10;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    public WeChatWebProvider(IdentityProviderConfig config, ObjectMapper objectMapper) {
        super(config);
        this.objectMapper = objectMapper;
    }

    @Override
    public String authorizationUrl(AuthorizationRequest request) {
        // 1. 微信按位置解析参数:appid 必须排在最前
        StringBuilder url = new StringBuilder(authorizationEndpoint());
        url.append(appendQueryParam("?", "appid", config.require("clientId")));
        url.append(appendQueryParam("&", "redirect_uri", request.redirectUri()));
        url.append(appendQueryParam("&", "response_type", "code"));
        url.append(appendQueryParam("&", "scope", scope()));
        url.append(appendQueryParam("&", "state", request.state()));
        for (Map.Entry<String, String> entry : request.extraParams().entrySet()) {
            url.append(appendQueryParam("&", entry.getKey(), entry.getValue()));
        }
        // 2. 微信仅在地址末尾带 #wechat_redirect 片段时渲染二维码
        url.append(WECHAT_REDIRECT_FRAGMENT);
        return url.toString();
    }

    @Override
    public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
        // 1. 回调必须带授权码
        if (ctx.code() == null || ctx.code().isBlank()) {
            throw new IllegalStateException("微信扫码回调缺少授权码");
        }
        // 2. 用授权码换访问令牌与 openid
        Map<String, Object> token = requestToken(ctx);
        String openid = text(token, "openid");
        if (openid == null || openid.isBlank()) {
            throw new IllegalStateException("微信扫码换票失败: 响应缺少 openid");
        }
        String unionid = text(token, "unionid");
        // 3. 取用户信息(昵称头像需要用户授权 snsapi_login)
        Map<String, Object> userInfo = requestUserInfo(text(token, "access_token"), openid);
        // 4. 外部标识:优先 unionid,缺失回落 openid
        String externalId = (unionid == null || unionid.isBlank()) ? openid : unionid;
        BrokeredIdentityContext identity = new BrokeredIdentityContext(externalId, config.providerId(), config.alias());
        identity.setNickname(text(userInfo, "nickname"));
        identity.setAttribute("openid", openid);
        if (unionid != null && !unionid.isBlank()) {
            identity.setAttribute("unionid", unionid);
        }
        String avatar = text(userInfo, "headimgurl");
        if (avatar != null && !avatar.isBlank()) {
            identity.setAttribute("avatar", avatar);
        }
        return identity;
    }

    /**
     * 用授权码换访问令牌与 openid。
     *
     * @param ctx 回调上下文,提供授权码
     * @return 令牌接口响应的 JSON 结构
     * @throws IllegalStateException 令牌请求失败或响应非合法 JSON
     */
    private Map<String, Object> requestToken(CallbackContext ctx) {
        String url = String.format("%s?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
                tokenEndpoint(), encode(config.require("clientId")), encode(config.require("clientSecret")),
                encode(ctx.code()));
        return parseJson(get(url));
    }

    /**
     * 读取用户信息(昵称、头像)。
     *
     * @param accessToken 访问令牌
     * @param openid      用户在开放平台的标识
     * @return 用户信息接口的 JSON 结构
     * @throws IllegalStateException 请求失败或响应非合法 JSON
     */
    private Map<String, Object> requestUserInfo(String accessToken, String openid) {
        String url = String.format("%s?access_token=%s&openid=%s&lang=zh_CN",
                userInfoEndpoint(), encode(accessToken), encode(openid));
        return parseJson(get(url));
    }

    /**
     * 发起 GET 请求并返回响应体,超时 10 秒。
     *
     * @param url 完整请求地址
     * @return 响应体文本
     * @throws IllegalStateException 非 2xx 状态码或 IO 异常
     */
    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("微信接口请求失败,HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("微信接口请求异常", e);
        }
    }

    /**
     * 将 JSON 文本解析为 Map。
     *
     * @param body 响应体文本
     * @return JSON 结构
     * @throws IllegalStateException 文本非合法 JSON
     */
    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("微信接口响应不是合法 JSON", e);
        }
    }

    /**
     * 扫码授权端点:配置项优先,缺省用微信公网地址。
     *
     * @return 授权端点地址
     */
    private String authorizationEndpoint() {
        return endpoint("authorizationUrl", DEFAULT_AUTHORIZATION_URL);
    }

    /**
     * 令牌端点:配置项优先,缺省用微信公网地址。
     *
     * @return 令牌端点地址
     */
    private String tokenEndpoint() {
        return endpoint("tokenUrl", DEFAULT_TOKEN_URL);
    }

    /**
     * 用户信息端点:配置项优先,缺省用微信公网地址。
     *
     * @return 用户信息端点地址
     */
    private String userInfoEndpoint() {
        return endpoint("userInfoUrl", DEFAULT_USER_INFO_URL);
    }

    /**
     * 读取端点配置,空值回落默认地址。
     *
     * @param key      配置键
     * @param fallback 默认地址
     * @return 去空格后的端点地址
     */
    private String endpoint(String key, String fallback) {
        String value = config.get(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    /**
     * 请求的授权范围:配置项优先,缺省 snsapi_login(返回昵称头像的必需范围)。
     *
     * @return 授权范围
     */
    private String scope() {
        String value = config.get("scope");
        return (value == null || value.isBlank()) ? DEFAULT_SCOPE : value.trim();
    }

    /**
     * URL 编码(UTF-8)。
     *
     * @param value 原值
     * @return 编码结果;入参为 null 时返回空串
     */
    private static String encode(String value) {
        return value == null ? "" : java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 按 key 读取字符串值。
     *
     * @param map JSON 结构
     * @param key 目标键
     * @return 值的字符串形态;键不存在时返回 null
     */
    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
