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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供方类型元数据接口测试。
 * <p>类型元数据是热插拔的入口:管理端据此渲染类型下拉与配置表单。
 * 端点缺失或内容不对,控制台就只能硬编码类型列表,新增提供方仍要改前端——
 * 热插拔就成了空话。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamProviderTypeIntegrationTest extends IamIntegrationTestBase {

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
    @DisplayName("类型元数据接口:返回已注册提供方类型的能力位与配置项声明")
    void providerTypesExposeRegisteredTypes() throws Exception {
        Session admin = login();

        ResponseEntity<String> response = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/identity-providers/provider-types", null);
        assertEquals(200, response.getStatusCode().value(), "类型元数据接口应当可用: " + response.getBody());

        JsonNode oidc = null;
        for (JsonNode type : dataOf(response.getBody())) {
            if ("oidc".equals(type.path("providerId").asText())) {
                oidc = type;
                break;
            }
        }
        assertNotNull(oidc, "已注册的 OIDC 类型应当出现在元数据里: " + response.getBody());
        assertEquals("通用 OIDC", oidc.path("name").asText());
        assertTrue(oidc.path("loginModes").toString().contains("REDIRECT"), "OIDC 支持浏览器跳转");
        // 配置项声明是动态表单的数据来源,没有它控制台只能退回自由 KV 编辑器
        assertTrue(oidc.path("configProperties").size() > 0, "应当声明该类型的配置项");
        boolean declaredSecret = false;
        for (JsonNode property : oidc.path("configProperties")) {
            if ("clientSecret".equals(property.path("name").asText())) {
                declaredSecret = true;
                assertTrue(property.path("secret").asBoolean(), "客户端密钥必须标记为机密");
            }
        }
        assertTrue(declaredSecret, "配置项声明里应当包含客户端密钥");
    }

    // ===== 内部实现 =====

    private record Session(String cookie, String csrf) {
    }

    private Session login() throws Exception {
        HttpHeaders headers = jsonHeaders();
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
}
