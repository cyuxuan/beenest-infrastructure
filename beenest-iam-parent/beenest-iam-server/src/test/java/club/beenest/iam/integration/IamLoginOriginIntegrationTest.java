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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 登录请求的来源判定集成测试。
 * <p>真实浏览器对表单提交的 {@code Origin} 由 referrer 推导:响应头一旦下发
 * {@code Referrer-Policy: no-referrer},referrer 被清空,{@code Origin} 会退化成
 * 字面量 {@code null}(不透明来源),服务端再也拿不到真实来源,同源登录请求会被
 * 判成跨源并返回 {@code Invalid CORS request}。这类问题只在真实浏览器里复现,
 * 单元测试造出来的请求头盖不住,所以这里用真实 HTTP 栈把响应头与判定结果一起钉住。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamLoginOriginIntegrationTest extends IamIntegrationTestBase {

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
        registry.add("iam.cors.allowed-origins", () -> ALLOWED_ORIGIN);
    }

    /** 已登记的前端来源 */
    private static final String ALLOWED_ORIGIN = "https://admin.example.com";

    @Test
    @DisplayName("登录页不得下发 no-referrer:它会把表单提交的 Origin 变成 null")
    void loginPageDoesNotStripReferrer() {
        ResponseEntity<String> response = rest.getForEntity(
                "/login?realm=default&return_to=", String.class);

        assertEquals(200, response.getStatusCode().value());
        String policy = response.getHeaders().getFirst("Referrer-Policy");
        assertNotNull(policy, "登录页应当显式声明 Referrer-Policy");
        assertNotEquals("no-referrer", policy,
                "no-referrer 会让浏览器把表单提交的 Origin 变成字面量 null,同源登录被判跨源");
        assertEquals("same-origin", policy,
                "只在本站内传 referrer:跨源不回传路径,同时保住真实的 Origin");
    }

    @Test
    @DisplayName("本站来源的登录请求放行")
    void allowsSameSiteLogin() throws Exception {
        ResponseEntity<String> response = login(baseUrl());
        assertEquals(200, response.getStatusCode().value(), "本站来源应当放行: " + response.getBody());
    }

    @Test
    @DisplayName("已登记的前端来源放行并回显允许来源")
    void allowsConfiguredOrigin() throws Exception {
        ResponseEntity<String> response = login(ALLOWED_ORIGIN);

        assertEquals(200, response.getStatusCode().value(), "已登记来源应当放行: " + response.getBody());
        assertEquals(ALLOWED_ORIGIN, response.getHeaders().getFirst("Access-Control-Allow-Origin"),
                "跨源响应要回显允许的来源,否则浏览器读不到响应");
    }

    @Test
    @DisplayName("不透明来源的登录请求拒绝:来源不可验证时不放行")
    void rejectsOpaqueOrigin() throws Exception {
        ResponseEntity<String> response = login("null");
        assertEquals(403, response.getStatusCode().value(),
                "Origin: null 无法与任何 authority 比对,不能猜成本站");
    }

    @Test
    @DisplayName("外部来源的登录请求拒绝")
    void rejectsForeignOrigin() throws Exception {
        ResponseEntity<String> response = login("https://evil.example");
        assertEquals(403, response.getStatusCode().value());
    }

    /**
     * 以指定来源发起 REST 登录。
     *
     * @param origin Origin 头取值
     * @return 登录响应
     * @throws Exception 请求体序列化失败
     */
    private ResponseEntity<String> login(String origin) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setOrigin(origin);
        return rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), headers),
                String.class);
    }
}
