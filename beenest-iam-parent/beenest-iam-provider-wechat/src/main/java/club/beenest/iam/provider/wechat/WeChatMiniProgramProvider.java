package club.beenest.iam.provider.wechat;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信小程序登录提供方。
 * <p>小程序没有浏览器跳转:客户端把 wx.login() 拿到的 code 上送平台,
 * 平台拿 code 换微信侧身份(openid/unionid),再交给联邦运行时换成本地用户。</p>
 * <p>外部标识取 unionid,缺失时回落到 openid:同一微信用户在不同入口下的 openid 不同、
 * unionid 相同;已绑定开放平台账号的用户才有 unionid,凭它可以跨入口识别同一人。</p>
 * <p>手机号由 {@code phoneCode}(手机号快速验证组件的回调 code)换取,只从微信服务端取得:
 * 平台据此把账号与已验证手机号关联,客户端的其它字段不参与该判定。</p>
 * <p>所需凭据由本类自己定义:键名 {@code jsCode} 是小程序 wx.login() 的一次性 code,
 * {@code phoneCode} 是手机号授权的一次性 code。</p>
 */
public class WeChatMiniProgramProvider implements IdentityProvider<IdentityProviderConfig> {

    /** 客户端提交的一次性 code 的键名:由本提供方定义,平台原样转交。 */
    public static final String CREDENTIAL_JS_CODE = "jsCode";

    /** 手机号授权回调 code 的键名:由本提供方定义,平台原样转交。 */
    public static final String CREDENTIAL_PHONE_CODE = "phoneCode";

    /** 是否必须带手机号授权的配置键,缺省为 true。 */
    private static final String CONFIG_PHONE_LOGIN_REQUIRED = "phoneLoginRequired";

    /** 微信默认接口基址。 */
    private static final String DEFAULT_API_BASE_URL = "https://api.weixin.qq.com";
    /** code2session 路径。 */
    private static final String CODE2SESSION_PATH = "/sns/jscode2session";
    /** 稳定版接口调用凭据路径:多实例各自获取不会互相顶掉。 */
    private static final String STABLE_TOKEN_PATH = "/cgi-bin/stable_token";
    /** 手机号换取路径。 */
    private static final String PHONE_NUMBER_PATH = "/wxa/business/getuserphonenumber";
    /** 外部调用超时(秒)。 */
    private static final int TIMEOUT_SECONDS = 10;
    /** 接口凭据提前刷新的余量:避免临界时刻拿着将要过期的令牌出网。 */
    private static final long TOKEN_REFRESH_AHEAD_MILLIS = Duration.ofMinutes(5).toMillis();
    /** 接口凭据未返回有效期时的兜底值(秒)。 */
    private static final long DEFAULT_TOKEN_TTL_SECONDS = 7200;
    /** 需要重新获取接口凭据的错误码:凭据无效、已过期、不合法。 */
    private static final Set<Object> TOKEN_INVALID_ERRCODES = Set.of(40001, 40014, 42001);
    /** 提交的登录 code 本身无效或已被使用的错误码。 */
    private static final Set<Object> CLIENT_ERRCODES = Set.of(40029, 40163);

    /**
     * 接口凭据缓存:按 接口基址 + AppID 分键。
     * <p>工厂每次登录都创建新的提供方实例,JVM 级缓存才能在请求之间复用;
     * 同一进程内多个 AppID 各用各的令牌。</p>
     */
    private static final Map<String, CachedToken> TOKEN_CACHE = new ConcurrentHashMap<>();

