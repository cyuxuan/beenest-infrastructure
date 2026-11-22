package club.beenest.iam.authz;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.subject.IamSubjectResolver;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OIDC 主体标识(sub)解析回归测试。
 * <p>锁定三条契约:</p>
 * <ol>
 *   <li>默认(登录名)与存量行为一致——升级不能让已接入的应用集体掉线;</li>
 *   <li>域切到 USER_ID 后 sub 是不可变主键,登录名改名不影响应用;</li>
 *   <li>客户端可单独覆盖,且覆盖优先于域策略——切换才能逐应用推进。</li>
 * </ol>
 */
class IamSubjectResolverTest {

    private static final Long REALM_ID = 1L;
    private static final String REALM_KEY = "default";

    @Test
    @DisplayName("域策略为登录名时,sub 取登录名(与升级前行为一致)")
    void resolvesLoginNameByDefault() {
        IamSubjectResolver resolver = resolver(SubjectMode.LOGIN_NAME, null);

        assertEquals("alice", resolver.resolve(principal(7L, "alice"), "oa"));
    }

    @Test
    @DisplayName("域策略为 USER_ID 时,sub 取不可变用户主键")
    void resolvesUserIdWhenRealmSwitched() {
        IamSubjectResolver resolver = resolver(SubjectMode.USER_ID, null);

        assertEquals("7", resolver.resolve(principal(7L, "alice"), "oa"),
                "sub 必须稳定:登录名改名不能改变应用看到的主体");
    }

    @Test
    @DisplayName("客户端覆盖优先于域策略:域为 USER_ID 而客户端指定登录名时,该应用仍拿登录名")
    void clientOverrideWinsOverRealm() {
        IamSubjectResolver resolver = resolver(SubjectMode.USER_ID, SubjectMode.LOGIN_NAME);

        assertEquals("alice", resolver.resolve(principal(7L, "alice"), "legacy-app"),
                "存量应用按登录名匹配,域切换不能把它一起改掉");
    }

    @Test
    @DisplayName("客户端覆盖为 USER_ID 时,即使域仍是登录名,该应用也拿用户主键")
    void clientCanOptInIndependently() {
        IamSubjectResolver resolver = resolver(SubjectMode.LOGIN_NAME, SubjectMode.USER_ID);

        assertEquals("7", resolver.resolve(principal(7L, "alice"), "new-app"));
    }

    @Test
    @DisplayName("已持有客户端模型时按该模型解析(登出广播逐个应用投递走这条路径)")
    void resolvesFromClientModel() {
        IamSubjectResolver resolver = resolver(SubjectMode.LOGIN_NAME, null);
        ClientModel client = client("app", SubjectMode.USER_ID);

        assertEquals("7", resolver.resolve(principal(7L, "alice"), client));
        assertEquals("alice", resolver.resolve(principal(7L, "alice"), (ClientModel) null),
                "取不到客户端时回落到域策略");
    }

    // ---------------------------------------------------------------
    // 组装
    // ---------------------------------------------------------------

    /** @param realmMode 域策略;@param clientMode 客户端覆盖(null 表示继承) */
    private static IamSubjectResolver resolver(SubjectMode realmMode, SubjectMode clientMode) {
        RealmProvider realmProvider = new RealmProvider() {
            @Override
            public Optional<RealmModel> findByRealmKey(String realmKey) {
                return Optional.of(realm(realmMode));
            }

            @Override
            public Optional<RealmModel> findById(Long id) {
                return Optional.of(realm(realmMode));
            }

            @Override
            public List<RealmModel> listEnabled() {
                return List.of(realm(realmMode));
            }
        };
        ClientProvider clientProvider = new ClientProvider() {
            @Override
            public Optional<ClientModel> findByClientKey(Long realmId, String clientKey) {
                return Optional.of(client(clientKey, clientMode));
            }

            @Override
            public Optional<ClientModel> findById(Long id) {
                return Optional.of(client("app", clientMode));
            }
        };
        return new IamSubjectResolver(realmProvider, clientProvider);
    }

    private static IamUserPrincipal principal(Long userId, String username) {
        return new IamUserPrincipal(userId, REALM_ID, REALM_KEY, username, null, 1, List.of(), Map.of());
    }

    private static RealmModel realm(SubjectMode mode) {
        return new RealmModel(REALM_ID, REALM_KEY, "默认域", true, true, 300L, 1800L, 1800L, 36000L, mode, null);
    }

    private static ClientModel client(String clientKey, SubjectMode mode) {
        return new ClientModel(1L, REALM_ID, clientKey, clientKey, ClientModel.ClientType.CONFIDENTIAL,
                "secret", Set.of("http://localhost/callback"), Set.of(), null, true,
                Set.of("openid"), true, false, true, mode, null, null,
                Set.of("authorization_code", "refresh_token"), Set.of(), false, null);
    }
}
