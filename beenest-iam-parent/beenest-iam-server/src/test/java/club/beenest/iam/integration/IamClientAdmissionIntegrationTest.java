package club.beenest.iam.integration;

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
import org.springframework.web.util.UriComponentsBuilder;
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
 * 客户端准入策略端到端回归。
 * <p>单元测试钉住了判定分支(白名单命中、不命中、强制 MFA、匿名放行),
 * 却钉不住"这段判定真的接在授权链路上、并且跑在授权端点之前"。
 * 过滤器没被注册、或被注册到授权端点之后时,应用照常启动、策略照常保存,
 * 只是永远不生效——表现正是"策略配了却没用",没有任何报错可供追查。
 * 只有用真实容器跑通整条链路,才能把这种静默失效暴露出来。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamClientAdmissionIntegrationTest extends IamIntegrationTestBase {

    // 容器由本测试类自己声明:Testcontainers 的扩展在本类测试结束时停止容器,
    // 放在基类里会导致后续测试类连不上数据库
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
    @DisplayName("白名单命中时照常走授权码流程")
    void allowsMatchingMethod() throws Exception {
        TestSession session = login();
        createClient(session, "admit-ok", Map.of("allowedLoginTypes", List.of("PASSWORD")));

        String location = authorizeLocation(session, "admit-ok");

        assertTrue(location.startsWith("http://127.0.0.1:9999/cb"), "应当回跳到客户端回调地址: " + location);
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        assertNotNull(code, "应当携带授权码: " + location);
    }

    @Test
    @DisplayName("白名单不匹配时转补验页,而不是直接失败")
    void challengesOnMismatch() throws Exception {
        TestSession session = login();
        // 白名单只能用"本域当下真的可用"的方式,否则管理端在配置期就会拒绝
        // ("该客户端将无人能登录")——本测试域因此先启用 LDAP 联合。
        // 本次会话是口令登录,与只允许 LDAP 的白名单必然不匹配,正是要考的分支
        enableLdapInRealm(session);
        createClient(session, "admit-ldap", Map.of("allowedLoginTypes", List.of("LDAP")));

        String location = authorizeLocation(session, "admit-ldap");

        assertTrue(location.contains("/login-challenge"), "应当跳补验页: " + location);
        assertTrue(location.contains("token="), "应当携带挑战令牌: " + location);
        assertFalse(location.contains("code="), "不得在补验前发放授权码: " + location);
    }

    @Test
    @DisplayName("强制 MFA 的客户端在会话未完成二步验证时要求补验")
    void challengesMfa() throws Exception {
        TestSession session = login();
        createClient(session, "admit-mfa", Map.of("requireMfa", true));

        String location = authorizeLocation(session, "admit-mfa");

        assertTrue(location.contains("/login-challenge"), "应当跳补验页: " + location);
        assertTrue(location.contains("mfa="), "MFA 挑战要同时签会话级票据: " + location);
        assertFalse(location.contains("code="), "不得在补验前发放授权码: " + location);
    }

    @Test
    @DisplayName("没有会话时不发起挑战,交给登录页")
    void anonymousIsLeftToLoginPage() throws Exception {
        TestSession session = login();
        // 同上:白名单方式必须是本域可用的,先启用 LDAP 才能登记该客户端
        enableLdapInRealm(session);
        createClient(session, "admit-anon", Map.of("allowedLoginTypes", List.of("LDAP")));
        // 不带 Cookie 访问:没有会话就没有"当前认证方式",准入判定直接放行,
        // 由登录页按策略展示过滤——在授权端点上拦匿名用户会把人踢进死循环
        String location = authorizeLocation(null, "admit-anon");

        assertFalse(location.contains("/login-challenge"), "匿名访问不应进入补验页: " + location);
        assertTrue(location.contains("/login"), "匿名访问应落到登录页: " + location);
    }

    /** 启用当前域的 LDAP 联合(仅落配置,不试连接)。 */
    private void enableLdapInRealm(TestSession session) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", true);
        body.put("serverUrl", "ldap://127.0.0.1:1389");
        body.put("userBaseDn", "dc=admission,dc=it");
        body.put("connectTimeoutMs", 1000);
        body.put("readTimeoutMs", 1000);
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/ldap-config", HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "启用 LDAP 联合应当成功: " + response.getBody());
    }

    /** 带准入字段登记机密客户端。 */
    private void createClient(TestSession session, String clientKey, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientKey", clientKey);
        body.put("clientName", "准入集成测试客户端");
        body.put("clientType", "CONFIDENTIAL");
        body.put("redirectUris", List.of("http://127.0.0.1:9999/cb"));
        body.put("scopes", List.of("openid", "profile"));
        body.putAll(extra);
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "登记客户端应当成功: " + response.getBody());
    }

    /** 访问授权端点,返回 Location(不假定一定带授权码)。 */
    private String authorizeLocation(TestSession session, String clientKey) {
        URI uri = UriComponentsBuilder.fromPath("/realms/default/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientKey)
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:9999/cb")
                .queryParam("state", "it-state")
                .build().encode().toUri();
        HttpHeaders headers = session == null ? new HttpHeaders() : cookieHeaders(session);
        ResponseEntity<String> response = rest.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
        assertEquals(302, response.getStatusCode().value(),
                "授权端点应当重定向: " + response.getStatusCode() + " " + response.getBody());
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "重定向必须带 Location");
        return location;
    }

    /** 登录会话(容器 Cookie + CSRF 令牌)。 */
    private record TestSession(String cookie, String csrf) {
    }

    /** REST 登录,返回会话 Cookie 与 CSRF 令牌。 */
    private TestSession login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        String csrf = cookieValue(cookie, "XSRF-TOKEN");
        assertNotNull(csrf, "登录后应当下发 XSRF-TOKEN");
        return new TestSession(cookie, csrf);
    }

    /** JSON 请求头(含 CSRF 令牌)。 */
    private HttpHeaders jsonHeaders(TestSession session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", session.csrf());
        headers.set(HttpHeaders.COOKIE, session.cookie());
        return headers;
    }

    /** 仅 Cookie 的请求头。 */
    private HttpHeaders cookieHeaders(TestSession session) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, session.cookie());
        return headers;
    }

    /** 拼接多个 Set-Cookie 为单个 Cookie 头。 */
    private static String joinCookies(java.util.List<String> setCookies) {
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
