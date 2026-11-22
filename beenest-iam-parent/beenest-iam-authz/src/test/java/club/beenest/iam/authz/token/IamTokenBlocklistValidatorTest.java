package club.beenest.iam.authz.token;

import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 令牌黑名单校验测试。
 * <p>两个维度都要拒绝:单令牌 jti 吊销、整会话 sid 吊销(用户登出)。</p>
 */
class IamTokenBlocklistValidatorTest {

    private RedisUtils redisUtils;
    private SessionRevocationService revocationService;
    private IamTokenBlocklistValidator validator;

    @BeforeEach
    void setUp() {
        redisUtils = mock(RedisUtils.class);
        revocationService = mock(SessionRevocationService.class);
        validator = new IamTokenBlocklistValidator(redisUtils, revocationService);
    }

    @Test
    @DisplayName("jti 命中黑名单:拒绝")
    void rejectsBlocklistedJti() {
        when(redisUtils.hasKey(anyString())).thenReturn(true);

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-1", null));

        assertTrue(result.hasErrors(), "jti 已吊销的令牌必须失效");
    }

    @Test
    @DisplayName("会话已登出:即使 jti 未吊销也拒绝")
    void rejectsRevokedSession() {
        when(redisUtils.hasKey(anyString())).thenReturn(false);
        when(revocationService.isRevokedHash("sid-hash")).thenReturn(true);

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-2", "sid-hash"));

        assertTrue(result.hasErrors(), "登出后该会话签出的令牌必须失效");
    }

    @Test
    @DisplayName("未吊销的令牌通过")
    void acceptsLiveToken() {
        when(redisUtils.hasKey(anyString())).thenReturn(false);
        when(revocationService.isRevokedHash(anyString())).thenReturn(false);

        OAuth2TokenValidatorResult result = validator.validate(jwt("jti-3", "sid-hash"));

        assertFalse(result.hasErrors());
    }

    private static Jwt jwt(String jti, String sid) {
        Jwt.Builder builder = Jwt.withTokenValue("token-" + jti)
                .header("alg", "RS256")
                .claim("sub", "alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (jti != null) {
            builder.claim("jti", jti);
        }
        if (sid != null) {
            builder.claims(claims -> claims.putAll(Map.of("sid", sid)));
        }
        return builder.build();
    }
}
