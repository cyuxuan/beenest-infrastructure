package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话授权时效集成测试。
 * <p>浏览器会话与 Bearer 令牌是同一份授权决策的两个视图,必须用同一套新鲜度规则:
 * 账号被禁用 → 会话立即作废;权限被撤销 → 会话就地刷新(不登出)。</p>
 * <p>这里锁住三条契约:禁用即失效、撤销即刷新、刷新不登出。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamSessionAuthorizationIntegrationTest extends IamIntegrationTestBase {

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
    @DisplayName("禁用账号后,已登录会话的下一个请求即失效")
    void disablingAccountTerminatesSession() throws Exception {
        Session admin = login();
        Long userId = createUser(admin, "session-disable");
        Session user = loginAs("session-disable");

        // 1. 禁用前:会话可用
        assertEquals(200, me(user).getStatusCode().value(), "禁用前会话应当可用");

        // 2. 禁用账号
        ResponseEntity<String> disable = exchange(admin, HttpMethod.PUT,
                "/api/v1/admin/users/" + userId, "{\"status\":0}");
        assertTrue(disable.getStatusCode().is2xxSuccessful(), "禁用应当成功: " + disable.getBody());

        // 3. 已登录会话的下一个请求即失效
        assertEquals(401, me(user).getStatusCode().value(),
                "账号被禁用后,已登录会话必须立即失效");
    }

    @Test
    @DisplayName("撤销权限后,已登录会话的下一个请求即失去该权限(且不被登出)")
    void revokingPermissionRefreshesSessionInPlace() throws Exception {
        Session admin = login();
        Long userId = createUser(admin, "session-revoke");
        // 1. 给用户直授一个角色,角色带一个权限
        Long permissionId = permissionId(admin, "audit:view");
        Long roleId = createRole(admin, "SESSION_REVOKE_ROLE");
        exchange(admin, HttpMethod.PUT, "/api/v1/admin/roles/" + roleId + "/permissions",
                "{\"permissionIds\":[" + permissionId + "]}");
        exchange(admin, HttpMethod.PUT, "/api/v1/admin/users/" + userId + "/roles",
                "{\"roleIds\":[" + roleId + "]}");

        Session user = loginAs("session-revoke");
        assertTrue(meAuthorities(user).contains("audit:view"), "会话应当持有刚授予的权限");

        // 2. 撤销角色(权限来源消失)
        ResponseEntity<String> delete = exchange(admin, HttpMethod.DELETE,
                "/api/v1/admin/roles/" + roleId, null);
        assertTrue(delete.getStatusCode().is2xxSuccessful(), "删除角色应当成功: " + delete.getBody());

        // 3. 会话刷新:权限立即消失,但用户仍处于登录态(不是被踢下线)
        List<String> authorities = meAuthorities(user);
        assertFalse(authorities.contains("audit:view"), "权限撤销后会话必须立即失去该权限");
        assertEquals(200, me(user).getStatusCode().value(), "权限刷新不应把用户登出");
    }

    @Test
    @DisplayName("用户特批撤销后,已登录会话的下一个请求即失去该权限")
    void revokingOverrideRefreshesSession() throws Exception {
        Session admin = login();
        Long userId = createUser(admin, "session-override");
        Long permissionId = permissionId(admin, "menu:view");

        // 1. 特批授予
        ResponseEntity<String> grant = exchange(admin, HttpMethod.PUT,
                "/api/v1/admin/users/" + userId + "/permission-overrides/" + permissionId,
                "{\"grantType\":\"GRANT\",\"reason\":\"会话时效测试\"}");
        assertTrue(grant.getStatusCode().is2xxSuccessful(), "特批授予应当成功: " + grant.getBody());

        Session user = loginAs("session-override");
        assertTrue(meAuthorities(user).contains("menu:view"), "会话应当持有特批授予的权限");

        // 2. 改判为收回
        ResponseEntity<String> revoke = exchange(admin, HttpMethod.PUT,
                "/api/v1/admin/users/" + userId + "/permission-overrides/" + permissionId,
                "{\"grantType\":\"REVOKE\",\"reason\":\"会话时效测试\"}");
        assertTrue(revoke.getStatusCode().is2xxSuccessful(), "特批收回应当成功: " + revoke.getBody());

        // 3. 会话立即失去该权限
        assertFalse(meAuthorities(user).contains("menu:view"), "特批收回后会话必须立即失去该权限");
    }

    // ===== 内部实现 =====

    /** 会话(容器 Cookie + CSRF 令牌)。 */
    private record Session(String cookie, String csrf) {
    }

    /** 管理员登录。 */
    private Session login() throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), jsonHeaders()),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "管理员登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return new Session(cookie, cookieValue(cookie, "XSRF-TOKEN"));
    }

    /** 普通用户登录。 */
    private Session loginAs(String username) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", username, "password", "Perm#2026x")), jsonHeaders()),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "用户登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return new Session(cookie, cookieValue(cookie, "XSRF-TOKEN"));
    }

    /** 造一个带口令的用户。 */
    private Long createUser(Session admin, String username) throws Exception {
        ResponseEntity<String> response = exchange(admin, HttpMethod.POST, "/api/v1/admin/users",
                objectMapper.writeValueAsString(Map.of(
                        "username", username, "nickname", username, "password", "Perm#2026x")));
        assertEquals(200, response.getStatusCode().value(), "建用户应当成功: " + response.getBody());
        return dataOf(response.getBody()).path("id").asLong();
    }

    /** 造一个域角色。 */
    private Long createRole(Session admin, String roleCode) throws Exception {
        ResponseEntity<String> response = exchange(admin, HttpMethod.POST, "/api/v1/admin/roles",
                objectMapper.writeValueAsString(Map.of(
                        "roleCode", roleCode, "roleName", roleCode, "containerType", "REALM")));
        assertEquals(200, response.getStatusCode().value(), "建角色应当成功: " + response.getBody());
        return dataOf(response.getBody()).path("id").asLong();
    }

    /** 按权限码取主键。 */
    private Long permissionId(Session admin, String code) throws Exception {
        ResponseEntity<String> response = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/permissions?pageSize=100", null);
        assertEquals(200, response.getStatusCode().value());
        for (JsonNode node : dataOf(response.getBody()).path("records")) {
            if (code.equals(node.path("permissionCode").asText())) {
                return node.path("id").asLong();
            }
        }
        throw new AssertionError("权限码不存在: " + code);
    }

    /** 用会话调 /api/v1/auth/me。 */
    private ResponseEntity<String> me(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, session.cookie());
        return rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
    }

    /** 会话持有的权限码。 */
    private List<String> meAuthorities(Session session) throws Exception {
        ResponseEntity<String> response = me(session);
        assertEquals(200, response.getStatusCode().value(), "会话应当仍然可用: " + response.getBody());
        List<String> authorities = new ArrayList<>();
        for (JsonNode node : dataOf(response.getBody()).path("authorities")) {
            authorities.add(node.asText());
        }
        return authorities;
    }

    /** 管理员发起的请求(带 CSRF 令牌)。 */
    private ResponseEntity<String> exchange(Session admin, HttpMethod method, String path, String body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", admin.csrf());
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** 拼接多个 Set-Cookie 为单个 Cookie 头。 */
    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
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
