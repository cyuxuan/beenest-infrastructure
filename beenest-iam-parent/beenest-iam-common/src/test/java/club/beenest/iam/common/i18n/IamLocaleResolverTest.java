package club.beenest.iam.common.i18n;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语言解析测试。
 * <p>解析顺序(显式选择 → Cookie → 浏览器偏好 → 默认中文)决定了用户看到的语言,
 * 顺序错了不会报错,只会让"选了英文下次又变回中文"这类问题悄悄发生。</p>
 */
@DisplayName("语言解析")
class IamLocaleResolverTest {

    private final IamLocaleResolver resolver = new IamLocaleResolver();

    @Test
    @DisplayName("Cookie 优先于浏览器偏好")
    void cookieWinsOverBrowserPreference() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setPreferredLocales(java.util.List.of(Locale.SIMPLIFIED_CHINESE));
        request.setCookies(new Cookie(IamLocaleResolver.COOKIE_NAME, "en"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.US);
    }

    @Test
    @DisplayName("无 Cookie 时按浏览器偏好:英文 → 英文,其它 → 中文")
    void fallsBackToBrowserPreference() {
        MockHttpServletRequest english = new MockHttpServletRequest();
        english.setPreferredLocales(java.util.List.of(Locale.UK));
        assertThat(resolver.resolveLocale(english)).isEqualTo(Locale.US);

        MockHttpServletRequest japanese = new MockHttpServletRequest();
        japanese.setPreferredLocales(java.util.List.of(Locale.JAPAN));
        assertThat(resolver.resolveLocale(japanese)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
    }

    @Test
    @DisplayName("Cookie 里是不支持的语言时忽略它,继续按浏览器偏好")
    void unsupportedCookieIsIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setPreferredLocales(java.util.List.of(Locale.US));
        request.setCookies(new Cookie(IamLocaleResolver.COOKIE_NAME, "fr"));

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.US);
    }

    @Test
    @DisplayName("写入 Cookie:路径为根、有效期一年、HttpOnly")
    void setLocaleWritesCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setLocale(request, response, Locale.US);

        Cookie cookie = response.getCookie(IamLocaleResolver.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("en");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(365 * 24 * 3600);
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    @DisplayName("写入 Cookie:语言为 null 或响应缺失时不做任何事")
    void setLocaleIgnoresMissingInput() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.setLocale(request, response, null);
        resolver.setLocale(request, null, Locale.US);

        assertThat(response.getCookies()).isEmpty();
    }

    @Test
    @DisplayName("标签解析:支持中英各种写法,其余返回 null")
    void parseTagAcceptsOnlySupportedTags() {
        assertThat(IamLocaleResolver.parseTag("en")).isEqualTo(Locale.US);
        assertThat(IamLocaleResolver.parseTag("en-US")).isEqualTo(Locale.US);
        assertThat(IamLocaleResolver.parseTag("EN_us")).isEqualTo(Locale.US);
        assertThat(IamLocaleResolver.parseTag("zh")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(IamLocaleResolver.parseTag("zh-CN")).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(IamLocaleResolver.parseTag("fr")).isNull();
        assertThat(IamLocaleResolver.parseTag("")).isNull();
        assertThat(IamLocaleResolver.parseTag(null)).isNull();
    }

    @Test
    @DisplayName("语言转标签:中英各有固定写法,其它语言返回 null")
    void toTagMapsSupportedLanguages() {
        assertThat(IamLocaleResolver.toTag(Locale.US)).isEqualTo("en");
        assertThat(IamLocaleResolver.toTag(Locale.SIMPLIFIED_CHINESE)).isEqualTo("zh-CN");
        assertThat(IamLocaleResolver.toTag(Locale.JAPAN)).isNull();
        assertThat(IamLocaleResolver.toTag(null)).isNull();
    }
}
