package club.beenest.iam.authz.account;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.LoginEvent;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamLoginEventManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 个人数据导出测试。
 * <p>导出是合规交付物,断言分两类:该有的段落一个不能少;不该出现的凭证密钥一个都不能有。</p>
 */
class PersonalDataExportServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long REALM_ID = 1L;
    private static final String USERNAME = "alice";

    private PersonalDataExportService service;

    @BeforeEach
    void setUp() {
        AccountOverviewService overviewService = mock(AccountOverviewService.class);
        IamUserManager userManager = mock(IamUserManager.class);
        UserProvider userProvider = mock(UserProvider.class);
        IamEffectiveRoleResolver roleResolver = mock(IamEffectiveRoleResolver.class);
        IamGroupManager groupManager = mock(IamGroupManager.class);
        IamLoginEventManager loginEventManager = mock(IamLoginEventManager.class);

        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 15, 0);
        when(overviewService.overview("sid-current")).thenReturn(overview(now));

        User user = new User();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setNickname("爱丽丝");
        user.setEmail("alice@example.com");
        user.setEmailVerified(true);
        user.setUserType("INTERNAL");
        user.setCreatedTime(now);
        when(userManager.getById(USER_ID)).thenReturn(user);

        when(userProvider.findById(USER_ID)).thenReturn(Optional.of(new UserModel(
                USER_ID, REALM_ID, USERNAME, "爱丽丝", "alice@example.com", null,
                1, "INTERNAL", true, 3, Map.of("department", "研发"))));

        Role role = new Role();
        role.setRoleCode("iam-admin");
        role.setRoleName("身份管理员");
        when(roleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(role));

        Group group = new Group();
        group.setGroupCode("rd");
        group.setGroupName("研发部");
        when(groupManager.listByUserId(USER_ID)).thenReturn(List.of(group));

        LoginEvent event = new LoginEvent();
        event.setEventType("LOGIN_SUCCESS");
        event.setLoginType("PASSWORD");
        event.setIp("127.0.0.1");
        event.setOccurredTime(now);
        when(loginEventManager.page(anyLong(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageResult<>(1, 50, 1, 1, List.of(event)));

        service = new PersonalDataExportService(overviewService, userManager, userProvider,
                roleResolver, groupManager, loginEventManager);

        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, REALM_ID, "default", USERNAME,
                "爱丽丝", 0, List.of(), Map.of(), -1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("导出包含资料、角色、组、凭证、授权、设备与登录历史")
    @SuppressWarnings("unchecked")
    void exportContainsAllSections() {
        Map<String, Object> document = service.export("sid-current");

        assertNotNull(document.get("exportedAt"));
        assertEquals("default", document.get("realm"));
        assertEquals(USERNAME, ((Map<String, Object>) document.get("account")).get("username"));

        Map<String, Object> profile = (Map<String, Object>) document.get("profile");
        assertEquals("爱丽丝", profile.get("nickname"));
        assertEquals(Map.of("department", "研发"), profile.get("attributes"));

        assertEquals(1, ((List<?>) document.get("roles")).size());
        assertEquals(1, ((List<?>) document.get("groups")).size());
        assertEquals(1, ((List<?>) document.get("authorizedApplications")).size());
        assertEquals(1, ((List<?>) document.get("sessions")).size());
        assertEquals(1, ((List<?>) document.get("recentLoginEvents")).size());
    }

    @Test
    @DisplayName("导出的凭证只含元数据,不含任何密钥字段")
    @SuppressWarnings("unchecked")
    void exportNeverLeaksSecrets() {
        Map<String, Object> credentials =
                (Map<String, Object>) service.export("sid-current").get("credentials");

        assertTrue((Boolean) credentials.get("passwordSet"));
        assertFalse(credentials.containsKey("secret"));
        assertFalse(credentials.containsKey("secretData"));
        assertFalse(credentials.containsKey("credentialData"));
        assertFalse(credentials.containsKey("hashes"));

        List<Map<String, Object>> passkeys = (List<Map<String, Object>>) credentials.get("passkeys");
        assertEquals(1, passkeys.size());
        assertFalse(passkeys.get(0).containsKey("credentialId"));
        assertFalse(passkeys.get(0).containsKey("userHandle"));
    }

    @Test
    @DisplayName("授权范围导出原始 scope 标识而非展示名")
    @SuppressWarnings("unchecked")
    void exportUsesRawScopeCodes() {
        List<Map<String, Object>> consents =
                (List<Map<String, Object>>) service.export("sid-current").get("authorizedApplications");

        assertEquals(List.of("openid", "profile"), consents.get(0).get("scopes"));
        assertEquals("demo", consents.get(0).get("clientKey"));
    }

    /**
     * 构造一份用户中心数据。
     *
     * @param now 统一时间戳
     * @return 用户中心数据
     */
    private static AccountOverview overview(LocalDateTime now) {
        return new AccountOverview(
                new AccountOverview.Profile(USERNAME, "爱丽丝", "alice@example.com", true,
                        null, false, "default", "INTERNAL"),
                new AccountOverview.Credentials(true, true, 6, true, List.of(
                        new club.beenest.iam.authz.rest.dto.WebAuthnCredentialResponse(1L, "MacBook", "aaguid",
                                List.of("internal"), true, true, now, now))),
                List.of(new AccountOverview.ConsentItem("demo", "示例应用", List.of(
                        new AccountOverview.ScopeItem("openid", "身份标识"),
                        new AccountOverview.ScopeItem("profile", "基本资料")), now)),
                List.of(new AccountOverview.SessionItem("sid-current", "PASSWORD", "127.0.0.1",
                        "Mozilla/5.0", now, now, true)),
                List.of());
    }
}
