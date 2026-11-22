package club.beenest.iam.integration;

import club.beenest.iam.authz.audit.IamAuditRetentionJob;
import club.beenest.iam.persistence.entity.AuditLog;
import club.beenest.iam.persistence.mapper.AuditLogMapper;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计合规闭环集成测试。
 * <p>审计的价值建立在"记录不可篡改"之上,而这一点此前只在代码层自律:
 * 表注释写着"仅插入不允许修改删除",数据库层却没有任何约束,持有写权限的人
 * 可以直接改掉记录。同样缺失的还有留存期与导出——审计表只增不减,也没有把
 * 数据带出系统的正规通道。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamAuditComplianceIntegrationTest extends IamIntegrationTestBase {

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
        // 留存期设为 1 天:便于验证到期记录确实会被清掉
        registry.add("iam.audit.retention-days", () -> "1");
    }

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private IamAuditRetentionJob retentionJob;

    @Test
    @DisplayName("审计记录只可追加:直接改写被数据库拒绝")
    void auditLogRejectsUpdate() {
        // 1. 落一条审计记录
        AuditLog log = new AuditLog();
        log.setRealmId(realmIdOfDefault());
        log.setOperatorName("audit-it");
        log.setAction("IT_ACTION");
        log.setDescription("集成测试写入的审计记录");
        log.setTargetType("IT");
        log.setOccurredTime(LocalDateTime.now());
        auditLogMapper.insert(log);
        assertNotNull(log.getId());

        // 2. 试图改写它:数据库层必须拒绝
        assertThrows(RuntimeException.class, () ->
                jdbcTemplate.update("UPDATE iam_audit_log SET description = '被篡改' WHERE id = " + log.getId()),
                "审计记录不允许被改写");
    }

    @Test
    @DisplayName("留存期到期:过期记录被清理,清理动作本身留痕")
    void expiredAuditRecordsArePurged() {
        // 1. 造一条三天前的记录(已超留存期)
        AuditLog old = new AuditLog();
        old.setRealmId(realmIdOfDefault());
        old.setOperatorName("audit-it");
        old.setAction("IT_OLD_ACTION");
        old.setDescription("超期记录");
        old.setTargetType("IT");
        old.setOccurredTime(LocalDateTime.now().minusDays(3));
        auditLogMapper.insert(old);

        // 2. 跑一次清理
        retentionJob.purgeExpired();

        // 3. 过期记录应当已被清掉
        List<AuditLog> remaining = auditLogMapper.selectByCondition(
                realmIdOfDefault(), "IT_OLD_ACTION", null, null, null, null);
        assertTrue(remaining.isEmpty(), "超期审计记录应当被清理,实际还剩 " + remaining.size() + " 条");

        // 4. 清理动作本身留痕:否则"谁清掉了多少审计"无从追溯
        List<AuditLog> purgeLogs = auditLogMapper.selectByCondition(
                realmIdOfDefault(), "AUDIT_PURGE", null, null, null, null);
        assertFalse(purgeLogs.isEmpty(), "清理动作应当留痕");
    }

    @Test
    @DisplayName("审计可按条件导出为 CSV")
    void auditLogsCanBeExported() throws Exception {
        Session admin = login();
        // 1. 先制造一条可识别的记录(建一个用户即可触发审计)
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST, "/api/v1/admin/users",
                "{\"username\":\"audit-export-user\",\"nickname\":\"导出测试\"}");
        assertTrue(created.getStatusCode().is2xxSuccessful(), "建用户应当成功: " + created.getBody());

        // 2. 导出
        ResponseEntity<byte[]> export = rest.exchange(
                "/api/v1/admin/audit-logs/export?action=USER_CREATE", HttpMethod.GET,
                new HttpEntity<>(null, cookieHeaders(admin)), byte[].class);
        assertEquals(200, export.getStatusCode().value(), "导出应当成功");
        String csv = new String(export.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("操作人"), "CSV 应当有表头: " + csv);
        assertTrue(csv.contains("USER_CREATE"), "CSV 应当含导出的记录: " + csv);
        // 带 BOM:没有它 Excel 打开中文列会乱码
        assertEquals('﻿', csv.charAt(0), "CSV 应当带 UTF-8 BOM");
    }

    // ===== 内部实现 =====

    /** 默认域主键。 */
    private Long realmIdOfDefault() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM iam_realm WHERE realm_key = 'default'", Long.class);
    }

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

    /** 管理员发起的请求(带 CSRF 令牌)。 */
    private ResponseEntity<String> exchange(Session admin, HttpMethod method, String path, String body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", admin.csrf());
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    /** 仅 Cookie 的请求头。 */
    private HttpHeaders cookieHeaders(Session session) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, session.cookie());
        return headers;
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
