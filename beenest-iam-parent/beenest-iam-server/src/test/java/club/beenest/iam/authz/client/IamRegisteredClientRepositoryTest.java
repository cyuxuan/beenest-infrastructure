package club.beenest.iam.authz.client;

import club.beenest.iam.authz.admission.ClientAdmissionPolicy;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户端仓储的策略下发测试。
 * <p>准入策略靠 RegisteredClient 带到协议链,绕开这一步的策略读不到,
 * 判定会退化成"不限制"——不限制是能跑通的状态,不会被测试发现。</p>
 */
class IamRegisteredClientRepositoryTest {

    private final ClientProvider clientProvider = mock(ClientProvider.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);
    private final IamRegisteredClientRepository repository =
            new IamRegisteredClientRepository(clientProvider, realmProvider);

    @BeforeEach
    void setUp() {
        // RealmModel 有 11 个分量:末尾的 SubjectMode 与 theme 传 null 表示"用默认"
        RealmModel realm = new RealmModel(1L, "default", "默认域", true, false,
                300L, 1800L, 1800L, 36000L, null, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
    }

    @Test
    @DisplayName("准入策略随 RegisteredClient 下发")
    void carriesAdmissionPolicy() {
        when(clientProvider.findByClientKey(1L, "app")).thenReturn(Optional.of(client(Set.of("SMS"))));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertEquals(Set.of(LoginMethod.SMS.code()), policy.allowedMethodCodes());
        assertTrue(policy.requiresMfa());
    }

    @Test
    @DisplayName("白名单存了值却一个都解析不出来时按拒绝一切处理,绝不放开")
    void unparseableWhitelistDeniesAll() {
        // 最现实的触发不是管理员手滑,而是将来从 LoginMethod 移除/改名某个值——
        // 彼时所有限定该方式的客户端都会走到这里。若落回"不限制",
        // 限制就被静默解除了,而且看起来一切正常
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(client(Set.of("TOTP"))));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
        assertFalse(policy.admits("SMS"));
    }

    @Test
    @DisplayName("白名单留空表示不限制,不是拒绝一切")
    void blankWhitelistMeansUnrestricted() {
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(client(Set.of())));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertFalse(policy.denyAll());
        assertTrue(policy.admits("PASSWORD"));
    }

    @Test
    @DisplayName("客户端对象为空时按拒绝一切处理,绝不落回不限制")
    void nullRegisteredClientDeniesAll() {
        // 理论不可达,但一旦发生,"不知道策略"同样只能按拒绝处理。
        // 这个用例钉住的方向比它现在不可达更重要:将来有人把这里改回
        // unrestricted(),编译通过、测试全绿,闸门就被静默拆掉了
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(null);

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
        assertFalse(policy.admits(null));
    }

    @Test
    @DisplayName("ClientSettings 为空时按拒绝一切处理,绝不落回不限制")
    void nullClientSettingsDeniesAll() {
        // SAS 的 Builder 总会给默认 ClientSettings,正常路径造不出 null;
        // 用替身直接打这个分支,同样是为了把"失败方向"钉死在测试里
        RegisteredClient registered = mock(RegisteredClient.class);
        when(registered.getClientSettings()).thenReturn(null);

        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
    }

    @Test
    @DisplayName("白名单混排时保留可识别的,只有全部认不出才算配置损坏")
    void partiallyUnparseableWhitelistKeepsRecognized() {
        // TOTP 是第二因素,不在 LoginMethod 词表里,解析时被忽略——
        // 但 SMS 认得出,说明配置大体有效,不能因此把整条限制也解除
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(client(Set.of("SMS", "TOTP"))));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertFalse(policy.denyAll());
        assertEquals(Set.of(LoginMethod.SMS.code()), policy.allowedMethodCodes());
        assertTrue(policy.admits("SMS"));
        assertFalse(policy.admits("TOTP"));
    }

    /**
     * 构造带准入策略的客户端模型。
     * <p>ClientModel 是 record,分量顺序为(Task 1 已落地,勿改):
     * id, realmId, clientKey, clientName, clientType, secret, redirectUris,
     * postLogoutRedirectUris, backchannelLogoutUri, reuseRefreshTokens, scopes,
     * requirePkce, requireConsent, enabled, subjectMode, accessTokenTtl,
     * refreshTokenTtl, grantTypes, allowedLoginTypes, requireMfa, serviceAccountUserId</p>
     */
    private static ClientModel client(Set<String> allowedLoginTypes) {
        return new ClientModel(
                1L, 1L, "app", "应用", ClientModel.ClientType.PUBLIC, null,
                Set.of("https://app.example.com/cb"), Set.of(), null, true,
                Set.of("openid"), true, false, true, null, null, null,
                Set.of("authorization_code"), allowedLoginTypes, true, null);
    }
}
