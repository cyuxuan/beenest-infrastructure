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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供方配置保存语义测试。
 * <p>两件事必须成立:启用状态的提供方配齐必填项才允许保存;
 * 机密项在编辑时留空表示保持原值——密钥不会回显,管理员没有别的办法知道它还在,
 * 若"留空即清空",运维改一个端点地址就会顺手把密钥也弄丢,三方登录随之全面失效。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamIdpConfigIntegrationTest extends IamIntegrationTestBase {

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
    @DisplayName("启用状态缺少必填配置项:拒绝保存并指明缺哪一项")
    void enabledProviderRequiresCompleteConfig() throws Exception {
        Session admin = login();
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "cid");
        // 故意不给 clientSecret / 端点

        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", "idp-missing-" + System.nanoTime(), "providerId", "oidc",
                                "displayName", "配置不全的提供方", "enabled", true, "config", config)));

        assertTrue(created.getStatusCode().is4xxClientError(),
                "配置不全的启用提供方应当被拒绝: " + created.getStatusCode() + " " + created.getBody());
        String body = created.getBody();
        assertTrue(body.contains("clientSecret"), "错误信息应当指明缺了哪个配置项: " + body);
    }

    @Test
    @DisplayName("停用状态允许先建后配:不阻断准备阶段")
    void disabledProviderMayIncompleteConfig() throws Exception {
        Session admin = login();
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "cid");

        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", "idp-draft-" + System.nanoTime(), "providerId", "oidc",
                                "displayName", "待配置的提供方", "enabled", false, "config", config)));

        assertEquals(200, created.getStatusCode().value(),
                "停用状态的提供方允许配置不全: " + created.getBody());
    }

    @Test
    @DisplayName("必填配置齐备:可以保存并在登录入口出现")
    void completeConfigIsAccepted() throws Exception {
        Session admin = login();
        String alias = "idp-ok-" + System.nanoTime();
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "cid");
        config.put("clientSecret", "secret");
        config.put("authorizationUrl", "https://idp.example.com/authorize");
        config.put("tokenUrl", "https://idp.example.com/token");
        config.put("userInfoUrl", "https://idp.example.com/userinfo");

        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "oidc",
                                "displayName", "配置齐备的提供方", "enabled", true, "config", config)));
        assertEquals(200, created.getStatusCode().value(), "配置齐备应当保存成功: " + created.getBody());

        ResponseEntity<String> entries = rest.exchange("/api/v1/auth/idps?realm=default", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        boolean found = false;
        for (JsonNode entry : dataOf(entries.getBody())) {
            if (alias.equals(entry.path("alias").asText())) {
                found = true;
            }
        }
        assertTrue(found, "配好的提供方应当出现在登录入口列表里");
    }

    @Test
    @DisplayName("编辑时机密项留空:原值保留,不因改其它项而丢失")
    void blankSecretKeepsStoredValue() throws Exception {
        Session admin = login();
        String alias = "idp-secret-" + System.nanoTime();
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "cid");
        config.put("clientSecret", "secret-value");
        config.put("appSecret", "app-secret-value");
        config.put("authorizationUrl", "https://idp.example.com/authorize");
        config.put("tokenUrl", "https://idp.example.com/token");
        config.put("userInfoUrl", "https://idp.example.com/userinfo");
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "oidc",
                                "displayName", "带密钥的提供方", "enabled", true, "config", config)));
        assertEquals(200, created.getStatusCode().value(), "创建应当成功: " + created.getBody());
        Long idpId = dataOf(created.getBody()).path("id").asLong();
        String storedBefore = storedSecret(idpId, "appSecret");
        assertNotNull(storedBefore, "密钥应当加密落库");

        // 编辑时只改非机密项,机密项留空
        Map<String, Object> patch = new HashMap<>();
        patch.put("clientId", "cid-2");
        patch.put("clientSecret", "");
        patch.put("appSecret", "");
        patch.put("authorizationUrl", "https://idp.example.com/authorize");
        patch.put("tokenUrl", "https://idp.example.com/token");
        patch.put("userInfoUrl", "https://idp.example.com/userinfo");
        ResponseEntity<String> updated = exchange(admin, HttpMethod.PUT,
                "/api/v1/admin/identity-providers/" + idpId, objectMapper.writeValueAsString(
                        Map.of("displayName", "带密钥的提供方", "enabled", true, "config", patch)));
        assertEquals(200, updated.getStatusCode().value(), "编辑应当成功: " + updated.getBody());

        String storedAfter = storedSecret(idpId, "appSecret");
        assertNotNull(storedAfter, "留空不应当把密钥抹掉");
        assertEquals(storedBefore, storedAfter, "机密项留空必须保持原值");
    }

    /** 读取库中某配置项的密文。 */
    private String storedSecret(Long idpId, String key) {
        String json = jdbcTemplate.queryForObject(
                "SELECT config FROM iam_identity_provider WHERE id = ?", String.class, idpId);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json).path(key);
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
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
