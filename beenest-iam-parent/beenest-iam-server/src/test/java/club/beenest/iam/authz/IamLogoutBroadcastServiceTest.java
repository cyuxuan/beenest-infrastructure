package club.beenest.iam.authz;

import club.beenest.iam.authz.jwks.RealmJwkSource;
import club.beenest.iam.authz.logout.IamLogoutBroadcastService;
import club.beenest.iam.authz.logout.LogoutTokenIssuer;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.SessionIdHash;
import club.beenest.iam.authz.subject.IamSubjectResolver;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.ClientSessionModel;
import club.beenest.iam.core.model.LogoutBroadcastModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.ClientSessionStore;
import club.beenest.iam.core.spi.provider.LogoutBroadcastStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单点登出广播回归测试。
 * <p>锁定两个企业场景中最容易掉的两个点:</p>
 * <ol>
 *   <li>登出令牌必须真的发出去、且声明齐全(应用按 events/sid/aud 判定并定位会话);</li>
 *   <li>应用没登记接收地址时必须留痕(SKIPPED),而不是静默跳过——
 *       静默跳过等于"用户以为退了,应用还在线",事后无从发现。</li>
 * </ol>
 */
class IamLogoutBroadcastServiceTest {

    private static final Long REALM_ID = 1L;
    private static final String REALM_KEY = "default";
    private static final String SID = "sid-logout-0001";

    private HttpServer server;
    private final List<String> receivedTokens = new CopyOnWriteArrayList<>();
    private int statusCode = 200;

