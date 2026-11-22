package club.beenest.iam.authz;

import club.beenest.iam.authz.authorization.RedisOAuth2AuthorizationService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.common.util.RedisUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Redis 授权存储的用户维索引与按 id 吊销回归测试。
 * <p>管理端"查看/吊销某用户已签发令牌"依赖用户维索引;若 save 未写入索引,
 * 列举必然为空——那意味着事故处置时"吊销全部令牌"形同虚设。
 * 本测试锁定索引的写入、列举与清理全链路。</p>
 */
class RedisOAuth2AuthorizationServiceTest {

    /** 内存 Redis 替身:字符串、集合、扫描三类操作全部实现。 */
    static class FakeRedis extends RedisUtils {
        private final Map<String, String> store = new ConcurrentHashMap<>();
        private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

        FakeRedis() {
            super(null, new ObjectMapper());
        }

        @Override
        public void set(String key, String value) {
            store.put(key, value);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            store.put(key, value);
        }

        @Override
        public String get(String key) {
            return store.get(key);
        }

        @Override
        public boolean hasKey(String key) {
            return store.containsKey(key) || sets.containsKey(key);
        }

        @Override
        public boolean delete(String key) {
            sets.remove(key);
            return store.remove(key) != null;
        }

        @Override
        public Long delete(String... keys) {
            long count = 0;
            for (String key : keys) {
                if (delete(key)) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public void addToSet(String key, String... members) {
            sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).addAll(List.of(members));
        }

        @Override
        public void addToSetWithTtl(String key, Duration ttl, String... members) {
            addToSet(key, members);
        }

        @Override
        public void removeFromSet(String key, String... members) {
            Set<String> existing = sets.get(key);
            if (existing != null) {
                existing.removeAll(List.of(members));
            }
        }

        @Override
        public List<String> getSetAsList(String key) {
            Set<String> members = sets.get(key);
            return members == null ? List.of() : List.copyOf(members);
        }

        @Override
        public Set<String> executeScan(String pattern) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            Set<String> found = ConcurrentHashMap.newKeySet();
            store.keySet().forEach(k -> {
                if (k.matches(regex)) {
                    found.add(k);
                }
            });
            sets.keySet().forEach(k -> {
                if (k.matches(regex)) {
                    found.add(k);
                }
            });
            return found;
        }
    }

    /** 固定客户端仓储替身:授权反序列化时回填 RegisteredClient。 */
    static class StubClientRepository implements RegisteredClientRepository {
        @Override
        public RegisteredClient findById(String id) {
            return newClient();
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return newClient();
        }

        @Override
        public void save(RegisteredClient registeredClient) {
        }

        private RegisteredClient newClient() {
            return RegisteredClient.withId("client-1")
                    .clientId("test-client")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://localhost/callback")
                    .scope("openid")
                    .build();
        }
    }

    private RedisOAuth2AuthorizationService authorizationService;
    private FakeRedis fakeRedis;
    private IamClientSessionService clientSessionService;

    @BeforeEach
    void setUp() {
        fakeRedis = new FakeRedis();
        // 客户端会话服务只验证调用契约(是否记录、记录给哪个客户端),
        // 内部落库逻辑由 persistence 层负责,这里用替身隔离开
        clientSessionService = mock(IamClientSessionService.class);
        authorizationService = new RedisOAuth2AuthorizationService(
                new StubClientRepository(), fakeRedis, clientSessionService);
    }

    @Test
    @DisplayName("保存授权后可按用户列举,吊销后不再可见")
    void listByUserReflectsSaveAndRemove() {
        // 1. 同一用户两条授权 + 另一用户一条授权
        saveAuthorization("authz-1", principalOf(1L, "alice"));
        saveAuthorization("authz-2", principalOf(1L, "alice"));
        saveAuthorization("authz-3", principalOf(2L, "bob"));

        // 2. 按用户列举:各见各的
        List<OAuth2Authorization> alice = authorizationService.listByUser(1L);
        List<OAuth2Authorization> bob = authorizationService.listByUser(2L);
        assertEquals(2, alice.size(), "alice 应有 2 条授权");
        assertEquals(1, bob.size(), "bob 应有 1 条授权");

        // 3. 吊销 alice 的一条:列举与按 id 查询同时消失
        assertTrue(authorizationService.removeById("authz-1"));
        assertEquals(1, authorizationService.listByUser(1L).size());
        assertNotNull(authorizationService.findById("authz-2"), "未吊销的授权必须仍在");
    }

