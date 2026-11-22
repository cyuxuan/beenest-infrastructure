package club.beenest.iam.common.i18n;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 语言解析器。
 * <p>解析顺序:显式选择({@code ?lang=}) → Cookie → 浏览器偏好 → 默认中文。
 * 支持中文与英文。</p>
 */
public class IamLocaleResolver implements LocaleResolver {

    /** 语言 Cookie 名 */
    public static final String COOKIE_NAME = "IAM_LOCALE";

    /** 支持的语言标签(小写比较) */
    private static final Set<String> SUPPORTED = Set.of("zh", "zh-cn", "en", "en-us");

    /** Cookie 有效期:语言偏好不是敏感信息,给长一点,避免频繁重选 */
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(365);

    /** 默认语言 */
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /**
     * 解析本次请求的语言。
     *
     * @param request 当前请求
     * @return 解析出的语言;任何环节缺失都回落到中文
     */
    @Override
    @NonNull
    public Locale resolveLocale(@NonNull HttpServletRequest request) {
        Locale fromCookie = parseTag(readCookie(request));
        if (fromCookie != null) {
            return fromCookie;
        }
        Locale fromHeader = request.getLocale();
        if (fromHeader != null && "en".equalsIgnoreCase(fromHeader.getLanguage())) {
            return Locale.US;
        }
        return DEFAULT_LOCALE;
    }

    /**
     * 记录用户显式选择的语言(写入 Cookie)。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param locale   目标语言;为 null 表示不做改变
     */
    @Override
    public void setLocale(@NonNull HttpServletRequest request, @Nullable HttpServletResponse response,
                          @Nullable Locale locale) {
        if (response == null || locale == null) {
            return;
        }
        String tag = toTag(locale);
        if (tag == null) {
            return;
        }
        Cookie cookie = new Cookie(COOKIE_NAME, tag);
        cookie.setPath("/");
        cookie.setMaxAge((int) COOKIE_MAX_AGE.toSeconds());
        // 语言偏好不需要 JS 读取;Secure 交给容器按实际协议决定
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }

    /**
     * 语言标签 → 支持的语言;不支持返回 null。
     *
     * @param tag 语言标签(如 {@code en}、{@code zh-CN})
     * @return 对应语言;不支持时为 null
     */
    public static Locale parseTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!SUPPORTED.contains(normalized)) {
            return null;
        }
        return normalized.startsWith("en") ? Locale.US : DEFAULT_LOCALE;
    }

    /**
     * 语言 → 存储用的标签。
     *
     * @param locale 语言
     * @return 标签;非中英语言返回 null(不写入 Cookie)
     */
    public static String toTag(Locale locale) {
        if (locale == null) {
            return null;
        }
        if ("en".equalsIgnoreCase(locale.getLanguage())) {
            return "en";
        }
        if ("zh".equalsIgnoreCase(locale.getLanguage())) {
            return "zh-CN";
        }
        return null;
    }

    /**
     * 读取语言 Cookie。
     *
     * @param request 当前请求
     * @return Cookie 中的语言标签;不存在返回 null
     */
    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
