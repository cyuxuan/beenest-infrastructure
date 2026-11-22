package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭据类登录闭环集成测试。
 * <p>链路:第三方凭据 → 平台签授权码 → 标准授权码流程换令牌。
 * 令牌由协议栈签发,签发、刷新、吊销都走标准流程,平台不另起一套。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamProviderGrantIntegrationTest extends IamIntegrationTestBase {

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

    private HttpServer wechat;

    @AfterEach
    void stopWechat() {
        if (wechat != null) {
            wechat.stop(0);
        }
    }

    @Test
    @DisplayName("凭据登录:自动建号、记录 openid、换到可用令牌")
    void credentialLoginCreatesUserAndIssuesToken() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-mini-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);
        String clientId = "mini-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId);

        // 1. 客户端密钥错误必须被拒
        ResponseEntity<String> bad = requestCode(alias, clientId, "wrong-secret", "js-code-1");
        assertTrue(bad.getStatusCode().is4xxClientError(),
                "密钥错误必须被拒绝: " + bad.getStatusCode() + " " + bad.getBody());

        // 2. 凭据换授权码
        ResponseEntity<String> codeResponse = requestCode(alias, clientId, clientSecret, "js-code-1");
        assertEquals(200, codeResponse.getStatusCode().value(),
                "凭据登录应当成功: " + codeResponse.getBody());
        String code = dataOf(codeResponse.getBody()).path("code").asText();
        String redirectUri = dataOf(codeResponse.getBody()).path("redirectUri").asText();
        assertTrue(code.length() > 20, "应当签发授权码: " + codeResponse.getBody());

        // 3. 用授权码到标准令牌端点换令牌
        ResponseEntity<String> tokenResponse = exchangeCode(clientId, clientSecret, code, redirectUri);
        assertEquals(200, tokenResponse.getStatusCode().value(),
                "授权码换令牌应当成功: " + tokenResponse.getBody());
        JsonNode token = objectMapper.readTree(tokenResponse.getBody());
        String accessToken = token.path("access_token").asText();
        assertTrue(accessToken.length() > 20, "应当签发访问令牌: " + tokenResponse.getBody());
        assertTrue(token.path("refresh_token").asText().length() > 0, "应当签发刷新令牌");

        // 4. 令牌能访问受保护接口
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(accessToken);
        ResponseEntity<String> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(null, bearer), String.class);
        assertEquals(200, me.getStatusCode().value(), "令牌应当能访问当前用户接口: " + me.getBody());
        assertEquals(alias + "-o-mini-1", dataOf(me.getBody()).path("username").asText(),
                "令牌应当指向刚建出来的外部账号");

        // 5. 用户已自动建出,且 openid 落在扩展属性里
        ResponseEntity<String> users = exchange(admin, HttpMethod.GET, "/api/v1/admin/users?pageSize=200", null);
        JsonNode external = null;
        for (JsonNode node : dataOf(users.getBody()).path("records")) {
            if ("EXTERNAL".equals(node.path("userType").asText())) {
                external = node;
                break;
            }
        }
        assertNotNull(external, "凭据登录应当自动建出外部账号");
        assertEquals("o-mini-1", external.path("attributes").path("openid").asText(),
                "openid 必须落进用户扩展属性");
    }

    @Test
    @DisplayName("刷新令牌可在标准端点换新令牌")
    void refreshTokenWorksOnStandardEndpoint() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-refresh-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);
        String clientId = "mini-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId);

        ResponseEntity<String> codeResponse = requestCode(alias, clientId, clientSecret, "js-code-1");
        assertEquals(200, codeResponse.getStatusCode().value(), "凭据登录应当成功: " + codeResponse.getBody());
        String code = dataOf(codeResponse.getBody()).path("code").asText();
        String redirectUri = dataOf(codeResponse.getBody()).path("redirectUri").asText();

        ResponseEntity<String> tokenResponse = exchangeCode(clientId, clientSecret, code, redirectUri);
        assertEquals(200, tokenResponse.getStatusCode().value(), "换令牌应当成功: " + tokenResponse.getBody());
        String refreshToken = objectMapper.readTree(tokenResponse.getBody()).path("refresh_token").asText();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String form = "grant_type=refresh_token&client_id=" + clientId + "&client_secret=" + clientSecret
                + "&refresh_token=" + refreshToken;
        ResponseEntity<String> refreshed = rest.exchange("/realms/default/oauth2/token", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
        assertEquals(200, refreshed.getStatusCode().value(), "刷新应当成功: " + refreshed.getBody());
        assertTrue(objectMapper.readTree(refreshed.getBody()).path("access_token").asText().length() > 0,
                "刷新应当换到新的访问令牌");
    }

    @Test
    @DisplayName("未获授权使用授权码的客户端:不能经凭据登录换到用户令牌")
    void clientWithoutAuthorizationCodeGrantIsRejected() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-nogrant-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);
        // 这个客户端只登记了 refresh_token:它没有授权码流程的资格
        String clientId = "mini-nogrant-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId, List.of("refresh_token"));

        ResponseEntity<String> response = requestCode(alias, clientId, clientSecret, "js-code-1");
        assertTrue(response.getStatusCode().is4xxClientError(),
                "没有授权码流程授权的客户端必须被拒绝: " + response.getStatusCode() + " " + response.getBody());
    }

    @Test
    @DisplayName("客户端密钥错误:要留下失败痕迹")
    void clientSecretFailureIsAudited() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-audit-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);
        String clientId = "mini-audit-" + System.nanoTime();
        createClient(admin, clientId, List.of("authorization_code", "refresh_token"));

        ResponseEntity<String> bad = requestCode(alias, clientId, "wrong-secret", "js-code-1");
        assertTrue(bad.getStatusCode().is4xxClientError(), "密钥错误必须被拒绝");

        ResponseEntity<String> events = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/login-events?eventType=LOGIN_FAILURE&pageSize=50", null);
        assertEquals(200, events.getStatusCode().value(), "登录事件应当可查: " + events.getBody());
        JsonNode failure = null;
        for (JsonNode node : dataOf(events.getBody()).path("records")) {
            if (clientId.equals(node.path("username").asText())) {
                failure = node;
                break;
            }
        }
        assertNotNull(failure, "客户端密钥错误必须留痕: " + events.getBody());
        assertEquals("PROVIDER", failure.path("loginType").asText(), "失败事件要标明是联邦登录入口");
        assertTrue(failure.path("failReason").asText().length() > 0, "失败原因不能为空");
    }

    @Test
    @DisplayName("同一客户端反复试错密钥:按客户端维度限流")
    void repeatedSecretFailuresAreThrottled() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-throttle-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);
        String clientId = "mini-throttle-" + System.nanoTime();
        createClient(admin, clientId, List.of("authorization_code", "refresh_token"));

        ResponseEntity<String> last = null;
        for (int i = 0; i < 21; i++) {
            last = requestCode(alias, clientId, "wrong-secret", "js-code-1");
        }
        assertNotNull(last);
        assertEquals(429, last.getStatusCode().value(),
                "反复试错密钥必须被限流: " + last.getStatusCode() + " " + last.getBody());
    }

    @Test
    @DisplayName("手机号登录:自动建号并写入已验证手机号,再次登录复用同一账号")
    void phoneLoginCreatesVerifiedUserAndReusesAccount() throws Exception {
        Session admin = login();
        String phone = "13700137001";
        String wechatBase = startWechat(phone);
        String alias = "wx-phone-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase, true);
        String clientId = "mini-phone-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId);

        // 1. 首次登录:按手机号建号
        String username = phoneLogin(alias, clientId, clientSecret, "js-phone-1", "phone-code-1");

        JsonNode user = findUserByPhone(admin, phone);
        assertNotNull(user, "手机号登录应当自动建出账号: " + username);
        assertEquals(username, user.path("username").asText());
        assertEquals("EXTERNAL", user.path("userType").asText());
        assertTrue(user.path("phoneVerified").asBoolean(), "手机号由微信担保,应按已验证入库");
        assertEquals("o-mini-1", user.path("attributes").path("openid").asText(),
                "openid 必须落进用户扩展属性");

        // 2. 同一微信身份再次登录:复用同一账号,不重复建号
        String second = phoneLogin(alias, clientId, clientSecret, "js-phone-2", "phone-code-2");
        assertEquals(username, second, "同一微信身份再次登录应当复用同一账号");
        assertEquals(1, countUsersByPhone(admin, phone), "同一手机号不应建出第二个账号");
    }

    @Test
    @DisplayName("手机号登录:手机号已在本地存在时归并到既有账号")
    void phoneLoginMergesPreProvisionedAccount() throws Exception {
        Session admin = login();
        String phone = "13600136002";
        String username = "staff-" + System.nanoTime();
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST, "/api/v1/admin/users",
                objectMapper.writeValueAsString(Map.of("username", username, "phone", phone,
                        "phoneVerified", true, "nickname", "预置员工")));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "预置本地账号应当成功: " + created.getBody());

        String wechatBase = startWechat(phone);
        String alias = "wx-merge-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase, true);
        String clientId = "mini-merge-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId);

        String loggedIn = phoneLogin(alias, clientId, clientSecret, "js-merge-1", "phone-code-1");

        assertEquals(username, loggedIn, "手机号已存在于本地时应当接管既有账号");
        assertEquals(1, countUsersByPhone(admin, phone), "归并不应建出重复账号");
    }

    @Test
    @DisplayName("手机号必填时未授权手机号:拒绝登录")
    void missingPhoneCodeIsRejectedWhenRequired() throws Exception {
        Session admin = login();
        String wechatBase = startWechat("13500135003");
        String alias = "wx-nophone-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase, true);
        String clientId = "mini-nophone-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId);

        ResponseEntity<String> response = requestCode(alias, clientId, clientSecret, "js-no-phone");

        assertTrue(response.getStatusCode().is4xxClientError(),
                "缺少手机号授权必须被拒绝: " + response.getStatusCode() + " " + response.getBody());
    }

    // ===== 内部实现 =====

    /**
     * 启动内嵌微信小程序接口,手机号接口固定返回默认号码。
     *
     * @return 接口基址
     * @throws IOException 端口绑定失败
     */
    private String startWechat() throws IOException {
        return startWechat("13800138000");
    }

    /**
     * 启动内嵌微信小程序接口。
     *
     * @param phone 手机号接口返回的号码
     * @return 接口基址
     * @throws IOException 端口绑定失败
     */
    private String startWechat(String phone) throws IOException {
        wechat = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wechat.createContext("/sns/jscode2session", exchange -> {
            byte[] bytes = "{\"openid\":\"o-mini-1\",\"session_key\":\"sk\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        wechat.createContext("/cgi-bin/stable_token", exchange -> {
            byte[] bytes = "{\"access_token\":\"wx-token\",\"expires_in\":7200}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        wechat.createContext("/wxa/business/getuserphonenumber", exchange -> {
            byte[] bytes = ("{\"errcode\":0,\"phone_info\":{\"purePhoneNumber\":\"" + phone + "\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        wechat.start();
        return "http://127.0.0.1:" + wechat.getAddress().getPort();
    }

    /**
     * 登记微信小程序提供方,不要求手机号授权。
     *
     * @param admin      管理员会话
     * @param alias      提供方别名
     * @param wechatBase 内嵌微信接口基址
     * @throws Exception 请求构造或发送失败
     */
    private void createMiniProvider(Session admin, String alias, String wechatBase) throws Exception {
        createMiniProvider(admin, alias, wechatBase, false);
    }

    /**
     * 登记微信小程序提供方。
     *
     * @param admin              管理员会话
     * @param alias              提供方别名
     * @param wechatBase         内嵌微信接口基址
     * @param phoneLoginRequired 是否要求手机号授权
     * @throws Exception 请求构造或发送失败
     */
    private void createMiniProvider(Session admin, String alias, String wechatBase, boolean phoneLoginRequired)
            throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "wx-app-id");
        config.put("clientSecret", "wx-app-secret");
        config.put("apiBaseUrl", wechatBase);
        config.put("phoneLoginRequired", String.valueOf(phoneLoginRequired));
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "wechat-mini",
                                "displayName", "微信小程序", "enabled", true, "config", config)));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "登记提供方应当成功: " + created.getBody());
    }

    /**
     * 登记机密客户端,默认授予授权码与刷新令牌。
     *
     * @param admin     管理员会话
     * @param clientKey 客户端标识
     * @return 明文密钥
     * @throws Exception 请求构造或发送失败
     */
    private String createClient(Session admin, String clientKey) throws Exception {
        return createClient(admin, clientKey, List.of("authorization_code", "refresh_token"));
    }

    /**
     * 登记机密客户端。
     *
     * @param admin      管理员会话
     * @param clientKey  客户端标识
     * @param grantTypes 授权类型
     * @return 明文密钥
     * @throws Exception 请求构造或发送失败
     */
    private String createClient(Session admin, String clientKey, List<String> grantTypes) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clientKey", clientKey);
        payload.put("clientName", "小程序后端");
        payload.put("clientType", "CONFIDENTIAL");
        payload.put("redirectUris", List.of("https://app.example.com/callback"));
        payload.put("scopes", List.of("openid", "profile"));
        payload.put("grantTypes", grantTypes);
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/clients", objectMapper.writeValueAsString(payload));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "登记客户端应当成功: " + created.getBody());
        return dataOf(created.getBody()).path("clientSecret").asText();
    }

    /**
     * 只提交 jsCode 的凭据登录。
     *
     * @param alias        提供方别名
     * @param clientId     客户端标识
     * @param clientSecret 客户端密钥
     * @param jsCode       小程序登录 code
     * @return 凭据换授权码的响应
     * @throws Exception 请求构造或发送失败
     */
    private ResponseEntity<String> requestCode(String alias, String clientId, String clientSecret, String jsCode)
            throws Exception {
        return requestCode(alias, clientId, clientSecret, Map.of("jsCode", jsCode));
    }

    /**
     * 凭据换授权码。
     *
     * @param alias        提供方别名
     * @param clientId     客户端标识
     * @param clientSecret 客户端密钥
     * @param credentials  凭据键值,内容由提供方定义
     * @return 凭据换授权码的响应
     * @throws Exception 请求构造或发送失败
     */
    private ResponseEntity<String> requestCode(String alias, String clientId, String clientSecret,
                                               Map<String, Object> credentials) throws Exception {
        HttpHeaders headers = jsonHeaders();
        Map<String, Object> payload = new HashMap<>();
        payload.put("provider", alias);
        payload.put("credentials", credentials);
        payload.put("clientId", clientId);
        payload.put("clientSecret", clientSecret);
        return rest.exchange("/api/v1/auth/provider-code?realm=default", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(payload), headers), String.class);
    }

    /**
     * 手机号凭据登录,走完整链路并返回令牌指向的登录名。
     *
     * @param alias        提供方别名
     * @param clientId     客户端标识
     * @param clientSecret 客户端密钥
     * @param jsCode       小程序登录 code
     * @param phoneCode    手机号授权 code
     * @return 令牌指向的登录名
     * @throws Exception 请求构造或发送失败
     */
    private String phoneLogin(String alias, String clientId, String clientSecret, String jsCode, String phoneCode)
            throws Exception {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("jsCode", jsCode);
        credentials.put("phoneCode", phoneCode);
        ResponseEntity<String> codeResponse = requestCode(alias, clientId, clientSecret, credentials);
        assertEquals(200, codeResponse.getStatusCode().value(), "手机号登录应当成功: " + codeResponse.getBody());
        String code = dataOf(codeResponse.getBody()).path("code").asText();
        String redirectUri = dataOf(codeResponse.getBody()).path("redirectUri").asText();

        ResponseEntity<String> tokenResponse = exchangeCode(clientId, clientSecret, code, redirectUri);
        assertEquals(200, tokenResponse.getStatusCode().value(), "换令牌应当成功: " + tokenResponse.getBody());
        String accessToken = objectMapper.readTree(tokenResponse.getBody()).path("access_token").asText();

        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(accessToken);
        ResponseEntity<String> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(null, bearer), String.class);
        assertEquals(200, me.getStatusCode().value(), "令牌应当能访问当前用户接口: " + me.getBody());
        return dataOf(me.getBody()).path("username").asText();
    }

    /**
     * 按手机号查找账号。
     *
     * @param admin 管理员会话
     * @param phone 手机号
     * @return 匹配的账号;不存在时返回 null
     * @throws Exception 请求构造或发送失败
     */
    private JsonNode findUserByPhone(Session admin, String phone) throws Exception {
        for (JsonNode node : listUsers(admin)) {
            if (phone.equals(node.path("phone").asText())) {
                return node;
            }
        }
        return null;
    }

    /**
     * 统计某手机号对应的账号数。
     *
     * @param admin 管理员会话
     * @param phone 手机号
     * @return 账号数
     * @throws Exception 请求构造或发送失败
     */
    private long countUsersByPhone(Session admin, String phone) throws Exception {
        long count = 0;
        for (JsonNode node : listUsers(admin)) {
            if (phone.equals(node.path("phone").asText())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 拉取用户列表,测试数据量小,单页足够。
     *
     * @param admin 管理员会话
     * @return 用户记录数组
     * @throws Exception 请求构造或发送失败
     */
    private JsonNode listUsers(Session admin) throws Exception {
        ResponseEntity<String> users = exchange(admin, HttpMethod.GET, "/api/v1/admin/users?pageSize=200", null);
        assertEquals(200, users.getStatusCode().value(), "用户列表应当可查: " + users.getBody());
        return dataOf(users.getBody()).path("records");
    }

    /** 标准授权码换令牌。 */
    private ResponseEntity<String> exchangeCode(String clientId, String clientSecret, String code, String redirectUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String form = "grant_type=authorization_code&client_id=" + clientId + "&client_secret=" + clientSecret
                + "&code=" + code + "&redirect_uri=" + redirectUri;
        return rest.exchange("/realms/default/oauth2/token", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }

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
