package club.beenest.iam.integration;

import club.beenest.iam.authz.session.IamSessionSweeperJob;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 分布式会话闭环集成测试。
 * <p>平台要能横向扩容,前提是浏览器会话不依赖某个特定进程。此前登录态存在容器
 * 会话里——容器会话只存在于创建它的那个进程,请求落到另一台实例就找不到会话,
 * 用户表现为"刚登录就被登出"。会话改由共享 Redis 承载后,任意实例都能服务
 * 同一个会话,平台才真正无状态。</p>
 * <p>这里用"删掉 Redis 里的会话键"来证明登录态确实只在共享存储里:
 * 若登录态还留在进程内,删 Redis 不会让请求掉登录态。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamDistributedSessionIntegrationTest extends IamIntegrationTestBase {

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

    @Autowired
    private RedisUtils redisUtils;

    @Autowired
    private UserSessionStore userSessionStore;

    @Autowired
    private IamSessionSweeperJob sweeperJob;

    @Test
    @DisplayName("登录态只存在于共享 Redis:会话标识可跨实例使用")
    void sessionStateLivesInSharedRedis() throws Exception {
        // 1. 登录:拿到平台自签的会话标识
        Session session = login();
        String sid = cookieValue(session.cookie(), "IAM_SESSION");
        assertNotNull(sid, "登录后应当下发平台签发的会话 Cookie: " + session.cookie());

        // 2. 会话状态在 Redis 里(不在进程内存里):任何实例都能取到同一份
        assertTrue(redisUtils.hasKey(IamUserSessionService.sessionKey(sid)),
                "会话快照应当在共享 Redis 里,这样任意实例都能服务同一个会话");

        // 3. 会话记录以同一个标识落库,会话管理/强制下线/登出广播都按它索引
        UserSessionModel record = userSessionStore.findBySid(sid);
        assertNotNull(record, "会话记录应当落库");
        assertEquals(UserSessionModel.STATE_ACTIVE, record.state());

        // 4. 把 Redis 里的会话键删掉,登录态随即失效——
        //    这正说明登录态完全由共享存储承载,进程内没有第二份
        redisUtils.delete(IamUserSessionService.sessionKey(sid));
        ResponseEntity<String> afterDelete = callWithCookie("/api/v1/auth/me", session.cookie());
        assertEquals(401, afterDelete.getStatusCode().value(),
                "会话键没了就该掉登录态:登录态不能还留在进程内存里");
    }

    @Test
    @DisplayName("会话键消失后,巡检把仍标记为活跃的记录收尾")
    void sweeperReapsDeadSessionRows() throws Exception {
        // 1. 登录并取出会话标识
        Session session = login();
        String sid = cookieValue(session.cookie(), "IAM_SESSION");
        assertNotNull(sid);

        // 2. 会话键消失(等价于用户关掉浏览器后 Redis 自然过期)
        redisUtils.delete(IamUserSessionService.sessionKey(sid));
        assertEquals(UserSessionModel.STATE_ACTIVE, userSessionStore.findBySid(sid).state(),
                "此刻数据库行还停在活跃,等着巡检收尾");

        // 3. 巡检一轮:记录被置为过期
        sweeperJob.sweepOnce();

        assertEquals(UserSessionModel.STATE_EXPIRED, userSessionStore.findBySid(sid).state(),
                "巡检应当把失效会话收尾,否则在线会话列表全是僵尸");
    }

    // ===== 内部实现 =====

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

    /** 带 Cookie 调用。 */
    private ResponseEntity<String> callWithCookie(String path, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, cookie);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
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
