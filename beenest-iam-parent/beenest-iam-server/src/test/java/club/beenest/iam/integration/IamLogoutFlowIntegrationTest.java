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

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门户退出链路集成测试。
 * <p>退出是表单 POST,由 {@code CsrfFilter} 按<b>参数名</b>校验令牌;字段名写错时校验读不到
 * 令牌,请求被拒成 403 并落到错误页,用户看到的是"退出后进无权限页"。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamLogoutFlowIntegrationTest extends IamIntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    /** 门户页面内嵌的 CSRF 令牌 */
    private static final Pattern CSRF_META =
            Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]*)\"");

    /** 门户页面内嵌的 CSRF 参数名 */
    private static final Pattern CSRF_PARAMETER_META =
            Pattern.compile("<meta name=\"_csrf_parameter\" content=\"([^\"]*)\"");

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
    @DisplayName("门户退出:按页面内嵌的参数名提交令牌即可注销并回到登录页")
    void portalLogoutRedirectsToLogin() throws Exception {
        String cookie = login();
        PortalPage portal = openPortal(cookie);
        assertTrue(portal.parameterName() != null && !portal.parameterName().isBlank(),
                "门户页面应当内嵌 CSRF 参数名");
        assertTrue(!portal.token().isBlank(), "门户页面内嵌的 CSRF 令牌不应为空");

        ResponseEntity<String> logout = submitLogout(cookie, portal.parameterName(), portal.token());
        assertEquals(302, logout.getStatusCode().value(), "退出应当被接受");
        assertTrue(String.valueOf(logout.getHeaders().getFirst(HttpHeaders.LOCATION)).endsWith("/login"),
                "退出后应当回到登录页: " + logout.getHeaders().getFirst(HttpHeaders.LOCATION));

        // 退出后会话已结束:再访问门户会被送回登录页
        ResponseEntity<String> after = rest.exchange("/portal", HttpMethod.GET,
                new HttpEntity<>(null, cookieHeaders(cookie)), String.class);
        assertEquals(302, after.getStatusCode().value(), "退出后门户不应再可访问");
        assertTrue(String.valueOf(after.getHeaders().getFirst(HttpHeaders.LOCATION)).contains("/login"),
                "退出后应当跳登录页: " + after.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    @DisplayName("门户退出:字段名写成请求头名时令牌读不到,请求被拒")
    void logoutWithHeaderNameAsFieldNameIsRejected() throws Exception {
        String cookie = login();
        PortalPage portal = openPortal(cookie);

        // 这是修复前的写法:字段名取了 headerName(X-XSRF-TOKEN),CsrfFilter 按 _csrf 找不到令牌
        ResponseEntity<String> logout = submitLogout(cookie, "X-XSRF-TOKEN", portal.token());
        assertEquals(403, logout.getStatusCode().value(), "字段名错误时应当被拒");
    }

    @Test
    @DisplayName("门户退出:不带令牌时请求被拒")
    void logoutWithoutTokenIsRejected() throws Exception {
        String cookie = login();
        ResponseEntity<String> logout = submitLogout(cookie, null, null);
        assertEquals(403, logout.getStatusCode().value(), "缺少令牌时应当被拒");
    }

    /** 登录并返回会话 Cookie。 */
    private String login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertNotNull(cookie);
        return cookie;
    }

    /** 打开门户并取出页面内嵌的 CSRF 令牌与参数名。 */
    private PortalPage openPortal(String cookie) {
        ResponseEntity<String> portal = rest.exchange("/portal", HttpMethod.GET,
                new HttpEntity<>(null, cookieHeaders(cookie)), String.class);
        assertEquals(200, portal.getStatusCode().value(), "门户应当可访问: " + portal.getBody());
        return new PortalPage(group(CSRF_META, portal.getBody()), group(CSRF_PARAMETER_META, portal.getBody()));
    }

    /** 提交退出表单;参数名为 null 时提交空体。 */
    private ResponseEntity<String> submitLogout(String cookie, String parameterName, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.COOKIE, cookie);
        String body = parameterName == null ? "" : parameterName + "=" + token;
        return rest.exchange("/logout", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static HttpHeaders cookieHeaders(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        return headers;
    }

    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    /** 门户页面里与退出相关的内嵌值。 */
    private record PortalPage(String token, String parameterName) {
    }
}