    @Test
    @DisplayName("吊销不存在的授权返回 false,不抛异常")
    void removeUnknownAuthorizationReturnsFalse() {
        assertFalse(authorizationService.removeById("not-exist"));
    }

    @Test
    @DisplayName("吊销用户全部授权后用户维索引清空")
    void revokeAllClearsUserIndex() {
        saveAuthorization("authz-1", principalOf(7L, "carol"));
        saveAuthorization("authz-2", principalOf(7L, "carol"));
        // 1. 逐条吊销(管理端 revokeByUser 的行为)
        for (OAuth2Authorization authorization : authorizationService.listByUser(7L)) {
            assertTrue(authorizationService.removeById(authorization.getId()));
        }
        // 2. 索引清空:再列举为空,不存在"删了正向键还留在索引里"的幽灵记录
        assertTrue(authorizationService.listByUser(7L).isEmpty());
    }

    @Test
    @DisplayName("吊销 JWT 访问令牌时写入 jti 黑名单,使无状态验签也能拒绝")
    void revokeWritesJtiToBlocklist() {
        // 1. 令牌值取 JWT 形态(未签名也可被 nimbus 解析出声明)
        String jti = "jti-smoke-0001";
        saveAuthorizationWithTokenValue("authz-jwt", principalOf(9L, "dave"), jwtOf(jti));
        // 2. 吊销
        assertTrue(authorizationService.removeById("authz-jwt"));
        // 3. 黑名单命中:验签环节据此拒绝已吊销令牌
        String blocklistKey = String.format(
                club.beenest.iam.common.constant.IamRedisKeyConstants.TOKEN_BLOCKLIST_JTI, jti);
        assertTrue(fakeRedis.hasKey(blocklistKey),
                "吊销后 jti 必须进入黑名单,否则业务接口在令牌自然过期前仍会放行");
    }

    @Test
    @DisplayName("授权请求的 state 可回查,同意提交不会因找不到授权而失败")
    void findByTokenResolvesState() {
        // 1. 保存一条待同意的授权请求(仅 state,无令牌)
        String state = "consent-state-abcdefg";
        OAuth2Authorization pending = OAuth2Authorization.withRegisteredClient(
                        new StubClientRepository().findById("client-1"))
                .id("authz-consent")
                .principalName("erin")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(new LinkedHashSet<>(Set.of("openid")))
                .attribute(OAuth2ParameterNames.STATE, state)
                .build();
        authorizationService.save(pending);
        // 2. 按 state 回查:同意流程依赖此路径定位原始授权请求
        OAuth2Authorization found = authorizationService.findByToken(state,
                new OAuth2TokenType(OAuth2ParameterNames.STATE));
        assertNotNull(found, "state 未建索引会导致同意提交被判 invalid_request");
        assertEquals("authz-consent", found.getId());
        // 3. 授权删除后索引一并清理,不残留指向空记录的键
        authorizationService.remove(found);
        assertNull(authorizationService.findByToken(state,
                new OAuth2TokenType(OAuth2ParameterNames.STATE)));
    }

