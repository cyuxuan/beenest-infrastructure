package club.beenest.iam.authz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨源策略测试:本站来源始终放行,外部来源按白名单,未登记来源拒绝。
 * <p>重点是"本站"的判定不能依赖框架按 scheme/host/port 推断的结果——
 * 经反向代理或端口映射后那套推断与浏览器看到的地址不一致,会把自家页面的
 * 登录请求判成跨源。</p>
 */
class IamCorsConfigurationTest {

    private static final String HOST = "id.example.com";
    private static final int PORT = 8080;
    private static final String SELF = "http://" + HOST + ":" + PORT;

    private final CorsConfigurationSource source =
            new IamSecurityConfiguration().corsConfigurationSource("https://admin.example.com");

    @Test
    @DisplayName("不带 Origin 的请求不参与跨源处理")
    void skipsRequestWithoutOrigin() {
        MockHttpServletRequest request = request(null);
        assertThat(source.getCorsConfiguration(request)).isNull();
    }

    @Test
    @DisplayName("本站来源放行:登录页对自己的接口发请求不应被当成跨源")
    void allowsSameSiteOrigin() {
        CorsConfiguration configuration = source.getCorsConfiguration(request(SELF));
        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin(SELF)).isEqualTo(SELF);
    }

    @Test
    @DisplayName("协议不同但 host:port 一致仍放行:代理后容器看到的是 http")
    void allowsSameSiteAcrossScheme() {
        String httpsSelf = "https://" + HOST + ":" + PORT;
        CorsConfiguration configuration = source.getCorsConfiguration(request(httpsSelf));
        assertThat(configuration.checkOrigin(httpsSelf)).isEqualTo(httpsSelf);
    }

    @Test
    @DisplayName("已登记的前端来源放行")
    void allowsWhitelistedOrigin() {
        CorsConfiguration configuration = source.getCorsConfiguration(request("https://admin.example.com"));
        assertThat(configuration.checkOrigin("https://admin.example.com"))
                .isEqualTo("https://admin.example.com");
    }

    @Test
    @DisplayName("端口被代理改写仍放行:浏览器访问 80、容器监听 8080")
    void allowsSameSiteAcrossPort() {
        String viaProxy = "http://" + HOST;
        CorsConfiguration configuration = source.getCorsConfiguration(request(viaProxy));
        assertThat(configuration.checkOrigin(viaProxy)).isEqualTo(viaProxy);
    }

    @Test
    @DisplayName("未登记的外部来源拒绝")
    void rejectsForeignOrigin() {
        CorsConfiguration configuration = source.getCorsConfiguration(request("https://evil.example"));
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    @DisplayName("换主机名访问时按该主机名放行:IP 与域名都是本站")
    void allowsIpAccess() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServerName("127.0.0.1");
        request.setServerPort(PORT);
        request.addHeader("Host", "127.0.0.1:" + PORT);
        request.addHeader("Origin", "http://127.0.0.1:" + PORT);
        CorsConfiguration configuration = source.getCorsConfiguration(request);
        assertThat(configuration.checkOrigin("http://127.0.0.1:" + PORT))
                .isEqualTo("http://127.0.0.1:" + PORT);
    }

    @Test
    @DisplayName("白名单为空时本站仍放行,外部来源仍拒绝")
    void allowsSameSiteWithoutWhitelist() {
        CorsConfigurationSource bare = new IamSecurityConfiguration().corsConfigurationSource("");
        assertThat(bare.getCorsConfiguration(request(SELF)).checkOrigin(SELF)).isEqualTo(SELF);
        assertThat(bare.getCorsConfiguration(request("https://evil.example"))
                .checkOrigin("https://evil.example")).isNull();
    }

    /**
     * 构造指向本站的请求。
     *
     * @param origin Origin 头,可为 null
     * @return 请求对象
     */
    private MockHttpServletRequest request(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServerName(HOST);
        request.setServerPort(PORT);
        request.addHeader("Host", HOST + ":" + PORT);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    @Test
    @DisplayName("不透明来源拒绝:不把 null 写进允许来源,也不猜它是本站")
    void rejectsOpaqueOrigin() {
        CorsConfiguration configuration = source.getCorsConfiguration(request("null"));
        assertThat(configuration).isNotNull();
        assertThat(configuration.checkOrigin("null")).isNull();
    }

    @Test
    @DisplayName("不透明来源即便带同源 Referer 也拒绝:来源头本身不可验证")
    void rejectsOpaqueOriginEvenWithSameSiteReferer() {
        MockHttpServletRequest request = request("null");
        request.addHeader("Referer", SELF + "/login");
        assertThat(source.getCorsConfiguration(request).checkOrigin("null")).isNull();
    }
}
