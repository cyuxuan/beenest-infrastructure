package club.beenest.iam.provider.oidc;

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
 * 通用 OIDC 提供方:任何符合 OIDC 规范的三方(如 GitHub / Google / 企业自建 IdP)。
 * <p>授权端点、令牌端点、用户信息端点均来自提供方配置,无需为每家三方单独写代码。</p>
 */
public class GenericOidcIdentityProvider extends OAuth2IdentityProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GenericOidcIdentityProvider(IdentityProviderConfig config, ObjectMapper objectMapper) {
        super(config);
        this.objectMapper = objectMapper;
    }

    @Override
    public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
        // 1. 回调缺少授权码无法继续
        if (ctx.code() == null || ctx.code().isBlank()) {
            throw new IllegalStateException("OIDC 回调缺少授权码");
        }
        // 2. 用授权码换访问令牌
        Map<String, Object> tokenResponse = requestToken(ctx);
        String accessToken = String.valueOf(tokenResponse.get("access_token"));
        // 3. 用访问令牌读取用户信息端点
        Map<String, Object> userInfo = requestUserInfo(accessToken);
        // 4. 组装经纪身份上下文
        String subject = text(userInfo, "sub", "id");
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("OIDC 用户信息缺少 sub");
        }
        BrokeredIdentityContext identity =
                new BrokeredIdentityContext(subject, config.providerId(), config.alias());
        identity.setUsername(text(userInfo, "preferred_username", "login", "name"));
        identity.setEmail(text(userInfo, "email"));
        identity.setNickname(text(userInfo, "name", "nickname"));
        if (Boolean.TRUE.equals(userInfo.get("email_verified"))) {
            identity.setAttribute("email_verified", "true");
        }
        // 5. 原始属性全量带回,供提供方侧属性映射规则读取任意来源属性
        userInfo.forEach((key, value) -> {
            if (value != null) {
                identity.setAttribute(key, stringify(value));
            }
        });
        return identity;
    }

    /**
     * 授权码换令牌(POST 表单)。
     * <p>redirect_uri 与授权请求不一致时,令牌端点拒绝请求。</p>
     *
     * @param ctx 回调上下文,提供授权码与原回调地址
     * @return 令牌响应的 JSON 结构
     * @throws IllegalStateException 令牌请求失败
     */
    private Map<String, Object> requestToken(CallbackContext ctx) {
        String tokenEndpoint = config.require("tokenUrl");
        String clientId = config.require("clientId");
        String clientSecret = config.require("clientSecret");
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(ctx.code())
                + "&redirect_uri=" + urlEncode(ctx.redirectUri())
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        String body = postForm(tokenEndpoint, form);
        return parseJson(body);
    }

    /**
     * 携带访问令牌读取用户信息端点(GET)。
     *
     * @param accessToken 访问令牌
     * @return 用户信息响应的 JSON 结构
     * @throws IllegalStateException 请求失败
     */
    private Map<String, Object> requestUserInfo(String accessToken) {
        String userInfoEndpoint = config.require("userInfoUrl");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(userInfoEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OIDC 用户信息请求失败,HTTP " + response.statusCode());
            }
            return parseJson(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OIDC 用户信息请求异常", e);
        }
    }

    /**
     * POST 表单并返回响应体。
     *
     * @param url  端点地址
     * @param form urlencoded 表单串
     * @return 响应体文本
     * @throws IllegalStateException 非 2xx 状态码或 IO 异常
     */
    private String postForm(String url, String form) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OIDC 令牌请求失败,HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("OIDC 令牌请求异常", e);
        }
    }

    /**
     * 解析 JSON 响应。
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
            throw new IllegalStateException("OIDC 响应不是合法 JSON", e);
        }
    }

    /**
     * 依次尝试多个候选键,取第一个非空值。
     *
     * @param map  JSON 结构
     * @param keys 候选键,按优先级排列
     * @return 首个非空值的字符串形态;全部缺失时返回 null
     */
    private static String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * 属性值转字符串。
     * <p>集合与对象序列化为 JSON 文本:外部 IdP 的数组属性(如 roles)按 JSON
     * 形态保留,属性映射按值匹配时对得上。</p>
     *
     * @param value 属性值
     * @return 字符串形态;序列化失败时回落 toString
     */
    private String stringify(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Iterable<?> || value instanceof Map<?, ?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    /**
     * URL 编码(UTF-8)。
     *
     * @param value 原值
     * @return 编码结果;入参为 null 时返回空串
     */
    private static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
