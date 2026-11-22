package club.beenest.iam.authz.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源校验过滤器测试:状态变更请求必须来自本站或已登记的前端来源。
 */
class IamOriginGuardFilterTest {

    private static final String HOST = "id.example.com";

    private final IamOriginGuardFilter filter = new IamOriginGuardFilter(
            List.of("https://admin.example.com"), new ObjectMapper());

    @Test
    @DisplayName("同源 POST 放行")
    void allowsSameOrigin() throws Exception {
        MockHttpServletResponse response = doFilter("POST", "https://" + HOST, null);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("已登记的前端来源放行")
    void allowsConfiguredOrigin() throws Exception {
        MockHttpServletResponse response = doFilter("POST", "https://admin.example.com", null);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("跨站 POST 拒绝:登录接口豁免 CSRF 后由这一层拦住登录 CSRF")
    void rejectsCrossSiteOrigin() throws Exception {
        MockHttpServletResponse response = doFilter("POST", "https://evil.example", null);
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("没有来源头按非浏览器客户端放行(curl / 服务端调用)")
    void allowsClientWithoutOrigin() throws Exception {
        MockHttpServletResponse response = doFilter("POST", null, null);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("GET 不校验来源")
    void ignoresSafeMethod() throws Exception {
        MockHttpServletResponse response = doFilter("GET", "https://evil.example", null);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("缺少 Origin 时回落到 Referer 判定")
    void fallsBackToReferer() throws Exception {
        assertThat(doFilter("POST", null, "https://evil.example/login").getStatus()).isEqualTo(403);
        assertThat(doFilter("POST", null, "https://" + HOST + "/login").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("默认端口与显式端口视为同一来源")
    void treatsDefaultPortAsSameOrigin() throws Exception {
        assertThat(doFilter("POST", "https://" + HOST + ":443", null).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("同主机不同端口视为同一来源:代理改写端口、前端 dev server 都属此类")
    void treatsSameHostAsSameOrigin() throws Exception {
        assertThat(doFilter("POST", "http://" + HOST + ":5175", null).getStatus()).isEqualTo(200);
        assertThat(doFilter("POST", "http://" + HOST, null).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("不同主机名仍拒绝:放宽端口不等于放宽域名")
    void stillRejectsDifferentHost() throws Exception {
        assertThat(doFilter("POST", "http://other.example:8080", null).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("不透明来源拒绝:来源不可验证时不放行,沙箱化 iframe 伪造登录走这条路径")
    void rejectsOpaqueOrigin() throws Exception {
        assertThat(doFilter("POST", "null", null).getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("不透明来源即便带同源 Referer 也拒绝")
    void rejectsOpaqueOriginEvenWithSameSiteReferer() throws Exception {
        assertThat(doFilter("POST", "null", "http://" + HOST + "/login").getStatus()).isEqualTo(403);
    }

    /**
     * 执行过滤器。
     *
     * @param method 请求方法
     * @param origin Origin 头,可为 null
     * @param referer Referer 头,可为 null
     * @return 响应
     * @throws Exception 过滤器异常
     */
    private MockHttpServletResponse doFilter(String method, String origin, String referer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/auth/login");
        request.addHeader("Host", HOST);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        if (referer != null) {
            request.addHeader("Referer", referer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
