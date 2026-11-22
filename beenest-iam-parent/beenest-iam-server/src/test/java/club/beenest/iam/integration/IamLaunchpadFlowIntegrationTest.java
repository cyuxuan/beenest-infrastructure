package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用门户启动链路集成测试。
 * <p>覆盖两件事:点击卡片是否 302 到应用入口地址、配置不完整或应用停用时
 * 卡片与跳转是否都判为不可进入。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamLaunchpadFlowIntegrationTest extends IamIntegrationTestBase {

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

    @Test
    @DisplayName("点击卡片:302 到应用入口地址")
    void launchRedirectsToEntryUrl() throws Exception {
        Session admin = login();
        String appCode = "lp-entry-app-" + System.nanoTime();
        createApp(admin, appCode, "https://app.example.com/home");

        ResponseEntity<String> launch = get(admin, "/portal/launch/" + appCode);
        assertEquals(302, launch.getStatusCode().value(), "启动应当重定向: " + launch.getBody());
        assertEquals("https://app.example.com/home",
                launch.getHeaders().getFirst(HttpHeaders.LOCATION), "启动目标是应用入口地址");

        JsonNode card = cardOf(get(admin, "/api/v1/launchpad/apps").getBody(), appCode);
        assertTrue(card.path("launchable").asBoolean(), "配了入口地址的卡片应当可进入");
    }

    @Test
    @DisplayName("未配置入口地址:启动退回门户并说明原因")
    void launchFallsBackToPortalWhenNoEntryUrl() throws Exception {
        Session admin = login();
        String appCode = "lp-empty-app-" + System.nanoTime();
        createApp(admin, appCode, "https://app.example.com/home");
        // 清掉入口地址
        Long appId = appIdOf(admin, appCode);
        ResponseEntity<String> update = exchange(admin, HttpMethod.PUT,
                "/api/v1/admin/application-services/" + appId, "{\"entryUrl\":\"\"}");
        assertTrue(update.getStatusCode().is2xxSuccessful(), "清空入口地址应当成功: " + update.getBody());

        ResponseEntity<String> launch = get(admin, "/portal/launch/" + appCode);
        assertEquals(302, launch.getStatusCode().value(), "启动仍然是一次重定向: " + launch.getBody());
        String location = launch.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.startsWith("/portal?launchError="),
                "无可跳转地址时退回门户并带上原因: " + location);

        JsonNode card = cardOf(get(admin, "/api/v1/launchpad/apps").getBody(), appCode);
        assertFalse(card.path("launchable").asBoolean(), "没有入口地址的卡片不可进入");
        assertEquals("应用未配置入口地址", card.path("launchHint").asText());
    }

    @Test
    @DisplayName("应用已停用:卡片不再下发,直接访问启动端点退回门户")
    void disabledAppIsNotLaunchable() throws Exception {
        Session admin = login();
        String appCode = "lp-off-app-" + System.nanoTime();
        createApp(admin, appCode, "https://app.example.com/home");
        Long appId = appIdOf(admin, appCode);
        ResponseEntity<String> update = exchange(admin, HttpMethod.PUT,
                "/api/v1/admin/application-services/" + appId, "{\"enabled\":false}");
        assertTrue(update.getStatusCode().is2xxSuccessful(), "停用应当成功: " + update.getBody());

        // 停用即不可见,卡片列表里不再出现
        JsonNode apps = dataOf(get(admin, "/api/v1/launchpad/apps").getBody());
        for (JsonNode node : apps) {
            assertFalse(appCode.equals(node.path("appCode").asText()), "停用的应用不应出现在卡片列表");
        }

        // 拿着旧页面上的卡片点进来:退回门户并说明原因,不是 404 页面
        ResponseEntity<String> launch = get(admin, "/portal/launch/" + appCode);
        assertEquals(302, launch.getStatusCode().value(), "启动仍然是一次重定向: " + launch.getBody());
        String location = launch.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        assertTrue(location.startsWith("/portal?launchError="),
                "不可进入时退回门户并带上原因: " + location);
    }

    // ===== 内部实现 =====

    /** 建一个门户应用(连带建它绑定的客户端)。 */
    private void createApp(Session admin, String appCode, String entryUrl) throws Exception {
        String clientKey = appCode + "-client";
        createClient(admin, clientKey);
        Long clientId = clientIdOf(admin, clientKey);
        Map<String, Object> body = new HashMap<>();
        body.put("appCode", appCode);
        body.put("appName", "门户集成测试应用");
        body.put("appType", "WEB");
        body.put("ssoMode", "OIDC");
        body.put("ssoClientPk", clientId);
        body.put("entryUrl", entryUrl);
        body.put("visibility", "PUBLIC");
        body.put("enabled", true);
        ResponseEntity<String> response = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/application-services", objectMapper.writeValueAsString(body));
        assertTrue(response.getStatusCode().is2xxSuccessful(), "建应用卡片应当成功: " + response.getBody());
    }

    /** 建机密客户端。 */
    private void createClient(Session admin, String clientKey) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientKey", clientKey);
        body.put("clientName", "门户集成测试客户端");
        body.put("clientType", "CONFIDENTIAL");
        body.put("redirectUris", List.of("https://app.example.com/cb"));
        body.put("scopes", List.of("openid", "profile"));
        ResponseEntity<String> response = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/clients", objectMapper.writeValueAsString(body));
        assertTrue(response.getStatusCode().is2xxSuccessful(), "登记客户端应当成功: " + response.getBody());
    }

    /** 取客户端主键。 */
    private Long clientIdOf(Session admin, String clientKey) throws Exception {
        ResponseEntity<String> response = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/clients?pageSize=200", null);
        for (JsonNode node : dataOf(response.getBody()).path("records")) {
            if (clientKey.equals(node.path("clientKey").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("客户端不存在: " + clientKey);
    }

    /** 取应用主键。 */
    private Long appIdOf(Session admin, String appCode) throws Exception {
        ResponseEntity<String> response = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/application-services?pageSize=200", null);
        for (JsonNode node : dataOf(response.getBody()).path("records")) {
            if (appCode.equals(node.path("appCode").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("应用不存在: " + appCode);
    }

    /** 从卡片列表响应中取指定应用的卡片。 */
    private JsonNode cardOf(String body, String appCode) throws Exception {
        for (JsonNode node : dataOf(body)) {
            if (appCode.equals(node.path("appCode").asText())) {
                return node;
            }
        }
        throw new AssertionError("卡片不存在: " + appCode);
    }

    /** 会话(Cookie + CSRF 令牌)。 */
    private record Session(String cookie, String csrf) {
    }

    /** 管理员登录。 */
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

    /** 管理员发起的请求(带 CSRF 令牌)。 */
    private ResponseEntity<String> exchange(Session admin, HttpMethod method, String path, String body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", admin.csrf());
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** 只带会话 Cookie 的 GET。 */
    private ResponseEntity<String> get(Session admin, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(URI.create(path), HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    /** 拼接多个 Set-Cookie 为单个 Cookie 头。 */
    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream()
                .map(c -> c.split(";", 2)[0])
                .toList());
    }

    /** 从 Cookie 串中取指定 cookie 的值。 */
    private static String cookieValue(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }
}
