package club.beenest.iam.authz.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源地址比对工具测试:本站判定只比对 host:port,不比对协议。
 */
class OriginAuthoritiesTest {

    private static final String HOST = "id.example.com";
    private static final int PORT = 8080;

    @Test
    @DisplayName("从绝对地址提取 host:port")
    void extractsAuthority() {
        assertThat(OriginAuthorities.authorityOf("https://id.example.com:8443/login")).isEqualTo("id.example.com:8443");
        assertThat(OriginAuthorities.authorityOf("http://127.0.0.1:8080")).isEqualTo("127.0.0.1:8080");
    }

    @Test
    @DisplayName("缺端口时只返回主机名")
    void extractsHostWithoutPort() {
        assertThat(OriginAuthorities.authorityOf("https://id.example.com")).isEqualTo("id.example.com");
    }

    @Test
    @DisplayName("非法或空地址返回 null")
    void rejectsInvalidUrl() {
        assertThat(OriginAuthorities.authorityOf(null)).isNull();
        assertThat(OriginAuthorities.authorityOf("")).isNull();
        assertThat(OriginAuthorities.authorityOf("null")).isNull();
        assertThat(OriginAuthorities.authorityOf("/relative/path")).isNull();
    }

    @Test
    @DisplayName("默认端口归一化后与省略端口等价")
    void normalizesDefaultPort() {
        assertThat(OriginAuthorities.normalize("id.example.com:443")).isEqualTo("id.example.com");
        assertThat(OriginAuthorities.normalize("id.example.com:80")).isEqualTo("id.example.com");
        assertThat(OriginAuthorities.normalize("id.example.com:8080")).isEqualTo("id.example.com:8080");
        assertThat(OriginAuthorities.normalize("ID.Example.COM:8080")).isEqualTo("id.example.com:8080");
    }

    @Test
    @DisplayName("Host 头一致即视为本站")
    void matchesHostHeader() {
        MockHttpServletRequest request = request();
        assertThat(OriginAuthorities.isSameSite(request, "http://" + HOST + ":" + PORT)).isTrue();
    }

    @Test
    @DisplayName("协议不同但 host:port 一致仍视为本站:反向代理后容器看到的是 http")
    void ignoresScheme() {
        MockHttpServletRequest request = request();
        assertThat(OriginAuthorities.isSameSite(request, "https://" + HOST + ":" + PORT)).isTrue();
    }

    @Test
    @DisplayName("Host 被代理改写时按 X-Forwarded-Host 判定")
    void matchesForwardedHost() {
        MockHttpServletRequest request = request();
        request.removeHeader("Host");
        request.addHeader("Host", "10.0.0.5:8080");
        request.addHeader("X-Forwarded-Host", "id.example.com:8080");
        assertThat(OriginAuthorities.isSameSite(request, "https://id.example.com:8080")).isTrue();
    }

    @Test
    @DisplayName("外部来源不视为本站")
    void rejectsForeignOrigin() {
        MockHttpServletRequest request = request();
        assertThat(OriginAuthorities.isSameSite(request, "https://evil.example")).isFalse();
        assertThat(OriginAuthorities.isSameSite(request, "http://127.0.0.1:8080")).isFalse();
    }

    @Test
    @DisplayName("端口被代理改写仍视为本站:浏览器访问 80、容器监听 8080")
    void ignoresPort() {
        MockHttpServletRequest request = request();
        assertThat(OriginAuthorities.isSameSite(request, "http://" + HOST)).isTrue();
        assertThat(OriginAuthorities.isSameSite(request, "https://" + HOST)).isTrue();
    }

    @Test
    @DisplayName("提取主机名:普通域名与 IPv6")
    void extractsHost() {
        assertThat(OriginAuthorities.hostOf("id.example.com:8080")).isEqualTo("id.example.com");
        assertThat(OriginAuthorities.hostOf("id.example.com")).isEqualTo("id.example.com");
        assertThat(OriginAuthorities.hostOf("[::1]:8080")).isEqualTo("[::1]");
        assertThat(OriginAuthorities.hostOf(null)).isNull();
    }

    @Test
    @DisplayName("候选地址去重且至少一项")
    void collectsDistinctCandidates() {
        MockHttpServletRequest request = request();
        assertThat(OriginAuthorities.hostAuthorities(request)).contains("id.example.com:8080");
        assertThat(OriginAuthorities.hostAuthorities(request)).doesNotHaveDuplicates();
    }

    /**
     * 构造本站请求:Host 头与服务端名一致,模拟正常直连。
     *
     * @return 请求对象
     */
    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServerName(HOST);
        request.setServerPort(PORT);
        request.addHeader("Host", HOST + ":" + PORT);
        return request;
    }

    @Test
    @DisplayName("识别不透明来源:字面量 null 大小写与空白不敏感")
    void detectsOpaqueOrigin() {
        assertThat(OriginAuthorities.isOpaque("null")).isTrue();
        assertThat(OriginAuthorities.isOpaque(" NULL ")).isTrue();
        assertThat(OriginAuthorities.isOpaque("https://id.example.com")).isFalse();
        assertThat(OriginAuthorities.isOpaque(null)).isFalse();
    }

    @Test
    @DisplayName("真实 Origin 按主机名给出可信或跨站结论")
    void verdictFromRealOrigin() {
        MockHttpServletRequest same = request();
        same.addHeader("Origin", "https://" + HOST + ":8443");
        assertThat(OriginAuthorities.verdictOf(same)).isEqualTo(OriginAuthorities.Verdict.TRUSTED);

        MockHttpServletRequest foreign = request();
        foreign.addHeader("Origin", "https://evil.example");
        assertThat(OriginAuthorities.verdictOf(foreign)).isEqualTo(OriginAuthorities.Verdict.UNTRUSTED);
    }

    @Test
    @DisplayName("不透明来源一律不可信:无法比对来源,也不该被猜成可信")
    void opaqueOriginIsUntrusted() {
        assertThat(OriginAuthorities.verdictOf(opaqueRequest()))
                .isEqualTo(OriginAuthorities.Verdict.UNTRUSTED);
    }

    @Test
    @DisplayName("不透明来源即便带同源 Referer 也不改判:来源头本身不可验证")
    void opaqueOriginStaysUntrustedWithSameSiteReferer() {
        MockHttpServletRequest request = opaqueRequest();
        request.addHeader("Referer", "https://" + HOST + "/login");
        assertThat(OriginAuthorities.verdictOf(request)).isEqualTo(OriginAuthorities.Verdict.UNTRUSTED);
    }

    @Test
    @DisplayName("没有任何来源信息时结论为无法判定")
    void verdictUnknownWithoutSourceHeaders() {
        assertThat(OriginAuthorities.verdictOf(request())).isEqualTo(OriginAuthorities.Verdict.UNKNOWN);
    }

    /**
     * 构造携带不透明来源的请求。
     *
     * @return 请求对象
     */
    private MockHttpServletRequest opaqueRequest() {
        MockHttpServletRequest request = request();
        request.addHeader("Origin", OriginAuthorities.OPAQUE_ORIGIN);
        return request;
    }
}
