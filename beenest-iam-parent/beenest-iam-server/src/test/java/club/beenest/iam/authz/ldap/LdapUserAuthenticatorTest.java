package club.beenest.iam.authz.ldap;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LDAP 用户联合编排测试:本地查不到 → 目录 bind → JIT 建号,失败一律不可区分。
 * <p>网关用内存替身,把"目录里有什么、口令对不对、可达不可达"做成显式输入。</p>
 */
class LdapUserAuthenticatorTest {

    private static final RealmModel REALM = new RealmModel(1L, "default", "默认域", true, true,
            300L, 3600L, 1800L, 28800L, SubjectMode.LOGIN_NAME, null);

    private IamLdapConfigStore configStore;
    private IamSecretCrypto secretCrypto;
    private UserProvider userProvider;
    private LoginEventPublisher publisher;
    private FakeLdapGateway gateway;
    private LdapUserAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        configStore = mock(IamLdapConfigStore.class);
        secretCrypto = mock(IamSecretCrypto.class);
        userProvider = mock(UserProvider.class);
        publisher = mock(LoginEventPublisher.class);
        gateway = new FakeLdapGateway();
        authenticator = new LdapUserAuthenticator(configStore, secretCrypto, userProvider,
                gateway, publisher);

        // 密文透传:测试不关信封加密,只验证"解密后的口令进了网关"
        when(secretCrypto.decrypt(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    private LdapConfig enabledConfig() {
        LdapConfig config = new LdapConfig();
        config.setRealmId(1L);
        config.setEnabled(true);
        config.setServerUrl("ldap://127.0.0.1:389");
        config.setBindDn("cn=svc,dc=corp");
        config.setBindPassword("enc:v1:svc-secret");
        config.setUserBaseDn("ou=staff,dc=corp");
        config.setUserFilter("(sAMAccountName={0})");
        config.setUuidAttribute("objectGUID");
        config.setUsernameAttribute("sAMAccountName");
        config.setEmailAttribute("mail");
        config.setDisplayNameAttribute("displayName");
        config.setConnectTimeoutMs(3000);
        config.setReadTimeoutMs(5000);
        return config;
    }

    @Test
    @DisplayName("域未启用联合:不接触目录,返回 empty")
    void disabledRealmNeverTouchesDirectory() {
        when(configStore.getConfig(1L, "default")).thenReturn(null);

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "pw");

        assertTrue(result.isEmpty());
        assertEquals(0, gateway.searchCalls);
        assertEquals(0, gateway.bindCalls);
    }

    @Test
    @DisplayName("目录中不存在账号:返回 empty,审计记录真实原因")
    void unknownInDirectoryIsSilentToUser() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());

        Optional<UserModel> result = authenticator.authenticate(REALM, "ghost", "pw");

