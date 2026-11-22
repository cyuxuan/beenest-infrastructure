package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 授权存储测试。
 * <p>覆盖授权码的一次性语义:同一枚码并发换票时只有一个请求能取到授权,
 * 其余请求按码不存在处理。</p>
 */
class RedisOAuth2AuthorizationServiceTest {

    private static final String CLIENT_DB_ID = "12";
    private static final String AUTHORIZATION_ID = "auth-0001";
    private static final String CODE = "code-0001";
    private static final OAuth2TokenType CODE_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

    /** 内存版键值存储,模拟 Redis 的原子写入语义 */
    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final RegisteredClientRepository registeredClientRepository = mock(RegisteredClientRepository.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final IamClientSessionService clientSessionService = mock(IamClientSessionService.class);

    private RedisOAuth2AuthorizationService service;
    private RegisteredClient registeredClient;

    @BeforeEach
    void setUp() {
        registeredClient = RegisteredClient.withId(CLIENT_DB_ID)
                .clientId("demo-app")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.example.com/callback")
                .scope("openid")
                .build();
        when(registeredClientRepository.findById(CLIENT_DB_ID)).thenReturn(registeredClient);

        when(redisUtils.get(anyString())).thenAnswer(call -> store.get(call.<String>getArgument(0)));
        doAnswer(call -> {
            store.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(redisUtils).set(anyString(), anyString(), any(Duration.class));
        when(redisUtils.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> store.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(redisUtils.delete(anyString()))
                .thenAnswer(call -> store.remove(call.<String>getArgument(0)) != null);

        service = new RedisOAuth2AuthorizationService(registeredClientRepository, redisUtils,
                clientSessionService);
    }

    @Test
    @DisplayName("授权码只能换一次票")
    void consumesAuthorizationCodeOnce() {
        service.save(authorizationWithCode());

        assertThat(service.findByToken(CODE, CODE_TYPE)).isNotNull();
        // 第二次取码:已被首次换票抢占,按码不存在处理
        assertThat(service.findByToken(CODE, CODE_TYPE)).isNull();
    }

    @Test
    @DisplayName("按 state 回查不受授权码抢占影响")
    void stateLookupDoesNotConsumeCode() {
        service.save(authorizationWithCode());

        assertThat(service.findByToken(CODE, null)).isNotNull();
        assertThat(service.findByToken(CODE, null)).isNotNull();
    }

    /**
     * 构造一条刚签发授权码的授权。
     *
     * @return 带授权码的授权对象
     */
    private OAuth2Authorization authorizationWithCode() {
        Instant now = Instant.now();
        return OAuth2Authorization.withRegisteredClient(registeredClient)
                .id(AUTHORIZATION_ID)
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("openid"))
                .token(new OAuth2AuthorizationCode(CODE, now, now.plusSeconds(300)))
                .build();
    }
}