    @Test
    @DisplayName("访问令牌 scope 随投影持久化:OIDC userinfo 依赖它判定 openid")
    void accessTokenScopesArePreserved() {
        // 1. 保存一条带 scope 的授权
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principalOf(11L, "frank"), null, List.of());
        Set<String> scopes = new LinkedHashSet<>(List.of("openid", "profile"));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(
                        new StubClientRepository().findById("client-1"))
                .id("authz-scope")
                .principalName("frank")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(scopes)
                .attribute(Principal.class.getName(), authentication)
                .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "token-scope",
                        Instant.now(), Instant.now().plusSeconds(300), scopes), metadata -> {
                })
                .build();
        authorizationService.save(authorization);
        // 2. 读回:scope 不得丢失,否则 userinfo 一律 insufficient_scope
        OAuth2Authorization loaded = authorizationService.findById("authz-scope");
        assertNotNull(loaded);
        assertEquals(Set.of("openid", "profile"),
                loaded.getAccessToken().getToken().getScopes(),
                "访问令牌 scope 必须持久化,否则 OIDC userinfo 无法判定 openid 授权");
    }

    @Test
    @DisplayName("授权码签发时记录客户端会话,供单点登出广播寻址")
    void codeIssuedRecordsClientSession() {
        // 1. 构造一条"授权端点刚签发 code"的授权(带主体与授权码)
        IamUserPrincipal principal = principalOf(5L, "grace");
        authorizationService.save(authorizationWithCode("authz-code", principal, "code-abc"));
        // 2. 客户端会话必须以 iam_client.id 记录,否则登出时找不到要通知哪个应用
        verify(clientSessionService).recordCurrentSession(eq(principal), eq(42L), eq("authz-code"));
    }

    @Test
    @DisplayName("令牌交换再次保存同一授权时不重复记录客户端会话")
    void tokenExchangeDoesNotRecordClientSessionAgain() {
        IamUserPrincipal principal = principalOf(6L, "heidi");
        OAuth2Authorization authorization = authorizationWithCode("authz-code-2", principal, "code-def");
        // 1. 首次保存 = 签发授权码
        authorizationService.save(authorization);
        // 2. 再次保存 = 令牌交换/刷新链路
        authorizationService.save(authorization);
        // 3. 只记一次:重复记录会让广播把同一应用通知多遍
        verify(clientSessionService, times(1)).recordCurrentSession(any(), any(), any());
    }

    /** 构造一条带授权码的授权(仅授权码流会触发客户端会话记录)。 */
    private OAuth2Authorization authorizationWithCode(String id, IamUserPrincipal principal, String code) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of());
        return OAuth2Authorization.withRegisteredClient(numericClientRepository().findById("42"))
                .id(id)
                .principalName(principal.getUsername())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(new LinkedHashSet<>(Set.of("openid")))
                .attribute(Principal.class.getName(), authentication)
                .token(new OAuth2AuthorizationCode(code, Instant.now(), Instant.now().plusSeconds(300)),
                        metadata -> {
                        })
                .build();
    }

    /** 主键为数字的客户端仓储:客户端会话要按 iam_client.id 记录。 */
    private static RegisteredClientRepository numericClientRepository() {
        return new RegisteredClientRepository() {
            @Override
            public RegisteredClient findById(String id) {
                return registered();
            }

            @Override
            public RegisteredClient findByClientId(String clientId) {
                return registered();
            }

            @Override
            public void save(RegisteredClient registeredClient) {
            }

            private RegisteredClient registered() {
                return RegisteredClient.withId("42")
                        .clientId("numeric-client")
                        .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("http://localhost/callback")
                        .scope("openid")
                        .build();
            }
        };
    }

    /** 构造一条带认证主体的授权(主体决定用户维索引归属)。 */
    private void saveAuthorization(String id, IamUserPrincipal principal) {
        saveAuthorizationWithTokenValue(id, principal, "token-" + id);
    }

    /** 构造一条授权,访问令牌值取指定的 JWT 形态字符串。 */
    private void saveAuthorizationWithTokenValue(String id, IamUserPrincipal principal,
                                                 String tokenValue) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of());
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(
                        new StubClientRepository().findById("client-1"))
                .id(id)
                .principalName(principal.getUsername())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(new LinkedHashSet<>(Set.of("openid")))
                .attribute(Principal.class.getName(), authentication)
                .token(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                tokenValue, Instant.now(), Instant.now().plusSeconds(300)),
                        metadata -> {
                        })
                .build();
        authorizationService.save(authorization);
    }

    /** 拼一个未签名的 JWT 形态字符串(nimbus 只解析不验签即可取到 jti;签名位给非空占位)。 */
    private String jwtOf(String jti) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"jti\":\"" + jti + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".AQAB";
    }

    private IamUserPrincipal principalOf(Long userId, String username) {
        return new IamUserPrincipal(userId, 10L, "default", username,
                username, 0, List.of("user:view"), Map.of());
    }
}
