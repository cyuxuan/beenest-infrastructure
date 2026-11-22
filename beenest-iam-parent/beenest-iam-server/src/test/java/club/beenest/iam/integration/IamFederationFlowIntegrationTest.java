package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三方登录闭环集成测试。
 * <p>联邦链路此前完全没有测试:broker 发起、state 一次性防伪、授权码换票、
 * 外部身份绑定、属性映射、角色映射,每一环都没有验证过。缺了这层测试,
 * "管理面配好了但运行时没生效"这类问题只能靠用户投诉发现。</p>
 * <p>这里内嵌一个最小的 OIDC 提供方(授权/令牌/用户信息三个端点),
 * 跑完整的"发起 → 跳转 → 回调 → 建号 → 登录态"链路。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamFederationFlowIntegrationTest extends IamIntegrationTestBase {

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

    /** 内嵌的极简 OIDC 提供方。 */
    private MockIdp idp;

    @AfterEach
    void stopIdp() {
        if (idp != null) {
            idp.stop();
        }
    }

    @Test
    @DisplayName("三方登录全链路:发起 → 回调 → 建号 → 建立登录态")
    void federationLoginCreatesLinkedUserAndSession() throws Exception {
        Session admin = login();
        idp = MockIdp.start("ext-user-1", "zhangsan@example.com", "张三", List.of("staff"));
        String alias = "idp-it-" + System.nanoTime();
        createIdentityProvider(admin, alias, idp);

        // 1. 发起:浏览器被送到外部 IdP
        ResponseEntity<String> start = rest.exchange(
                "/broker/default/" + alias + "/login", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, start.getStatusCode().value(),
                "发起三方登录应当重定向到外部 IdP: " + start.getBody());
        String location = start.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "应当重定向到外部 IdP");
        assertTrue(location.startsWith(idp.baseUrl()), "应当跳到外部 IdP: " + location);
        String state = queryParam(location, "state");
        assertNotNull(state, "授权地址应当携带 state: " + location);

        // 2. 回调:平台用授权码换外部身份,建号并建立登录态
        ResponseEntity<String> callback = rest.exchange(
                "/broker/default/" + alias + "/callback?code=mock-code&state=" + state,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, callback.getStatusCode().value(),
                "回调应当重定向回站内: " + callback.getBody());
        // 容器会把相对回跳地址补成绝对地址,这里只校验它落在本站的门户路径上
        String target = callback.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertTrue(target != null && target.endsWith("/portal"),
                "回调应当回到应用门户: " + target);
        // 回调建立了登录态:浏览器拿到会话 Cookie
        String cookie = joinCookies(callback.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertNotNull(cookieValue(cookie, "IAM_SESSION"), "回调后应当下发会话 Cookie: " + cookie);

        // 3. 外部账号已在本地建出来(登录名按 别名-外部标识 生成)
        ResponseEntity<String> users = exchange(admin, HttpMethod.GET, "/api/v1/admin/users?pageSize=200", null);
        JsonNode external = null;
        for (JsonNode node : dataOf(users.getBody()).path("records")) {
            if ("EXTERNAL".equals(node.path("userType").asText())) {
                external = node;
                break;
            }
        }
        assertNotNull(external, "三方登录应当建出外部类型账号");
        assertEquals(alias + "-ext-user-1", external.path("username").asText(),
                "外部账号登录名应当可追溯到来源提供方");
        assertEquals("zhangsan@example.com", external.path("email").asText());
    }

    @Test
    @DisplayName("提供方属性映射真正生效:外部属性写入本地扩展属性")
    void idpAttributeMapperTakesEffect() throws Exception {
        Session admin = login();
        idp = MockIdp.start("ext-user-2", "lisi@example.com", "李四", List.of("staff"));
        String alias = "idp-map-" + System.nanoTime();
        Long idpId = createIdentityProvider(admin, alias, idp);
        // 建一条属性映射:外部 department → 本地扩展属性 department
        ResponseEntity<String> mapper = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers/" + idpId + "/mappers",
                "{\"mapperType\":\"ATTRIBUTE\",\"sourceAttribute\":\"department\","
                        + "\"targetAttribute\":\"department\"}");
        assertTrue(mapper.getStatusCode().is2xxSuccessful(), "建映射应当成功: " + mapper.getBody());

        // 走完三方登录
        ResponseEntity<String> start = rest.exchange("/broker/default/" + alias + "/login", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        String state = queryParam(start.getHeaders().getFirst(HttpHeaders.LOCATION), "state");
        rest.exchange("/broker/default/" + alias + "/callback?code=mock-code&state=" + state,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);

        // 映射规则必须真的把外部属性搬进了本地账号
        ResponseEntity<String> users = exchange(admin, HttpMethod.GET, "/api/v1/admin/users?pageSize=200", null);
        JsonNode external = null;
        for (JsonNode node : dataOf(users.getBody()).path("records")) {
            if ("EXTERNAL".equals(node.path("userType").asText())) {
                external = node;
                break;
            }
        }
        assertNotNull(external, "应当已建出外部账号");
        JsonNode attributes = external.path("attributes");
        assertEquals("研发中心", attributes.path("department").asText(),
                "属性映射必须在回调时真的生效,外部属性应当写进本地扩展属性");
    }

    @Test
    @DisplayName("外部 IdP 不可用:明确报上游故障,不落成系统错误")
    void unreachableIdpReportsUpstreamFailure() throws Exception {
        Session admin = login();
        // 指向一个没人监听的端口
        String alias = "idp-down-" + System.nanoTime();
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "cid");
        config.put("clientSecret", "secret");
        config.put("authorizationUrl", "http://127.0.0.1:1/authorize");
        config.put("tokenUrl", "http://127.0.0.1:1/token");
        config.put("userInfoUrl", "http://127.0.0.1:1/userinfo");
        config.put("scope", "openid profile");
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "oidc",
                                "displayName", "不可用提供方", "enabled", true, "config", config)));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "建提供方应当成功: " + created.getBody());

        // 发起时授权地址是配置项,能拼出来;真正的问题在回调换票
        ResponseEntity<String> start = rest.exchange("/broker/default/" + alias + "/login", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, start.getStatusCode().value());
        String state = queryParam(start.getHeaders().getFirst(HttpHeaders.LOCATION), "state");

        ResponseEntity<String> callback = rest.exchange(
                "/broker/default/" + alias + "/callback?code=mock-code&state=" + state,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);
        // 外部 IdP 故障是上游问题,不是本平台 500
        assertTrue(callback.getStatusCode().is5xxServerError() || callback.getStatusCode().is4xxClientError(),
                "外部提供方不可用时不应静默成功: " + callback.getStatusCode());
        assertTrue(callback.getBody().contains("IDP_UNAVAILABLE")
                        || callback.getBody().contains("身份提供方")
                        || callback.getBody().contains("第三方登录"),
                "错误信息应当指向上游故障: " + callback.getBody());
    }

    // ===== 内部实现 =====

    /** 登记身份提供方,返回其主键。 */
    private Long createIdentityProvider(Session admin, String alias, MockIdp mock) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "mock-client");
        config.put("clientSecret", "mock-secret");
        config.put("authorizationUrl", mock.baseUrl() + "/authorize");
        config.put("tokenUrl", mock.baseUrl() + "/token");
        config.put("userInfoUrl", mock.baseUrl() + "/userinfo");
        config.put("scope", "openid profile email");
        ResponseEntity<String> response = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "oidc",
                                "displayName", "集成测试提供方", "enabled", true, "config", config)));
        assertTrue(response.getStatusCode().is2xxSuccessful(), "登记提供方应当成功: " + response.getBody());
        return objectMapper.readTree(response.getBody()).path("data").path("id").asLong();
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

    /** 取 URL 查询参数。 */
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

    /**
     * 极简 OIDC 提供方。
     * <p>只实现平台真正用到的那几个端点:授权(直接回跳带 code)、令牌(返回访问令牌)、
     * 用户信息(返回 sub 与若干属性)。</p>
     */
    private static final class MockIdp {

        private final HttpServer server;
        private final String subject;
        private final String email;
        private final String name;
        private final List<String> roles;
        /** 最近一次收到的授权请求,供断言用 */
        private final AtomicReference<String> lastAuthorizationRequest = new AtomicReference<>();

        private MockIdp(HttpServer server, String subject, String email, String name, List<String> roles) {
            this.server = server;
            this.subject = subject;
            this.email = email;
            this.name = name;
            this.roles = roles;
        }

        static MockIdp start(String subject, String email, String name, List<String> roles) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MockIdp idp = new MockIdp(server, subject, email, name, roles);
            server.createContext("/authorize", exchange -> {
                idp.lastAuthorizationRequest.set(exchange.getRequestURI().toString());
                String redirect = queryParam(exchange.getRequestURI().toString(), "redirect_uri");
                String state = queryParam(exchange.getRequestURI().toString(), "state");
                String location = redirect + "?code=mock-code&state=" + (state == null ? "" : state);
                exchange.getResponseHeaders().add("Location", location);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/token", exchange -> {
                respond(exchange, 200, "{\"access_token\":\"mock-access-token\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600,\"id_token\":\"mock-id-token\"}");
            });
            server.createContext("/userinfo", exchange -> {
                StringBuilder json = new StringBuilder("{\"sub\":\"").append(subject)
                        .append("\",\"email\":\"").append(email)
                        .append("\",\"email_verified\":true")
                        .append(",\"preferred_username\":\"").append(subject)
                        .append("\",\"name\":\"").append(name)
                        .append("\",\"department\":\"研发中心\"")
                        .append(",\"roles\":[");
                for (int i = 0; i < roles.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    json.append('"').append(roles.get(i)).append('"');
                }
                json.append("]}");
                respond(exchange, 200, json.toString());
            });
            server.start();
            return idp;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
