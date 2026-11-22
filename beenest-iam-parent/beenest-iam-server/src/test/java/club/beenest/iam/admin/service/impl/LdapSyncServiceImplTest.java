package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.LdapSyncRequest;
import club.beenest.iam.admin.dto.LdapSyncResponse;
import club.beenest.iam.authz.ldap.LdapGateway;
import club.beenest.iam.authz.ldap.LdapUnavailableException;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.persistence.converter.UserConverter;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import club.beenest.iam.persistence.manager.IamUserManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LDAP 目录全量同步测试:只补建与补全,本地账号优先,预演不落库。
 */
class LdapSyncServiceImplTest {

    private static final Long REALM_ID = 1L;

    private IamLdapConfigStore configStore;
    private LdapGateway gateway;
    private IamUserManager userManager;
    private LdapSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        configStore = mock(IamLdapConfigStore.class);
        gateway = mock(LdapGateway.class);
        userManager = mock(IamUserManager.class);
        IamSecretCrypto secretCrypto = mock(IamSecretCrypto.class);
        when(secretCrypto.decrypt(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(configStore.getConfig(REALM_ID, "default")).thenReturn(enabledConfig());
        service = new LdapSyncServiceImpl(configStore, secretCrypto, gateway, userManager,
                new UserConverter(new ObjectMapper()));
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("目录中有、本地没有:新建联合账号并记录目录来源")
    void createsMissingLocalUser() {
        when(gateway.searchAll(any(), anyString(), anyInt()))
                .thenReturn(List.of(entry("bob", "bob@corp.local", "Bob", "guid-1")));
        when(userManager.getByUsername(REALM_ID, "bob")).thenReturn(null);
        when(userManager.findByEmail(REALM_ID, "bob@corp.local")).thenReturn(Optional.empty());

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, false));

        assertTrue(report.success());
        assertEquals(1, report.created());
        assertEquals(1, report.scanned());
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(userManager).createFederatedUser(eq(REALM_ID), eq("bob"), eq("bob@corp.local"),
                eq("Bob"), any(), attributes.capture());
        assertEquals("cn=bob,ou=staff,dc=corp", attributes.getValue().get("ldap_dn"));
        assertEquals("guid-1", attributes.getValue().get("ldap_uuid"));
    }

