package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OIDC 主链路集成测试。
 * <p>用真实的 PostgreSQL 与 Redis 跑完整协议栈:登录 → 授权码 + PKCE → 换令牌 →
 * 刷新 → 吊销。单元测试用桩件替掉了授权存储与 Redis,恰恰盖不住"吊销端点
 * 返回 200 但令牌照旧可用"这类只在真实栈上才暴露的缺陷。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamOidcFlowIntegrationTest extends IamIntegrationTestBase {

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

    private final SecureRandom random = new SecureRandom();

    @Test
    @DisplayName("授权码 + PKCE 全链路,且吊销后令牌立即失效")
    void authorizationCodeFlowAndRevocation() throws Exception {
        // 1. 登录:拿到会话 Cookie 与 CSRF 令牌
        TestSession session = login();

        // 2. 登记一个机密客户端
        String clientSecret = createClient(session, "it-client");

        // 3. 授权码 + PKCE:登录态直接复用,拿到 code
        AuthorizationResult authorization = authorize(session, "it-client");

        // 4. 换令牌
        TokenSet tokens = exchangeCode("it-client", clientSecret, authorization);
        assertNotNull(tokens.accessToken(), "应当签发访问令牌");
        assertNotNull(tokens.refreshToken(), "应当签发刷新令牌");

        // 5. 令牌可用
        assertEquals(200, callWithBearer("/api/v1/auth/me", tokens.accessToken()).getStatusCode().value());

        // 6. 吊销:RFC 7009 端点必须让令牌立即失效,而不是"返回 200 就完事"
        ResponseEntity<String> revoke = rest.exchange(
                "/realms/default/oauth2/revoke", HttpMethod.POST,
                basicAuth("it-client", clientSecret,
                        "token=" + tokens.accessToken()),
                String.class);
        assertEquals(200, revoke.getStatusCode().value());

        // 7. 吊销后访问令牌必须失效(此前实现只回 200,令牌仍可用)
        assertEquals(401, callWithBearer("/api/v1/auth/me", tokens.accessToken()).getStatusCode().value(),
                "吊销后访问令牌必须立即失效");
        // 8. 刷新令牌同样失效(整条授权作废,不再能换出新令牌)
        ResponseEntity<String> refresh = rest.exchange(
                "/realms/default/oauth2/token", HttpMethod.POST,
                basicAuth("it-client", clientSecret,
                        "grant_type=refresh_token&refresh_token=" + tokens.refreshToken()),
                String.class);
        assertTrue(refresh.getStatusCode().is4xxClientError(),
                "吊销后刷新令牌必须失效,实际返回: " + refresh.getStatusCode());
    }

    @Test
    @DisplayName("刷新后旧访问令牌立即失效(与 userinfo 口径一致)")
    void oldAccessTokenRejectedAfterRefresh() throws Exception {
        TestSession session = login();
        String clientSecret = createClient(session, "it-client-2");
        AuthorizationResult authorization = authorize(session, "it-client-2");
        TokenSet first = exchangeCode("it-client-2", clientSecret, authorization);

        // 刷新换新令牌
        ResponseEntity<String> refreshed = rest.exchange(
                "/realms/default/oauth2/token", HttpMethod.POST,
                basicAuth("it-client-2", clientSecret,
                        "grant_type=refresh_token&refresh_token=" + first.refreshToken()),
                String.class);
        assertEquals(200, refreshed.getStatusCode().value(), "刷新应当成功");
        JsonNode body = objectMapper.readTree(refreshed.getBody());
        String newAccessToken = body.get("access_token").asText();

        // 旧访问令牌必须失效:userinfo 端点早已拒绝它,业务接口不该继续放行
        assertEquals(401, callWithBearer("/api/v1/auth/me", first.accessToken()).getStatusCode().value(),
                "刷新后旧访问令牌必须立即失效");
        assertEquals(200, callWithBearer("/api/v1/auth/me", newAccessToken).getStatusCode().value(),
                "刷新后的新访问令牌应当可用");
    }

    @Test
    @DisplayName("未认证访问管理接口返回 401,页面跳转登录页")
    void unauthenticatedAccessIsRejected() {
        assertEquals(401, rest.getForEntity("/api/v1/admin/users", String.class).getStatusCode().value());
        ResponseEntity<String> portal = rest.getForEntity("/portal", String.class);
        assertTrue(portal.getStatusCode().is3xxRedirection(), "未登录访问页面应跳转登录页");
    }


    @Test
    @DisplayName("凭据模式(机器对机器)令牌可访问管理接口")
    void clientCredentialsTokenCanCallManagementApi() throws Exception {
        TestSession session = login();

        // 1. 建服务账号(机器身份,无口令)
        Long serviceAccountId = createServiceAccount(session, "svc-it");

        // 2. 给服务账号授予超级管理员角色
        assignSuperAdminRole(session, serviceAccountId);

        // 3. 建凭据模式客户端并绑定服务账号
        String secret = createClientWithCredentials(session, "m2m-client", serviceAccountId);

        // 4. 凭据模式换令牌
        ResponseEntity<String> tokenResponse = rest.exchange(
                "/realms/default/oauth2/token", HttpMethod.POST,
                basicAuth("m2m-client", secret, "grant_type=client_credentials"),
                String.class);
        assertEquals(200, tokenResponse.getStatusCode().value(),
                "凭据模式应当签发令牌: " + tokenResponse.getBody());
        String accessToken = objectMapper.readTree(tokenResponse.getBody()).get("access_token").asText();

        // 5. 令牌带得上权限(服务账号的权限全集),能访问管理接口
        assertEquals(200, callWithBearer("/api/v1/admin/users", accessToken).getStatusCode().value(),
                "凭据模式令牌应当能访问管理接口");
    }

    @Test
    @DisplayName("权限撤销后立即生效(权限代次失效机制)")
    void permissionRevocationTakesEffectImmediately() throws Exception {
        TestSession session = login();

        // 1. 服务账号 + 超级管理员角色
        Long serviceAccountId = createServiceAccount(session, "svc-revoke");
        assignSuperAdminRole(session, serviceAccountId);
        String secret = createClientWithCredentials(session, "m2m-revoke", serviceAccountId);

        // 2. 令牌带得上 user:view 权限
        String accessToken = clientCredentialsToken("m2m-revoke", secret);
        assertEquals(200, callWithBearer("/api/v1/admin/users", accessToken).getStatusCode().value(),
                "服务账号令牌应当能访问管理接口");

        // 3. 撤销该账号的 user:view 权限
        revokeUserPermission(session, "svc-revoke", "user:view");

        // 4. 重新签发的令牌必须立刻失去该权限(权限代次自增,缓存即刻失效)
        String afterToken = clientCredentialsToken("m2m-revoke", secret);
        assertEquals(403, callWithBearer("/api/v1/admin/users", afterToken).getStatusCode().value(),
                "权限撤销后新令牌必须立即失去该权限");
    }

    /** 凭据模式换令牌。 */
    private String clientCredentialsToken(String clientKey, String secret) throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "/realms/default/oauth2/token", HttpMethod.POST,
                basicAuth(clientKey, secret, "grant_type=client_credentials"),
                String.class);
        assertEquals(200, response.getStatusCode().value(),
                "凭据模式应当签发令牌: " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("access_token").asText();
    }

    // ===== 内部实现 =====

    /** 建服务账号用户(机器身份:无口令、不得交互式登录)。 */
    private Long createServiceAccount(TestSession session, String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("nickname", "集成测试服务账号");
        body.put("userType", "SERVICE");
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/users", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "建服务账号应当成功: " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("id").asLong();
    }

    /** 给用户授予超级管理员角色。 */
    private void assignSuperAdminRole(TestSession session, Long userId) throws Exception {
        List<Map<String, Object>> roles = dataList(
                rest.exchange("/api/v1/admin/roles?pageSize=50", HttpMethod.GET,
                        new HttpEntity<>(null, jsonHeaders(session)), String.class).getBody());
        Long roleId = null;
        for (Map<String, Object> role : roles) {
            if ("SUPER_ADMIN".equals(String.valueOf(role.get("roleCode")))) {
                roleId = Long.valueOf(String.valueOf(role.get("id")));
                break;
            }
        }
        assertNotNull(roleId, "应当存在 SUPER_ADMIN 角色");
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/users/" + userId + "/roles",
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("roleIds", List.of(roleId))),
                        jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "授予角色应当成功: " + response.getBody());
    }

    /** 建凭据模式客户端并绑定服务账号。 */
    private String createClientWithCredentials(TestSession session, String clientKey, Long serviceAccountId)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientKey", clientKey);
        body.put("clientName", "机器对机器客户端");
        body.put("clientType", "CONFIDENTIAL");
        body.put("redirectUris", List.of("http://127.0.0.1:9999/cb"));
        body.put("scopes", List.of("openid"));
        body.put("grantTypes", List.of("client_credentials"));
        body.put("serviceAccountUserId", serviceAccountId);
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "登记客户端应当成功: " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("clientSecret").asText();
    }

    /** 撤销用户对某个权限的持有(用户特批 REVOKE)。 */
    private void revokeUserPermission(TestSession session, String username, String permissionCode)
            throws Exception {
        ResponseEntity<String> permissions = rest.exchange(
                "/api/v1/admin/permissions?pageSize=200", HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        Long permissionId = null;
        for (Map<String, Object> permission : dataList(permissions.getBody())) {
            if (permissionCode.equals(String.valueOf(permission.get("permissionCode")))) {
                permissionId = Long.valueOf(String.valueOf(permission.get("id")));
                break;
            }
        }
        assertNotNull(permissionId, "应当存在权限: " + permissionCode);
        Long userId = null;
        for (Map<String, Object> user : dataList(
                rest.exchange("/api/v1/admin/users?pageSize=50", HttpMethod.GET,
                        new HttpEntity<>(null, jsonHeaders(session)), String.class).getBody())) {
            if (username.equals(String.valueOf(user.get("username")))) {
                userId = Long.valueOf(String.valueOf(user.get("id")));
                break;
            }
        }
        assertNotNull(userId, "应当存在用户: " + username);
        Map<String, Object> body = new HashMap<>();
        body.put("grantType", "REVOKE");
        body.put("reason", "集成测试:撤销权限");
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/users/" + userId + "/permission-overrides/" + permissionId,
                HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(),
                "撤销权限应当成功: " + response.getBody());
    }

    /** 解析分页响应里的 data 数组。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(String body) throws Exception {
        JsonNode data = objectMapper.readTree(body).path("data");
        // 分页响应的记录在 data.records 下;非分页响应直接就是数组
        JsonNode records = data.has("records") ? data.path("records") : data;
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : records) {
            result.add(objectMapper.convertValue(item, Map.class));
        }
        return result;
    }

    /** 登录会话(容器 Cookie + CSRF 令牌)。 */
    private record TestSession(String cookie, String csrf) {
    }

    /** 令牌对。 */
    private record TokenSet(String accessToken, String refreshToken, String idToken) {
    }

    /** 授权结果:授权码与配套的 PKCE 校验串(换令牌时必须成对使用)。 */
    private record AuthorizationResult(String code, String verifier) {
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

    /** 登记机密客户端,返回一次性明文密钥。 */
    private String createClient(TestSession session, String clientKey) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientKey", clientKey);
        body.put("clientName", "集成测试客户端");
        body.put("clientType", "CONFIDENTIAL");
        body.put("redirectUris", List.of("http://127.0.0.1:9999/cb"));
        body.put("scopes", List.of("openid", "profile"));
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "登记客户端应当成功: " + response.getBody());
        return objectMapper.readTree(response.getBody()).get("data").get("clientSecret").asText();
    }

    /** 走授权端点拿授权码(复用已登录会话)。 */
    private AuthorizationResult authorize(TestSession session, String clientKey) {
        String verifier = randomToken();
        String challenge = sha256Base64Url(verifier);
        // 显式构造 URI:RestTemplate 会对字符串模板再做一次编码,
        // 已转义的 %20 会被二次编码成 %2520,scope 随即失效
        URI uri = UriComponentsBuilder.fromPath("/realms/default/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientKey)
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:9999/cb")
                .queryParam("state", "it-state")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUri();
        ResponseEntity<String> response = rest.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(null, cookieHeaders(session)), String.class);
        // 授权成功 = 302 跳回客户端,授权码在 Location 里
        assertEquals(302, response.getStatusCode().value(),
                "授权端点应当重定向回客户端: " + response.getBody());
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "授权端点应当重定向回客户端");
        String code = queryParam(location, "code");
        assertNotNull(code, "重定向地址应当携带授权码: " + location);
        return new AuthorizationResult(code, verifier);
    }

    /** 授权码换令牌。 */
    private TokenSet exchangeCode(String clientKey, String clientSecret,
                                  AuthorizationResult authorization) throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "/realms/default/oauth2/token", HttpMethod.POST,
                basicAuth(clientKey, clientSecret,
                        "grant_type=authorization_code&code=" + authorization.code()
                                + "&redirect_uri=http://127.0.0.1:9999/cb"
                                + "&code_verifier=" + authorization.verifier()),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "换令牌应当成功: " + response.getBody());
        return parseTokens(response.getBody());
    }

    /** 解析令牌响应。 */
    private TokenSet parseTokens(String body) throws Exception {
        JsonNode node = objectMapper.readTree(body);
        return new TokenSet(
                node.path("access_token").asText(null),
                node.path("refresh_token").asText(null),
                node.path("id_token").asText(null));
    }

    /** 带 Bearer 令牌调用。 */
    private ResponseEntity<String> callWithBearer(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(null, headers), String.class);
    }

    /** 客户端认证 + 表单体的请求实体。 */
    private HttpEntity<String> basicAuth(String clientKey, String clientSecret, String form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientKey, clientSecret);
        return new HttpEntity<>(form, headers);
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

    /** 取 URL 查询参数。 */
    private static String queryParam(String url, String name) {
        int index = url.indexOf(name + "=");
        if (index < 0) {
            return null;
        }
        int start = index + name.length() + 1;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }

    /** 随机 token。 */
    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** PKCE challenge。 */
    private static String sha256Base64Url(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
