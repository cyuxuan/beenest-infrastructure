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

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外置扩展包装载测试。
 * <p>第三方提供方应当能独立于本仓库交付:打一个 jar、放进约定目录、重启即可生效,
 * 不必改本项目的 Maven 依赖、也不必重新打包服务端。这里现场打一个 jar 丢进临时目录,
 * 验证平台在启动时真的把它认了出来。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamExternalProviderIntegrationTest extends IamIntegrationTestBase {

    /** 提供方工厂的服务声明文件名(与 SPI 的全限定名对应)。 */
    private static final String SERVICE_FILE =
            "META-INF/services/club.beenest.iam.core.spi.federation.IdentityProviderFactory";

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
        // 外置扩展包目录:必须在应用上下文启动前就位,装载发生在启动阶段
        registry.add("iam.provider.dir", () -> buildProviderJar().toString());
    }

    @Test
    @DisplayName("外置目录里的扩展包:启动时被装载,类型出现在提供方列表里")
    void externalProviderJarIsLoaded() throws Exception {
        Session admin = login();

        ResponseEntity<String> response = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/identity-providers/provider-types", null);
        assertEquals(200, response.getStatusCode().value(), "类型元数据接口应当可用: " + response.getBody());

        JsonNode external = null;
        for (JsonNode type : dataOf(response.getBody())) {
            if (ExternalTestProviderFactory.PROVIDER_ID.equals(type.path("providerId").asText())) {
                external = type;
                break;
            }
        }
        assertNotNull(external, "外置扩展包应当被装载: " + response.getBody());
        assertEquals("外置测试提供方", external.path("name").asText());
        assertTrue(external.path("configProperties").size() > 0, "外置类型同样要声明配置项");
    }

    /**
     * 现场打一个只含工厂类与服务声明的 jar,放进临时目录。
     * <p>刻意不引用测试类:外置扩展包是独立编译产物,只依赖核心 SPI。</p>
     */
    private static Path buildProviderJar() {
        try {
            Path dir = Files.createTempDirectory("iam-external-providers");
            Path jar = dir.resolve("external-test-provider.jar");
            URL location = ExternalTestProviderFactory.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            Path classFile = Path.of(location.toURI())
                    .resolve("club/beenest/iam/integration/ExternalTestProviderFactory.class");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new ZipEntry(SERVICE_FILE));
                out.write((ExternalTestProviderFactory.class.getName() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
                out.putNextEntry(new ZipEntry("club/beenest/iam/integration/ExternalTestProviderFactory.class"));
                out.write(Files.readAllBytes(classFile));
                out.closeEntry();
            }
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException("构造外置扩展包测试 jar 失败", e);
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