    @Test
    @DisplayName("本地账号优先:同名账号有本地口令时整体跳过,不建号也不更新")
    void localAccountWithPasswordIsSkipped() {
        when(gateway.searchAll(any(), anyString(), anyInt()))
                .thenReturn(List.of(entry("admin", "admin@corp.local", "Admin", "guid-2")));
        when(userManager.getByUsername(REALM_ID, "admin"))
                .thenReturn(entity(9L, "admin", "运维管理员", null));
        when(userManager.hasLocalPassword(9L)).thenReturn(true);

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, false));

        assertEquals(1, report.skipped());
        assertEquals(0, report.created());
        assertEquals(0, report.updated());
        verify(userManager, never()).createFederatedUser(anyLong(), anyString(), any(), any(), any(), any());
        verify(userManager, never()).update(any());
    }

    @Test
    @DisplayName("已联合账号只补空缺:已有邮箱与昵称不被目录覆盖,仅补目录来源属性")
    void fillsOnlyMissingFields() {
        when(gateway.searchAll(any(), anyString(), anyInt()))
                .thenReturn(List.of(entry("bob", "dir@corp.local", "目录昵称", "guid-3")));
        User entity = entity(7L, "bob", "本地昵称", "local@corp.local");
        when(userManager.getByUsername(REALM_ID, "bob")).thenReturn(entity);
        when(userManager.hasLocalPassword(7L)).thenReturn(false);

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, false));

        assertEquals(1, report.updated());
        ArgumentCaptor<User> updated = ArgumentCaptor.forClass(User.class);
        verify(userManager).update(updated.capture());
        assertEquals("local@corp.local", updated.getValue().getEmail());
        assertEquals("本地昵称", updated.getValue().getNickname());
        assertTrue(updated.getValue().getAttributes().contains("cn=bob,ou=staff,dc=corp"));
        assertTrue(updated.getValue().getAttributes().contains("guid-3"));
    }

    @Test
    @DisplayName("预演模式:报告将新建的数量,但不写库")
    void dryRunDoesNotWrite() {
        when(gateway.searchAll(any(), anyString(), anyInt()))
                .thenReturn(List.of(entry("alice", "alice@corp.local", "Alice", "guid-4")));
        when(userManager.getByUsername(REALM_ID, "alice")).thenReturn(null);

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, true));

        assertTrue(report.success());
        assertTrue(report.dryRun());
        assertEquals(1, report.created());
        verify(userManager, never()).createFederatedUser(anyLong(), anyString(), any(), any(), any(), any());
        verify(userManager, never()).update(any());
    }

    @Test
    @DisplayName("目录邮箱已被其他账号占用:账号照建但不写邮箱,避免找回口令串号")
    void skipsConflictingEmailOnCreate() {
        when(gateway.searchAll(any(), anyString(), anyInt()))
                .thenReturn(List.of(entry("bob", "boss@corp.local", "Bob", "guid-5")));
        when(userManager.getByUsername(REALM_ID, "bob")).thenReturn(null);
        when(userManager.findByEmail(REALM_ID, "boss@corp.local"))
                .thenReturn(Optional.of(model(1L, "boss", "老板", "boss@corp.local")));

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, false));

        assertEquals(1, report.created());
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(userManager).createFederatedUser(eq(REALM_ID), eq("bob"), email.capture(), any(), any(), any());
        assertNull(email.getValue());
        assertFalse(report.samples().isEmpty());
    }

    @Test
    @DisplayName("读取达到上限:报告标记截断,提示调大上限")
    void marksTruncatedWhenLimitReached() {
        when(gateway.searchAll(any(), anyString(), eq(1)))
                .thenReturn(List.of(entry("bob", null, null, null)));
        when(userManager.getByUsername(REALM_ID, "bob")).thenReturn(null);

        LdapSyncResponse report = service.sync(new LdapSyncRequest(1, true));

        assertTrue(report.truncated());
        assertTrue(report.message().contains("上限"));
    }

    @Test
    @DisplayName("目录不可达:同步失败并给出可排查的提示")
    void unavailableDirectoryFails() {
        when(gateway.searchAll(any(), anyString(), anyInt()))
                .thenThrow(new LdapUnavailableException("connect timed out", null));

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, false));

        assertFalse(report.success());
        assertTrue(report.message().contains("目录不可达"));
    }

    @Test
    @DisplayName("联合未启用:不接触目录,直接提示先启用")
    void disabledConfigDoesNotTouchDirectory() {
        when(configStore.getConfig(REALM_ID, "default")).thenReturn(null);

        LdapSyncResponse report = service.sync(new LdapSyncRequest(500, false));

        assertFalse(report.success());
        assertTrue(report.message().contains("未启用"));
        verify(gateway, never()).searchAll(any(), anyString(), anyInt());
    }

    private static void login() {
        IamUserPrincipal principal = new IamUserPrincipal(100L, REALM_ID, "default", "admin",
                "管理员", 1, List.of("ldap-config:update"), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static LdapConfig enabledConfig() {
        LdapConfig config = new LdapConfig();
        config.setRealmId(REALM_ID);
        config.setEnabled(true);
        config.setServerUrl("ldap://127.0.0.1:389");
        config.setBindDn("cn=svc,dc=corp");
        config.setBindPassword("enc:v1:svc-secret");
        config.setUserBaseDn("ou=staff,dc=corp");
        config.setUserFilter("(&(objectClass=person)(sAMAccountName={0}))");
        config.setUuidAttribute("objectGUID");
        config.setUsernameAttribute("sAMAccountName");
        config.setEmailAttribute("mail");
        config.setDisplayNameAttribute("displayName");
        config.setConnectTimeoutMs(3000);
        config.setReadTimeoutMs(5000);
        return config;
    }

    private static LdapGateway.LdapEntry entry(String username, String email, String displayName,
                                               String uuid) {
        Map<String, List<String>> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        attributes.put("sAMAccountName", List.of(username));
        if (email != null) {
            attributes.put("mail", List.of(email));
        }
        if (displayName != null) {
            attributes.put("displayName", List.of(displayName));
        }
        if (uuid != null) {
            attributes.put("objectGUID", List.of(uuid));
        }
        return new LdapGateway.LdapEntry("cn=" + username + ",ou=staff,dc=corp", attributes);
    }

    private static UserModel model(Long id, String username, String nickname, String email) {
        return new UserModel(id, REALM_ID, username, nickname, email, null, 1,
                "EXTERNAL", false, 0, Map.of());
    }

    private static User entity(Long id, String username, String nickname, String email) {
        User user = new User();
        user.setId(id);
        user.setRealmId(REALM_ID);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setEmail(email);
        user.setAttributes("{}");
        return user;
    }
}