    private final IdentityProviderConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    public WeChatMiniProgramProvider(IdentityProviderConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public IdentityProviderConfig getConfig() {
        return config;
    }

    @Override
    public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
        // 1. 凭据由本提供方定义:jsCode 必填,phoneCode 按配置必填
        String jsCode = ctx.credential(CREDENTIAL_JS_CODE);
        if (jsCode == null || jsCode.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "小程序登录缺少 jsCode");
        }
        String phoneCode = ctx.credential(CREDENTIAL_PHONE_CODE);
        boolean phoneMissing = phoneCode == null || phoneCode.isBlank();
        if (phoneMissing && phoneLoginRequired()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "小程序登录缺少 phoneCode:需要用户授权手机号后再登录");
        }
        // 2. 调微信接口换身份
        Map<String, Object> response = requestCode2Session(jsCode);
        // 3. 失败以 errcode 表示而非 HTTP 状态码,须先于解析判定
        Object errcode = errorCode(response);
        if (errcode != null && CLIENT_ERRCODES.contains(errcode)) {
            // 提交的 code 无效或已被使用:属于客户端凭据问题,按认证失败回给客户端
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "微信小程序登录凭证已失效: errcode=" + errcode + ", errmsg=" + response.get("errmsg"));
        }
        if (errcode != null) {
            // 其余错误指向平台配置或微信侧故障,不能报成用户的操作问题
            throw new IllegalStateException("微信小程序换票失败: errcode=" + errcode
                    + ", errmsg=" + response.get("errmsg"));
        }
        String openid = text(response, "openid");
        if (openid == null || openid.isBlank()) {
            throw new IllegalStateException("微信小程序换票失败: 响应缺少 openid");
        }
        String unionid = text(response, "unionid");
        // 4. 外部标识:优先 unionid,缺失回落 openid
        String externalId = (unionid == null || unionid.isBlank()) ? openid : unionid;
        BrokeredIdentityContext identity = new BrokeredIdentityContext(externalId, config.providerId(), config.alias());
        identity.setAttribute("openid", openid);
        if (unionid != null && !unionid.isBlank()) {
            identity.setAttribute("unionid", unionid);
        }
        // 5. 手机号只由服务端用 phoneCode 换取,客户端上送的手机号不采信
        if (!phoneMissing) {
            identity.setVerifiedPhone(requestPhoneNumber(phoneCode.trim()));
        }
        // 6. 会话密钥(session_key)不落库:仅用于加密通信的临时凭据
        return identity;
    }

    /**
     * 用手机号授权 code 换取手机号。
     * <p>接口凭据失效时清缓存重试一次:管理员更换 AppSecret 后不必重启进程。</p>
     *
     * @param phoneCode 手机号快速验证组件的回调 code
     * @return 纯手机号(不含国家码)
     * @throws IllegalStateException 微信返回错误或响应缺少手机号
     */
    private String requestPhoneNumber(String phoneCode) {
        String cacheKey = tokenCacheKey();
        Map<String, Object> response = postPhoneNumber(phoneCode, accessToken(cacheKey));
        Object errcode = errorCode(response);
        if (errcode != null && TOKEN_INVALID_ERRCODES.contains(errcode)) {
            TOKEN_CACHE.remove(cacheKey);
            response = postPhoneNumber(phoneCode, accessToken(cacheKey));
            errcode = errorCode(response);
        }
        if (errcode != null) {
            throw new IllegalStateException("微信手机号换取失败: errcode=" + errcode
                    + ", errmsg=" + response.get("errmsg"));
        }
        Object info = response.get("phone_info");
        if (!(info instanceof Map<?, ?> phoneInfo)) {
            throw new IllegalStateException("微信手机号换取失败: 响应缺少 phone_info");
        }
        String phone = text(phoneInfo, "purePhoneNumber");
        if (phone == null || phone.isBlank()) {
            phone = text(phoneInfo, "phoneNumber");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalStateException("微信手机号换取失败: 响应缺少手机号");
        }
        return phone;
    }

    /**
     * 调用手机号换取接口。
     *
     * @param phoneCode 手机号授权 code
     * @param token     接口调用凭据
     * @return 接口响应的 JSON 结构
     */
    private Map<String, Object> postPhoneNumber(String phoneCode, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", phoneCode);
        return postJson(PHONE_NUMBER_PATH + "?access_token=" + encode(token), body, "微信手机号换取");
    }

    /**
     * 取接口调用凭据。
     * <p>缓存命中且未进入提前刷新窗口时直接复用;否则在类级锁内取一次,
     * 并发请求不会同时打到微信接口。</p>
     *
     * @param cacheKey 缓存键(接口基址 + AppID)
     * @return 接口调用凭据
     */
    private String accessToken(String cacheKey) {
        CachedToken cached = TOKEN_CACHE.get(cacheKey);
        if (isFresh(cached)) {
            return cached.value();
        }
        synchronized (TOKEN_CACHE) {
            cached = TOKEN_CACHE.get(cacheKey);
            if (isFresh(cached)) {
                return cached.value();
            }
            CachedToken fresh = requestStableToken();
            TOKEN_CACHE.put(cacheKey, fresh);
            return fresh.value();
        }
    }

    /**
     * 调用稳定版接口凭据接口。
     * <p>稳定版令牌不会被后续获取顶掉,多实例各自持有互不影响。</p>
     *
     * @return 新的缓存项
     * @throws IllegalStateException 微信返回错误或响应缺少凭据
     */
    private CachedToken requestStableToken() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grant_type", "client_credential");
        body.put("appid", config.require("clientId"));
        body.put("secret", config.require("clientSecret"));
        body.put("force_refresh", false);
        Map<String, Object> response = postJson(STABLE_TOKEN_PATH, body, "微信接口凭据获取");
        Object errcode = errorCode(response);
        if (errcode != null) {
            throw new IllegalStateException("微信接口凭据获取失败: errcode=" + errcode
                    + ", errmsg=" + response.get("errmsg"));
        }
        String token = text(response, "access_token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("微信接口凭据获取失败: 响应缺少 access_token");
        }
        Object expiresIn = response.get("expires_in");
        long ttlSeconds = expiresIn instanceof Number number && number.longValue() > 0
                ? number.longValue() : DEFAULT_TOKEN_TTL_SECONDS;
        return new CachedToken(token, System.currentTimeMillis() + ttlSeconds * 1000);
    }

    /**
     * 以 JSON 报文发起 POST 请求。
     *
     * @param path   相对接口基址的路径,含查询串
     * @param body   请求体
     * @param action 动作名,用于错误信息
     * @return 接口响应的 JSON 结构
     * @throws IllegalStateException 请求失败或响应非合法 JSON
     */
    private Map<String, Object> postJson(String path, Map<String, Object> body, String action) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(action + "请求构造失败", e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl() + path))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(action + "请求失败,HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(action + "请求异常", e);
        }
    }

    /**
     * 是否要求手机号授权,缺省为 true。
     *
     * @return true 表示缺少 phoneCode 时拒绝登录
     */
    private boolean phoneLoginRequired() {
        String value = config.get(CONFIG_PHONE_LOGIN_REQUIRED);
        return value == null || value.isBlank() || Boolean.parseBoolean(value.trim());
    }

    /**
     * 接口凭据的缓存键。
     *
     * @return 接口基址与 AppID 组成的键
     */
    private String tokenCacheKey() {
        return apiBaseUrl() + "|" + config.require("clientId");
    }

    /**
     * 缓存项是否仍在有效期内(留出提前刷新余量)。
     *
     * @param token 缓存项,可为 null
     * @return true 表示可直接复用
     */
    private static boolean isFresh(CachedToken token) {
        return token != null
                && System.currentTimeMillis() < token.expiresAtMillis() - TOKEN_REFRESH_AHEAD_MILLIS;
    }

    /** 接口调用凭据缓存项:值 + 到期时间戳。 */
    private record CachedToken(String value, long expiresAtMillis) {
    }

    /**
     * 调用 code2session 接口换取微信侧身份。
     *
     * @param jsCode 小程序 wx.login() 的一次性 code
     * @return 接口响应的 JSON 结构
     * @throws IllegalStateException 请求失败或响应非合法 JSON
     */
    private Map<String, Object> requestCode2Session(String jsCode) {
        String url = String.format("%s%s?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                apiBaseUrl(), CODE2SESSION_PATH,
                encode(config.require("clientId")), encode(config.require("clientSecret")), encode(jsCode));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("微信小程序换票请求失败,HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("微信小程序换票请求异常", e);
        }
    }

    /**
     * 接口基址:配置项优先,缺省用微信公网地址。
     *
     * @return 不带结尾斜杠的基址
     */
    private String apiBaseUrl() {
        String base = config.get("apiBaseUrl");
        return (base == null || base.isBlank()) ? DEFAULT_API_BASE_URL : trimTrailingSlash(base.trim());
    }

    /**
     * 去掉结尾斜杠。
     *
     * @param value 原值
     * @return 去尾斜杠后的值
     */
    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * URL 编码(UTF-8)。
     *
     * @param value 原值
     * @return 编码结果;入参为 null 时返回空串
     */
    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 读取接口的失败错误码。
     * <p>微信接口成功时并非一律省略 errcode:手机号接口成功会返回 {@code errcode=0}。
     * 只有非零值表示失败,缺少 errcode 也算成功。</p>
     *
     * @param response 接口响应的 JSON 结构
     * @return 失败时的错误码;成功返回 null
     */
    private static Object errorCode(Map<?, ?> response) {
        Object errcode = response.get("errcode");
        if (errcode == null) {
            return null;
        }
        return "0".equals(String.valueOf(errcode).trim()) ? null : errcode;
    }

    /**
     * 按 key 读取字符串值。
     *
     * @param map JSON 结构
     * @param key 目标键
     * @return 值的字符串形态;键不存在时返回 null
     */
    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