    @BeforeEach
    void startReceiver() throws Exception {
        // 用 JDK 内置 HttpServer 当"接入应用"的接收端点,真实走一遍 HTTP 投递
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/backchannel", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String token = extractToken(body);
            if (token != null) {
                receivedTokens.add(token);
            }
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("已登记接收地址的应用收到带 events/sid/aud 的登出令牌,记录为已送达")
    void deliversLogoutTokenToRegisteredClient() throws Exception {
        FakeStore store = new FakeStore();
        IamLogoutBroadcastService service = newService(store,
                session(1L, 7L, 100L),
                client(100L, "oa", "http://127.0.0.1:" + port() + "/backchannel", null));

        int targets = service.broadcast(REALM_ID, REALM_KEY, SID, principal(7L, "alice"));

        assertEquals(1, targets, "应有一个目标应用");
        assertEquals(1, receivedTokens.size(), "应用必须收到一次登出通知");
        // 1. 令牌声明:events 是登出语义标识,sid 用于定位应用侧会话,aud 绑定目标应用
        SignedJWT jwt = SignedJWT.parse(receivedTokens.get(0));
        assertNotNull(jwt.getJWTClaimsSet().getClaim("events"), "缺少 events 声明时应用无法识别为登出");
        assertTrue(jwt.getJWTClaimsSet().getClaim("events").toString()
                .contains(LogoutTokenIssuer.EVENT_BACKCHANNEL_LOGOUT));
        assertEquals(SessionIdHash.of(SID), jwt.getJWTClaimsSet().getStringClaim("sid"),
                "sid 必须是会话 id 的摘要,且与 ID Token 中的 sid 同值");
        assertEquals("oa", jwt.getJWTClaimsSet().getAudience().get(0));
        assertEquals("alice", jwt.getJWTClaimsSet().getSubject());
        assertEquals("http://iam.test/realms/default", jwt.getJWTClaimsSet().getIssuer());
        // 2. 投递结果回写:排障时要能看到"发了、成功了"
        assertEquals(LogoutBroadcastModel.STATE_SENT, store.lastState());
        // 3. 会话的客户端映射随之关闭,避免下次登出重复通知
        assertEquals(1, store.closed);
    }

    @Test
    @DisplayName("未登记接收地址的应用记为 SKIPPED,不静默跳过")
    void recordsSkippedWhenClientHasNoBackchannelUri() {
        FakeStore store = new FakeStore();
        IamLogoutBroadcastService service = newService(store,
                session(1L, 7L, 200L),
                client(200L, "legacy-app", null, null));

        service.broadcast(REALM_ID, REALM_KEY, SID, principal(7L, "bob"));

        assertTrue(receivedTokens.isEmpty(), "未登记地址不应发起请求");
        assertEquals(LogoutBroadcastModel.STATE_SKIPPED, store.lastState(),
                "必须留痕:未登记地址的应用在登出后仍在线,这一点必须可查");
    }

    @Test
    @DisplayName("应用返回 5xx 时记为失败,但登出流程本身不抛异常")
    void marksFailedOnServerError() {
        statusCode = 500;
        FakeStore store = new FakeStore();
        IamLogoutBroadcastService service = newService(store,
                session(1L, 7L, 300L),
                client(300L, "broken-app", "http://127.0.0.1:" + port() + "/backchannel", null));

        service.broadcast(REALM_ID, REALM_KEY, SID, principal(7L, "carol"));

        assertEquals(LogoutBroadcastModel.STATE_FAILED, store.lastState());
        assertEquals(500, store.lastHttpStatus());
    }

    @Test
    @DisplayName("客户端单独覆盖为 USER_ID 时,登出令牌的 sub 与它拿到的 ID Token 一致(用户主键)")
    void logoutTokenSubjectFollowsClientOverride() throws Exception {
        FakeStore store = new FakeStore();
        IamLogoutBroadcastService service = newService(store,
                session(1L, 7L, 400L),
                client(400L, "new-app", "http://127.0.0.1:" + port() + "/backchannel", SubjectMode.USER_ID));

        service.broadcast(REALM_ID, REALM_KEY, SID, principal(7L, "alice"));

        assertEquals(1, receivedTokens.size());
        SignedJWT jwt = SignedJWT.parse(receivedTokens.get(0));
        // 应用按 sub 定位本地会话:这里必须与 ID Token 的 sub 同值,否则登出找不到人
        assertEquals("7", jwt.getJWTClaimsSet().getSubject(),
                "客户端覆盖为 USER_ID 后,sub 必须是不可变用户主键");
    }

    @Test
    @DisplayName("定向登出只通知目标客户端,其它应用保持在线")
    void targetedBroadcastOnlyTouchesTargetClient() throws Exception {
        FakeStore store = new FakeStore();
        ClientSessionModel oa = new ClientSessionModel(1L, REALM_ID, 10L, SID, 7L, 100L, "oa");
        ClientSessionModel crm = new ClientSessionModel(2L, REALM_ID, 10L, SID, 7L, 200L, "crm");
        Map<Long, ClientModel> clients = Map.of(
                100L, client(100L, "oa", "http://127.0.0.1:" + port() + "/backchannel", null),
                200L, client(200L, "crm", "http://127.0.0.1:" + port() + "/backchannel", null));
        IamLogoutBroadcastService service = newService(store, List.of(oa, crm), clients);

        int targets = service.broadcastToClient(REALM_ID, REALM_KEY, SID, principal(7L, "alice"), 100L);

        // 1. 只向目标客户端投递一次
        assertEquals(1, targets);
        assertEquals(1, receivedTokens.size());
        SignedJWT jwt = SignedJWT.parse(receivedTokens.get(0));
        assertEquals("oa", jwt.getJWTClaimsSet().getAudience().get(0),
                "授权收回只影响单个应用,不能把用户从其它应用一并踢下线");
        // 2. 只关闭目标客户端的会话记录
        assertEquals(1, store.closed);
    }

    // ---------------------------------------------------------------
    // 组装
    // ---------------------------------------------------------------

    private IamLogoutBroadcastService newService(FakeStore store, ClientSessionModel session,
                                                 ClientModel client) {
        ClientSessionStore sessionStore = new ClientSessionStore() {
            @Override
            public ClientSessionModel record(Long realmId, Long userSessionId, Long userId, Long clientId) {
                return session;
            }

            @Override
            public List<ClientSessionModel> listBySid(Long realmId, String sid) {
                return List.of(session);
            }

            @Override
            public List<ClientSessionModel> listByUserAndClient(Long realmId, Long userId, Long clientId) {
                return session.clientId().equals(clientId) ? List.of(session) : List.of();
            }

            @Override
            public int closeByUserAndClient(Long realmId, Long userId, Long clientId) {
                store.closed++;
                return 1;
            }

            @Override
            public int closeBySidAndClient(String sid, Long clientId) {
                store.closed++;
                return 1;
            }

            @Override
            public int closeBySid(String sid) {
                store.closed++;
                return 1;
            }
        };
        ClientProvider clientProvider = new ClientProvider() {
            @Override
            public Optional<ClientModel> findByClientKey(Long realmId, String clientKey) {
                return Optional.of(client);
            }

            @Override
            public Optional<ClientModel> findById(Long id) {
                return Optional.of(client);
            }
        };
        RealmProvider realmProvider = new RealmProvider() {
            @Override
            public Optional<RealmModel> findByRealmKey(String realmKey) {
                return Optional.of(realm(REALM_ID, realmKey, SubjectMode.LOGIN_NAME));
            }

            @Override
            public Optional<RealmModel> findById(Long id) {
                return Optional.of(realm(id, REALM_KEY, SubjectMode.LOGIN_NAME));
            }

            @Override
            public List<RealmModel> listEnabled() {
                return List.of(realm(REALM_ID, REALM_KEY, SubjectMode.LOGIN_NAME));
            }
        };
        return new IamLogoutBroadcastService(sessionStore, clientProvider, store,
                new LogoutTokenIssuer(new FixedKeyJwkSource()),
                new IamSubjectResolver(realmProvider, clientProvider), "http://iam.test", 3000L);
    }

    /**
     * 多会话、多客户端的组装版本,用于定向投递测试。
     *
     * @param store    投递记录替身
     * @param sessions 会话上的客户端记录
     * @param clients  客户端主键到客户端模型的映射
     * @return 广播服务
     */
    private IamLogoutBroadcastService newService(FakeStore store, List<ClientSessionModel> sessions,
                                                 Map<Long, ClientModel> clients) {
        ClientSessionStore sessionStore = new ClientSessionStore() {
            @Override
            public ClientSessionModel record(Long realmId, Long userSessionId, Long userId, Long clientId) {
                return sessions.isEmpty() ? null : sessions.get(0);
            }

            @Override
            public List<ClientSessionModel> listBySid(Long realmId, String sid) {
                return sessions;
            }

            @Override
            public List<ClientSessionModel> listByUserAndClient(Long realmId, Long userId, Long clientId) {
                return sessions.stream().filter(session -> session.clientId().equals(clientId)).toList();
            }

            @Override
            public int closeByUserAndClient(Long realmId, Long userId, Long clientId) {
                store.closed++;
                return 1;
            }

            @Override
            public int closeBySidAndClient(String sid, Long clientId) {
                store.closed++;
                return 1;
            }

            @Override
            public int closeBySid(String sid) {
                store.closed++;
                return 1;
            }
        };
        ClientProvider clientProvider = new ClientProvider() {
            @Override
            public Optional<ClientModel> findByClientKey(Long realmId, String clientKey) {
                return clients.values().stream()
                        .filter(client -> client.clientKey().equals(clientKey))
                        .findFirst();
            }

            @Override
            public Optional<ClientModel> findById(Long id) {
                return Optional.ofNullable(clients.get(id));
            }
        };
        RealmProvider realmProvider = new RealmProvider() {
            @Override
            public Optional<RealmModel> findByRealmKey(String realmKey) {
                return Optional.of(realm(REALM_ID, realmKey, SubjectMode.LOGIN_NAME));
            }

            @Override
            public Optional<RealmModel> findById(Long id) {
                return Optional.of(realm(id, REALM_KEY, SubjectMode.LOGIN_NAME));
            }

            @Override
            public List<RealmModel> listEnabled() {
                return List.of(realm(REALM_ID, REALM_KEY, SubjectMode.LOGIN_NAME));
            }
        };
        return new IamLogoutBroadcastService(sessionStore, clientProvider, store,
                new LogoutTokenIssuer(new FixedKeyJwkSource()),
                new IamSubjectResolver(realmProvider, clientProvider), "http://iam.test", 3000L);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private static IamUserPrincipal principal(Long userId, String username) {
        return new IamUserPrincipal(userId, REALM_ID, REALM_KEY, username, null, 1, List.of(), Map.of());
    }

    private static RealmModel realm(Long id, String realmKey, SubjectMode mode) {
        return new RealmModel(id, realmKey, realmKey, true, true, 300L, 1800L, 1800L, 36000L, mode, null);
    }

    private static ClientSessionModel session(Long realmId, Long userId, Long clientId) {
        return new ClientSessionModel(1L, realmId, 10L, SID, userId, clientId, "app");
    }

    /** @param subjectMode 客户端覆盖策略;null 表示继承所属域 */
    private static ClientModel client(Long id, String clientKey, String backchannelUri, SubjectMode subjectMode) {
        return new ClientModel(id, REALM_ID, clientKey, clientKey, ClientModel.ClientType.CONFIDENTIAL,
                "secret", Set.of("http://localhost/callback"), Set.of(), backchannelUri, true,
                Set.of("openid"), true, false, true, subjectMode, null, null,
                Set.of("authorization_code", "refresh_token"), Set.of(), false, null);
    }

    private static String extractToken(String formBody) {
        for (String pair : formBody.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0 && "logout_token".equals(pair.substring(0, index))) {
                return URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 固定密钥来源:测试不需要走真实的域密钥库。 */
    private static class FixedKeyJwkSource extends RealmJwkSource {
        private final com.nimbusds.jose.jwk.RSAKey key = generateKey();

        FixedKeyJwkSource() {
            super(null, null);
        }

        @Override
        public List<com.nimbusds.jose.jwk.JWK> jwksFor(String realmKey) {
            return List.of(key);
        }

        private static com.nimbusds.jose.jwk.RSAKey generateKey() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var pair = generator.generateKeyPair();
                return new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .keyID("test-kid-" + UUID.randomUUID())
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 投递记录替身:分配自增 id 并保留最后一次回写结果。 */
    private static class FakeStore implements LogoutBroadcastStore {
        private final AtomicLong sequence = new AtomicLong();
        private final Map<Long, LogoutBroadcastModel> records = new ConcurrentHashMap<>();
        private final List<Long> order = new CopyOnWriteArrayList<>();
        private int closed;

        @Override
        public LogoutBroadcastModel insert(LogoutBroadcastModel model) {
            long id = sequence.incrementAndGet();
            LogoutBroadcastModel stored = new LogoutBroadcastModel(id, model.realmId(), model.sid(),
                    model.userId(), model.clientId(), model.clientKey(), model.targetUri(),
                    model.state(), null, null, null);
            records.put(id, stored);
            order.add(id);
            return stored;
        }

        @Override
        public void updateResult(Long id, String state, Integer httpStatus, String errorMessage) {
            LogoutBroadcastModel current = records.get(id);
            if (current == null) {
                return;
            }
            records.put(id, new LogoutBroadcastModel(current.id(), current.realmId(), current.sid(),
                    current.userId(), current.clientId(), current.clientKey(), current.targetUri(),
                    state, httpStatus, errorMessage, current.createdTime()));
        }

        @Override
        public List<LogoutBroadcastModel> listBySid(Long realmId, String sid) {
            return new ArrayList<>(records.values());
        }

        @Override
        public List<LogoutBroadcastModel> listRecent(Long realmId, int limit) {
            return new ArrayList<>(records.values());
        }

        String lastState() {
            return records.get(order.get(order.size() - 1)).state();
        }

        Integer lastHttpStatus() {
            return records.get(order.get(order.size() - 1)).httpStatus();
        }
    }
}
