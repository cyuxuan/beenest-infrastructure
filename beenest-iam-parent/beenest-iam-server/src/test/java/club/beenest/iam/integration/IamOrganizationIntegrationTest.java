package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组织架构与岗位集成测试。
 * <p>锁定三条契约:</p>
 * <ol>
 *   <li>任职是权限来源:在某部门任某岗位,即继承该岗位挂载的角色;</li>
 *   <li>主部门唯一:换主部门后旧的自动失效,诊断视图里只能有一个主部门;</li>
 *   <li>含下级成员:父部门能看到整棵子树的成员,同一个人不重复出现。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamOrganizationIntegrationTest extends IamIntegrationTestBase {

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
    @DisplayName("任职岗位后继承岗位角色,且诊断里能看出岗位来源")
    void positionRolesFlowIntoEffectiveRoles() throws Exception {
        Session session = login();

        Long dept = createGroup(session, "org-dept", "研发中心", null, "DEPARTMENT");
        Long position = createPosition(session, "org-manager", "部门经理");
        Long role = createRole(session, "ORG_POSITION_ROLE", "岗位角色");
        Long user = createUser(session, "org-user-a");

        // 1. 岗位挂角色
        ResponseEntity<String> assignRoles = rest.exchange(
                "/api/v1/admin/positions/" + position + "/roles", HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("roleIds", List.of(role))), jsonHeaders(session)), String.class);
        assertEquals(200, assignRoles.getStatusCode().value(), "岗位挂角色应当成功: " + assignRoles.getBody());

        // 2. 任职:在研发中心担任部门经理,并作为主部门
        assignMembers(session, dept, List.of(
                Map.of("userId", user, "positionId", position, "primary", true)));

        // 3. 有效角色里必须出现该角色,且来源标注为岗位
        JsonNode roleNode = findEffectiveRole(session, user, "ORG_POSITION_ROLE");
        assertNotNull(roleNode, "任职岗位后应当继承岗位角色: " + effectiveBody(session, user));
        JsonNode positions = roleNode.path("positions");
        boolean fromPosition = false;
        for (JsonNode node : positions) {
            if (node.path("id").asLong() == position) {
                fromPosition = true;
            }
        }
        assertTrue(fromPosition, "角色来源应当标注岗位,实际: " + roleNode);

        // 4. 用户组织视图:部门、岗位、主部门标记一并返回
        JsonNode organizations = dataOf(get(session,
                "/api/v1/admin/users/" + user + "/organizations").getBody());
        JsonNode first = organizations.get(0);
        assertEquals(dept.longValue(), first.path("groupId").asLong(), "主部门应当排在最前");
        assertEquals(position.longValue(), first.path("positionId").asLong(), "任职岗位应当回显");
        assertTrue(first.path("primary").asBoolean(), "应当标记为主部门");
    }

    @Test
    @DisplayName("主部门至多一个:一次提交两个被拒,换主部门后旧的自动失效")
    void keepsAtMostOnePrimaryDepartment() throws Exception {
        Session session = login();

        Long deptA = createGroup(session, "pri-dept-a", "部门 A", null, "DEPARTMENT");
        Long deptB = createGroup(session, "pri-dept-b", "部门 B", null, "DEPARTMENT");
        Long user = createUser(session, "org-user-b");

        // 1. 一次提交里两个主部门 → 拒绝
        ResponseEntity<String> both = rest.exchange(
                "/api/v1/admin/groups/" + deptA + "/members", HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("members", List.of(
                        Map.of("userId", user, "primary", true)))), jsonHeaders(session)),
                String.class);
        assertEquals(200, both.getStatusCode().value(), "单主部门应当成功: " + both.getBody());
        assignMembers(session, deptB, List.of(Map.of("userId", user, "primary", true)));

        // 2. 换主部门后,组织视图里只剩一个主部门,且是后设的那个
        JsonNode organizations = dataOf(get(session,
                "/api/v1/admin/users/" + user + "/organizations").getBody());
        long primaryCount = 0;
        JsonNode primaryNode = null;
        for (JsonNode node : organizations) {
            if (node.path("primary").asBoolean()) {
                primaryCount++;
                primaryNode = node;
            }
        }
        assertEquals(1, primaryCount, "主部门至多一个,实际: " + organizations);
        assertNotNull(primaryNode);
        assertEquals(deptB.longValue(), primaryNode.path("groupId").asLong(),
                "主部门应当是后设置的部门 B");

        // 3. 同一次提交里给两个人都标主部门 → 拒绝
        Long user2 = createUser(session, "org-user-c");
        ResponseEntity<String> rejected = rest.exchange(
                "/api/v1/admin/groups/" + deptA + "/members", HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("members", List.of(
                        Map.of("userId", user, "primary", true),
                        Map.of("userId", user2, "primary", true)))), jsonHeaders(session)),
                String.class);
        assertTrue(rejected.getStatusCode().is4xxClientError(),
                "一次提交两个主部门必须被拒: " + rejected.getBody());
    }

    @Test
    @DisplayName("含下级成员:父部门能看到子树成员且同一人不重复")
    void listsSubtreeMembersWithoutDuplicates() throws Exception {
        Session session = login();

        Long parent = createGroup(session, "sub-parent", "技术中心", null, "DEPARTMENT");
        Long child = createGroup(session, "sub-child", "平台组", parent, "DEPARTMENT");
        Long user = createUser(session, "org-user-d");

        // 同一人同时在父部门与子部门任职
        assignMembers(session, parent, List.of(Map.of("userId", user, "primary", true)));
        assignMembers(session, child, List.of(Map.of("userId", user)));

        JsonNode page = dataOf(get(session,
                "/api/v1/admin/groups/" + parent + "/members?includeSub=true&pageSize=100").getBody());
        long hits = 0;
        for (JsonNode node : page.path("records")) {
            if (node.path("userId").asLong() == user) {
                hits++;
            }
        }
        assertEquals(1, hits, "同一人在含下级视图里只应出现一次: " + page.path("records"));

        // 不含下级时仍然能查到本部门成员
        JsonNode only = dataOf(get(session,
                "/api/v1/admin/groups/" + parent + "/members?pageSize=100").getBody());
        assertEquals(1, only.path("total").asInt(), "本部门成员应当是一个");
    }

    // ===== 内部实现 =====

    private record Session(String cookie, String csrf) {
    }

    private Session login() throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), plainJsonHeaders()),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return new Session(cookie, cookieValue(cookie, "XSRF-TOKEN"));
    }

    private Long createGroup(Session session, String code, String name, Long parentId,
                             String groupType) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("groupCode", code);
        body.put("groupName", name);
        body.put("groupType", groupType);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/groups", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "建部门应当成功: " + response.getBody());
        return idOf(response.getBody());
    }

    private Long createPosition(Session session, String code, String name) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/positions", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("positionCode", code, "positionName", name)), jsonHeaders(session)),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "建岗位应当成功: " + response.getBody());
        return idOf(response.getBody());
    }

    private Long createRole(Session session, String code, String name) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/roles", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("roleCode", code, "roleName", name, "containerType", "REALM")),
                        jsonHeaders(session)),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "建角色应当成功: " + response.getBody());
        return idOf(response.getBody());
    }

    private Long createUser(Session session, String username) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/users", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", username, "nickname", username)), jsonHeaders(session)),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "建用户应当成功: " + response.getBody());
        return idOf(response.getBody());
    }

    private void assignMembers(Session session, Long groupId, List<Map<String, Object>> members)
            throws Exception {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/admin/groups/" + groupId + "/members", HttpMethod.PUT,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("members", members)), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "设置任职应当成功: " + response.getBody());
    }

    private ResponseEntity<String> get(Session session, String url) {
        ResponseEntity<String> response = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "查询应当成功: " + url + " → " + response.getBody());
        return response;
    }

    private String effectiveBody(Session session, Long userId) throws Exception {
        return get(session, "/api/v1/admin/users/" + userId + "/effective-permissions").getBody();
    }

    /** 在有效角色里按编码找角色节点。 */
    private JsonNode findEffectiveRole(Session session, Long userId, String roleCode) throws Exception {
        JsonNode roles = dataOf(effectiveBody(session, userId)).path("roles");
        for (JsonNode node : roles) {
            if (roleCode.equals(node.path("roleCode").asText())) {
                return node;
            }
        }
        return null;
    }

    private Long idOf(String body) throws Exception {
        return objectMapper.readTree(body).path("data").path("id").asLong();
    }

    /** 登录请求还没有会话,只需要内容类型。 */
    private HttpHeaders plainJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders jsonHeaders(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", session.csrf());
        headers.set(HttpHeaders.COOKIE, session.cookie());
        return headers;
    }

    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String cookie : setCookies) {
            parts.add(cookie.split(";", 2)[0]);
        }
        return String.join("; ", parts);
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