        assertTrue(result.isEmpty());
        verify(publisher).publish(eq("default"), eq(null), eq("ghost"),
                eq(LoginEventEvent.LOGIN_FAILURE), eq("PASSWORD"), eq(null), eq(null),
                eq("LDAP 目录中不存在该账号"));
    }

    @Test
    @DisplayName("目录口令错误:返回 empty,审计与'不存在'分开记录")
    void wrongDirectoryPasswordIsSilentToUser() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());
        gateway.addUser("bob", "cn=bob,ou=staff,dc=corp", "correct-horse");

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "wrong");

        assertTrue(result.isEmpty());
        verify(publisher).publish(eq("default"), eq(null), eq("bob"),
                eq(LoginEventEvent.LOGIN_FAILURE), eq("PASSWORD"), eq(null), eq(null),
                eq("LDAP 口令错误"));
        // 口令错误不建号
        verify(userProvider, never()).createFederatedUser(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("认证通过:JIT 创建本地用户,扩展属性记录目录来源")
    void successfulBindProvisionsLocalUser() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());
        gateway.addUser("bob", "cn=bob,ou=staff,dc=corp", "correct-horse");
        gateway.attributes("cn=bob,ou=staff,dc=corp").put("objectGUID", List.of("9f2c...guid"));
        gateway.attributes("cn=bob,ou=staff,dc=corp").put("mail", List.of("bob@corp.local"));
        gateway.attributes("cn=bob,ou=staff,dc=corp").put("displayName", List.of("Bob"));
        when(userProvider.findByUsername(1L, "bob")).thenReturn(Optional.empty());
        when(userProvider.createFederatedUser(eq(1L), eq("bob"), eq("bob@corp.local"), eq("Bob"), any(), any()))
                .thenReturn(new UserModel(7L, 1L, "bob", "Bob", "bob@corp.local", null, 1,
                        "EXTERNAL", false, 0, Map.of()));

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "correct-horse");

        assertTrue(result.isPresent());
        assertEquals(7L, result.get().id());
        // 目录来源进扩展属性:可追溯,也为将来"按 DN 反查"留锚点
        ArgumentCaptor<Map<String, String>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(userProvider).createFederatedUser(eq(1L), eq("bob"), eq("bob@corp.local"), eq("Bob"),
                any(), attrs.capture());
        assertEquals("cn=bob,ou=staff,dc=corp", attrs.getValue().get("ldap_dn"));
        assertEquals("9f2c...guid", attrs.getValue().get("ldap_uuid"));
        // 口令校验用的是"用户 DN + 输入口令",不是搜索账号口令
        assertEquals("correct-horse", gateway.lastBindPassword);
        assertEquals("cn=bob,ou=staff,dc=corp", gateway.lastBindDn);
    }

    @Test
    @DisplayName("已 JIT 过的用户再次登录:复用本地账号,不重复建号")
    void provisionedUserIsReused() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());
        gateway.addUser("bob", "cn=bob,ou=staff,dc=corp", "correct-horse");
        UserModel existing = new UserModel(7L, 1L, "bob", "Bob", null, null, 1,
                "EXTERNAL", false, 0, Map.of());
        when(userProvider.findByUsername(1L, "bob")).thenReturn(Optional.of(existing));

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "correct-horse");

        assertTrue(result.isPresent());
        assertEquals(7L, result.get().id());
        verify(userProvider, never()).createFederatedUser(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("目录不可达:返回 empty,审计标记为平台故障而非口令错误")
    void unavailableDirectoryIsSilentToUser() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());
        gateway.available = false;

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "pw");

        assertTrue(result.isEmpty());
        verify(publisher).publish(eq("default"), eq(null), eq("bob"),
                eq(LoginEventEvent.LOGIN_FAILURE), eq("PASSWORD"), eq(null), eq(null),
                eq("LDAP 目录不可达"));
        verify(userProvider, never()).createFederatedUser(anyLong(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("并发 JIT:唯一索引冲突时复用先到者,不把冲突抛给用户")
    void racingProvisionReusesWinner() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());
        gateway.addUser("bob", "cn=bob,ou=staff,dc=corp", "correct-horse");
        when(userProvider.findByUsername(1L, "bob"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new UserModel(9L, 1L, "bob", "Bob", null, null, 1,
                        "EXTERNAL", false, 0, Map.of())));
        // 该用例不配 mail 属性:email 为 null,匹配器必须允许 null(anyString 不匹配 null)
        when(userProvider.createFederatedUser(anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("uk username"));

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "correct-horse");

        assertTrue(result.isPresent());
        assertEquals(9L, result.get().id());
    }

    @Test
    @DisplayName("配置不完整(缺目录地址/搜索基):按未启用处理并留痕")
    void incompleteConfigFailsSafe() {
        LdapConfig config = enabledConfig();
        config.setServerUrl("");
        config.setUserBaseDn("");
        when(configStore.getConfig(1L, "default")).thenReturn(config);

        Optional<UserModel> result = authenticator.authenticate(REALM, "bob", "pw");

        assertTrue(result.isEmpty());
        assertEquals(0, gateway.searchCalls);
        verify(publisher).publish(eq("default"), eq(null), eq("bob"),
                eq(LoginEventEvent.LOGIN_FAILURE), eq("PASSWORD"), eq(null), eq(null),
                eq("LDAP 配置不完整(缺少目录地址或用户搜索基)"));
    }

    @Test
    @DisplayName("登录名特殊字符被转义后才进过滤器")
    void filterValueIsEscaped() {
        when(configStore.getConfig(1L, "default")).thenReturn(enabledConfig());

        authenticator.authenticate(REALM, "bob)(uid=admin", "pw");

        assertEquals("(sAMAccountName=bob\\29\\28uid=admin)", gateway.lastSearchFilter);
        assertFalse(gateway.lastSearchFilter.contains(")(uid="));
    }

    /** 内存目录替身:用户名 → DN,口令与属性按 DN 存;available 模拟网络故障。 */
    static class FakeLdapGateway implements LdapGateway {

        private final Map<String, String> dnByUsername = new HashMap<>();
        private final Map<String, String> passwordByDn = new HashMap<>();
        private final Map<String, Map<String, List<String>>> attrsByDn = new HashMap<>();
        private boolean available = true;

        int searchCalls;
        int bindCalls;
        String lastSearchFilter;
        String lastBindDn;
        String lastBindPassword;

        void addUser(String username, String dn, String password) {
            dnByUsername.put(username, dn);
            passwordByDn.put(dn, password);
        }

        Map<String, List<String>> attributes(String dn) {
            return attrsByDn.computeIfAbsent(dn, key -> new HashMap<>());
        }

        @Override
        public Optional<LdapEntry> search(LdapSettings config, String filter) {
            searchCalls++;
            lastSearchFilter = filter;
            if (!available) {
                throw new LdapUnavailableException("目录不可达(测试替身)", null);
            }
            String dn = dnByUsername.entrySet().stream()
                    .filter(entry -> filter.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            return dn == null ? Optional.empty()
                    : Optional.of(new LdapEntry(dn, attrsByDn.getOrDefault(dn, Map.of())));
        }

        @Override
        public boolean bind(LdapSettings config, String userDn, String password) {
            bindCalls++;
            lastBindDn = userDn;
            lastBindPassword = password;
            if (!available) {
                throw new LdapUnavailableException("目录不可达(测试替身)", null);
            }
            return password != null && password.equals(passwordByDn.get(userDn));
        }

        @Override
        public List<LdapEntry> searchAll(LdapSettings config, String filter, int maxEntries) {
            if (!available) {
                throw new LdapUnavailableException("目录不可达(测试替身)", null);
            }
            return dnByUsername.values().stream()
                    .limit(maxEntries)
                    .map(dn -> new LdapEntry(dn, attrsByDn.getOrDefault(dn, Map.of())))
                    .toList();
        }
    }
}
