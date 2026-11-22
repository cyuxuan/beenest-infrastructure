package club.beenest.iam.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信登录端到端集成测试。
 * <p>扫码登录走浏览器跳转:发起时跳到微信的扫码授权页,微信回调后平台换身份、建号并建立会话。
 * 小程序登录的链路由 {@link IamProviderGrantIntegrationTest} 覆盖。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamWechatLoginIntegrationTest extends IamIntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("iam.realm-key.master-secret", () -> "integration-test-master-secret");
        registry.add("iam.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("iam.bootstrap.enabled", () -> "true");
    }

    private HttpServer wechat;

    @AfterEach
    void stopWechat() {
        if (wechat != null) {
            wechat.stop(0);
        }
    }

    @Test
    @DisplayName("扫码登录:跳微信扫码授权页,回调后建号并建立会话")
    void scanLoginRedirectsToWechatAndEstablishesSession() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-web-" + System.nanoTime();
        createWebProvider(admin, alias, wechatBase);

        // 1. 发起:跳到微信的扫码授权页
        ResponseEntity<String> start = rest.exchange("/broker/default/" + alias + "/login", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, start.getStatusCode().value(), "发起扫码登录应当重定向: " + start.getBody());
        String location = start.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "应当重定向到微信扫码授权页");
        assertTrue(location.startsWith("https://open.weixin.qq.com/connect/qrconnect?appid="),
                "微信扫码授权页必须以 appid 开头: " + location);
        assertTrue(location.endsWith("#wechat_redirect"), "微信要求地址带该片段: " + location);
        String state = queryParam(location, "state");
        assertNotNull(state, "授权地址应当携带 state: " + location);

        // 2. 微信回调:换身份、建号、建立会话
        ResponseEntity<String> callback = rest.exchange(
                "/broker/default/" + alias + "/callback?code=mock-code&state=" + state,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, callback.getStatusCode().value(), "回调应当重定向回站内: " + callback.getBody());
        String cookie = joinCookies(callback.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertNotNull(cookieValue(cookie, "IAM_SESSION"), "回调后应当建立会话: " + cookie);

        // 3. 账号已建出来,openid 落在扩展属性里
        ResponseEntity<String> users = exchange(admin, HttpMethod.GET, "/api/v1/admin/users?pageSize=200", null);
        String externalId = null;
        for (var node : dataOf(users.getBody()).path("records")) {
            if ("EXTERNAL".equals(node.path("userType").asText())) {
                externalId = node.path("attributes").path("openid").asText();
                break;
            }
        }
        assertEquals("o-web-1", externalId, "扫码登录应当建出外部账号并记录 openid");
    }

    // ===== 内部实现 =====

    /** 内嵌微信开放平台接口:令牌端点与用户信息端点。 */
    private String startWechat() throws IOException {
        wechat = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wechat.createContext("/sns/oauth2/access_token", exchange -> {
            respond(exchange, "{\"access_token\":\"mock-access\",\"openid\":\"o-web-1\","
                    + "\"unionid\":\"u-web-1\",\"expires_in\":7200}");
        });
        wechat.createContext("/sns/userinfo", exchange -> {
            respond(exchange, "{\"openid\":\"o-web-1\",\"nickname\":\"李四\","
                    + "\"headimgurl\":\"https://img.example.com/a.png\"}");
        });
        wechat.start();
        return "http://127.0.0.1:" + wechat.getAddress().getPort();
    }

    /** 登记微信扫码登录提供方。 */
    private void createWebProvider(Session admin, String alias, String wechatBase) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "wx-open-id");
        config.put("clientSecret", "wx-open-secret");
        config.put("tokenUrl", wechatBase + "/sns/oauth2/access_token");
        config.put("userInfoUrl", wechatBase + "/sns/userinfo");
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "wechat-web",
                                "displayName", "微信扫码登录", "enabled", true, "config", config)));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "登记提供方应当成功: " + created.getBody());
    }

    private record Session(String cookie, String csrf) {
    }

    private Session login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return new Session(cookie, cookieValue(cookie, "XSRF-TOKEN"));
    }

    private ResponseEntity<String> exchange(Session admin, HttpMethod method, String path, String body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", admin.csrf());
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    private static String cookieValue(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String queryParam(String url, String name) {
        if (url == null) {
            return null;
        }
        int index = url.indexOf(name + "=");
        if (index < 0) {
            return null;
        }
        int start = index + name.length() + 1;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }
}
