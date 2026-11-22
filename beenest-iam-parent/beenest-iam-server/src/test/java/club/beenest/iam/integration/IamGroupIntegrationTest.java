package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户组集成测试。
 * <p>组是权限主体:成员经组继承组上的角色。层级只表达归属,不参与继承。
 * 这里锁定四类契约:树的装配与筛选、层级成环的拦截、父级可清空、删组的子组归属。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamGroupIntegrationTest extends IamIntegrationTestBase {

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
    @DisplayName("组树按层级装配,筛选命中节点时保留其祖先")
    void buildsTreeAndKeepsAncestorsOnFilter() throws Exception {
        Session session = login();

        // 1. 造一棵三层树:中心 / 平台 / 应用
        Long center = createGroup(session, "it-center", "中心", null);
        Long platform = createGroup(session, "it-platform", "平台组", center);
        Long app = createGroup(session, "it-app", "应用组", platform);

        // 2. 树接口按层级返回,父在子前
        List<JsonNode> tree = treeNodes(session, null, null);
        JsonNode centerNode = findNode(tree, center);
        assertNotNull(centerNode, "树中应当有中心组");
        JsonNode platformNode = findNode(toList(centerNode.path("children")), platform);
        assertNotNull(platformNode, "中心组下应当有平台组");
        JsonNode appNode = findNode(toList(platformNode.path("children")), app);
        assertNotNull(appNode, "平台组下应当有应用组");
        assertEquals("中心 / 平台组 / 应用组", appNode.path("path").asText(),
                "路径应当自顶向下拼出");

        // 3. 只筛最底层:祖先必须一并保留,否则命中节点失去上下文
        List<JsonNode> filtered = treeNodes(session, null, "应用组");
        assertFalse(filtered.isEmpty(), "筛选应当命中");
        assertNotNull(findNode(filtered, center), "命中节点的祖先应当保留");
        assertNotNull(findNode(filtered, app), "命中节点本身应当在结果里");
    }

    @Test
    @DisplayName("上级组不能是自己的下级(层级成环被拦截)")
    void rejectsCyclicParent() throws Exception {
        Session session = login();

        Long parent = createGroup(session, "cyc-parent", "环父", null);
        Long child = createGroup(session, "cyc-child", "环子", parent);

        // 1. 父组挂到自己的子组下 → 必须被拒
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/groups/" + parent, HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("groupName", "环父", "parentId", child)), jsonHeaders(session)),
                String.class);
        assertTrue(response.getStatusCode().is4xxClientError(),
                "挂到自己的下级下必须被拒,实际: " + response.getBody());

        // 2. 父组挂到自身 → 同样被拒
        ResponseEntity<String> self = rest.exchange(
                "/api/v1/admin/groups/" + parent, HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("groupName", "环父", "parentId", parent)), jsonHeaders(session)),
                String.class);
        assertTrue(self.getStatusCode().is4xxClientError(), "挂到自身必须被拒");
    }

    @Test
    @DisplayName("上级组可清空:子组能提升回顶级组")
    void clearsParent() throws Exception {
        Session session = login();

        Long parent = createGroup(session, "clr-parent", "清空父", null);
        Long child = createGroup(session, "clr-child", "清空子", parent);
        assertNotNull(groupNode(session, child).path("parentId").asLong(-1) >= 0 ? 1 : null,
                "建组后应当挂在父组下");

        // 显式清空上级组
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/groups/" + child, HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("groupName", "清空子", "clearParent", true)), jsonHeaders(session)),
                String.class);
        assertEquals(200, response.getStatusCode().value(),
                "清空上级组应当成功: " + response.getBody());
        assertTrue(groupNode(session, child).path("parentId").isNull()
                        || groupNode(session, child).path("parentId").isMissingNode(),
                "清空后不应再有上级组");
    }

    @Test
    @DisplayName("删组后子组提升到被删组的上一级,不留悬空上级")
    void reparentsChildrenOnDelete() throws Exception {
        Session session = login();

        Long root = createGroup(session, "del-root", "删除根", null);
        Long mid = createGroup(session, "del-mid", "删除中", root);
        Long leaf = createGroup(session, "del-leaf", "删除叶", mid);

        // 删中间层
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/groups/" + mid, HttpMethod.DELETE,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "删除应当成功: " + response.getBody());

        // 子组应被提升到根下,而不是指向已删除的组
        JsonNode leafNode = groupNode(session, leaf);
        assertEquals(root.longValue(), leafNode.path("parentId").asLong(-1),
                "子组应当提升到被删组的上一级");
    }

    @Test
    @DisplayName("组成员可按主键取全量,可分页查看,用户可反查所属组")
    void memberLookupAndUserGroups() throws Exception {
        Session session = login();

        Long group = createGroup(session, "mem-group", "成员组", null);
        // 建两个用户并加入组
        Long userA = createUser(session, "mem-user-a");
        Long userB = createUser(session, "mem-user-b");
        ResponseEntity<String> assign = rest.exchange(
                "/api/v1/admin/groups/" + group + "/members", HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("members", List.of(Map.of("userId", userA), Map.of("userId", userB)))),
                        jsonHeaders(session)),
                String.class);
        assertEquals(200, assign.getStatusCode().value(), "分配成员应当成功: " + assign.getBody());

        // 1. 成员主键:分配是覆盖式的,必须能拿到完整集合
        ResponseEntity<String> ids = rest.exchange(
                "/api/v1/admin/groups/" + group + "/member-ids", HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        assertEquals(200, ids.getStatusCode().value());
        List<Long> memberIds = new ArrayList<>();
        for (JsonNode node : dataOf(ids.getBody())) {
            memberIds.add(node.asLong());
        }
        assertTrue(memberIds.contains(userA) && memberIds.contains(userB),
                "成员主键应当包含两个用户: " + memberIds);

        // 2. 成员分页
        ResponseEntity<String> page = rest.exchange(
                "/api/v1/admin/groups/" + group + "/members?pageNum=1&pageSize=1", HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        assertEquals(200, page.getStatusCode().value());
        JsonNode pageNode = dataOf(page.getBody());
        assertEquals(2, pageNode.path("total").asInt(), "总数应当是两个成员");

        // 3. 用户反查所属组
        ResponseEntity<String> userGroups = rest.exchange(
                "/api/v1/admin/users/" + userA + "/organizations", HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        assertEquals(200, userGroups.getStatusCode().value());
        boolean contains = false;
        for (JsonNode node : dataOf(userGroups.getBody())) {
            if (node.path("groupId").asLong() == group) {
                contains = true;
            }
        }
        assertTrue(contains, "用户反查应当包含所属组");
    }

    // ===== 内部实现 =====

    /** 登录会话。 */
    private record Session(String cookie, String csrf) {
    }

    /** 登录并取会话。 */
    private Session login() throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), jsonHeaders()),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        String csrf = cookieValue(cookie, "XSRF-TOKEN");
        assertNotNull(csrf, "登录后应当下发 XSRF-TOKEN");
        return new Session(cookie, csrf);
    }

    /** 造一个组,返回主键。 */
    private Long createGroup(Session session, String code, String name, Long parentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("groupCode", code);
        body.put("groupName", name);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/groups", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "建组应当成功: " + response.getBody());
        return objectMapper.readTree(response.getBody()).path("data").path("id").asLong();
    }

    /** 造一个用户,返回主键。 */
    private Long createUser(Session session, String username) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/users", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", username, "nickname", username)), jsonHeaders(session)),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "建用户应当成功: " + response.getBody());
        return objectMapper.readTree(response.getBody()).path("data").path("id").asLong();
    }

    /** 取组树。 */
    private List<JsonNode> treeNodes(Session session, String groupCode, String groupName) throws Exception {
        StringBuilder url = new StringBuilder("/api/v1/admin/groups/tree");
        boolean first = true;
        if (groupCode != null) {
            url.append(first ? "?" : "&").append("groupCode=").append(groupCode);
            first = false;
        }
        if (groupName != null) {
            url.append(first ? "?" : "&").append("groupName=").append(groupName);
        }
        ResponseEntity<String> response = rest.exchange(url.toString(), HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "取组树应当成功: " + response.getBody());
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node : dataOf(response.getBody())) {
            nodes.add(node);
        }
        return nodes;
    }

    /** 在树里找指定主键的节点。 */
    private static JsonNode findNode(List<JsonNode> nodes, Long id) {
        for (JsonNode node : nodes) {
            if (node.path("id").asLong() == id) {
                return node;
            }
            JsonNode found = findNode(toList(node.path("children")), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** JsonNode 数组 → List。 */
    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        if (array != null) {
            for (JsonNode node : array) {
                list.add(node);
            }
        }
        return list;
    }

    /** 查某个组在树中的节点。 */
    private JsonNode groupNode(Session session, Long id) throws Exception {
        JsonNode node = findNode(treeNodes(session, null, null), id);
        assertNotNull(node, "树中应当能找到组: " + id);
        return node;
    }

    /** JSON 请求头(含 CSRF 令牌与 Cookie)。 */
    private HttpHeaders jsonHeaders(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", session.csrf());
        headers.set(HttpHeaders.COOKIE, session.cookie());
        return headers;
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
