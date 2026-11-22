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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 表单登录会话集成测试。
 * <p>一次表单登录只签发一个会话,同浏览器重复登录时旧会话置为被顶下线。
 * 这两条契约要用真实过滤器链验证:会话上下文仓库若在登录成功前补建会话,
 * 一次登录会同时落两条记录,在线会话列表里多出无人使用的会话。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamFormLoginSessionIntegrationTest extends IamIntegrationTestBase {

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

    /** 会话 Cookie 名 */
    private static final String SESSION_COOKIE = "IAM_SESSION";
    /** 登录页隐藏字段中的 CSRF 令牌 */
    private static final Pattern CSRF_INPUT =
            Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Test
    @DisplayName("表单登录每次只签发一个会话,重复登录把旧会话置为被顶下线")
    void formLoginIssuesSingleSessionAndReplacesPrevious() throws Exception {
        long activeBefore = sessionCount("ACTIVE");
        long replacedBefore = sessionCount("REPLACED");

        Login first = formLogin(null);
        assertEquals(1, first.sessionCookies().size(),
                "一次登录只能签发一个会话,实际: " + first.sessionCookies());
        assertEquals(activeBefore + 1, sessionCount("ACTIVE"), "一次登录只能落一条在线会话");
        assertEquals(replacedBefore, sessionCount("REPLACED"), "首次登录没有可替换的会话");

        Login second = formLogin(first.cookies());
        assertEquals(1, second.sessionCookies().size(),
                "重复登录只能签发一个会话,实际: " + second.sessionCookies());
        assertEquals(activeBefore + 1, sessionCount("ACTIVE"),
                "重复登录后旧会话下线,在线会话总数不变");
        assertEquals(replacedBefore + 1, sessionCount("REPLACED"), "旧会话应记为被顶下线");
    }

    // ===== 内部实现 =====

    /** 登录结果(响应后的 Cookie 与本次签发的会话 Cookie)。 */
    private record Login(String cookies, List<String> sessionCookies) {
    }

    /**
     * 走登录页表单完成一次登录。
     *
     * @param cookies 浏览器已有的 Cookie;可为空
     * @return 登录结果
     * @throws Exception 请求失败
     */
    private Login formLogin(String cookies) throws Exception {
        HttpHeaders pageHeaders = new HttpHeaders();
        if (cookies != null && !cookies.isBlank()) {
            pageHeaders.set(HttpHeaders.COOKIE, cookies);
        }
        ResponseEntity<String> page = rest.exchange("/login?realm=default&return_to=%2Fconsole",
                HttpMethod.GET, new HttpEntity<>(pageHeaders), String.class);
        assertEquals(200, page.getStatusCode().value(), "登录页应当可访问");

        String csrf = csrfToken(page.getBody());
        String requestCookies = mergeCookies(page.getHeaders().get(HttpHeaders.SET_COOKIE), cookies);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.COOKIE, requestCookies);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "admin");
        form.add("password", ADMIN_PASSWORD);
        form.add("realm", "default");
        form.add("return_to", "/console");
        form.add("_csrf", csrf);

        ResponseEntity<String> response = rest.exchange("/login?realm=default&return_to=%2Fconsole",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        assertEquals(302, response.getStatusCode().value(), "表单登录应当重定向: " + response.getBody());

        List<String> sessionCookies = response.getHeaders()
                .getOrDefault(HttpHeaders.SET_COOKIE, List.of()).stream()
                .filter(cookie -> cookie.startsWith(SESSION_COOKIE + "="))
                .map(cookie -> cookie.split(";", 2)[0])
                .toList();
        return new Login(mergeCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE), cookies),
                sessionCookies);
    }

    /**
     * 从登录页 HTML 取 CSRF 令牌。
     *
     * @param html 登录页内容
     * @return 令牌值
     */
    private static String csrfToken(String html) {
        Matcher matcher = CSRF_INPUT.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            throw new AssertionError("登录页没有渲染 CSRF 令牌");
        }
        return matcher.group(1);
    }

    /**
     * 合并响应下发的 Cookie 与浏览器已有 Cookie,同名以后者为准。
     *
     * @param setCookies 响应 Set-Cookie 头;可为空
     * @param existing   浏览器已有 Cookie;可为空
     * @return Cookie 请求头取值
     */
    private static String mergeCookies(List<String> setCookies, String existing) {
        Map<String, String> merged = new LinkedHashMap<>();
        collect(merged, existing);
        if (setCookies != null) {
            collect(merged, String.join("; ", setCookies.stream()
                    .map(cookie -> cookie.split(";", 2)[0]).toList()));
        }
        return merged.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    /**
     * 解析 Cookie 串并写入映射。
     *
     * @param target 目标映射
     * @param cookies Cookie 串;可为空
     */
    private static void collect(Map<String, String> target, String cookies) {
        if (cookies == null || cookies.isBlank()) {
            return;
        }
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            int index = trimmed.indexOf('=');
            if (index > 0) {
                target.put(trimmed.substring(0, index), trimmed.substring(index + 1));
            }
        }
    }

    /**
     * 管理员的会话记录数。
     *
     * @param state 会话状态
     * @return 记录数
     */
    private long sessionCount(String state) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from iam_user_session s join iam_user u on u.id = s.user_id "
                        + "where u.username = 'admin' and s.state = ?",
                Long.class, state);
        return count == null ? 0 : count;
    }
}
