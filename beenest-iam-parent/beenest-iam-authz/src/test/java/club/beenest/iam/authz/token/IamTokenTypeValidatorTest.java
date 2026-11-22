package club.beenest.iam.authz.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌类型校验测试:ID Token 不得作为访问令牌,访问令牌必须带受众。
 */
class IamTokenTypeValidatorTest {

    private final IamTokenTypeValidator validator = new IamTokenTypeValidator();

    @Test
    @DisplayName("访问令牌通过校验")
    void acceptsAccessToken() {
        assertThat(validator.validate(jwt("access", List.of("console"))).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("ID Token 被拒绝:不能当作 Bearer 令牌使用")
    void rejectsIdToken() {
        OAuth2TokenValidatorResult result = validator.validate(jwt("id", List.of("console")));
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).hasSize(1);
    }

    @Test
    @DisplayName("缺失类型标记的存量令牌按访问令牌放行,保证升级平滑")
    void acceptsTokenWithoutTypeMarker() {
        assertThat(validator.validate(jwt(null, List.of("console"))).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("缺少受众的令牌被拒绝")
    void rejectsTokenWithoutAudience() {
        assertThat(validator.validate(jwt("access", List.of())).hasErrors()).isTrue();
    }

    /**
     * 构造测试令牌。
     *
     * @param tokenType 令牌类型声明,可为 null
     * @param audience  受众列表
     * @return 令牌
     */
    private static Jwt jwt(String tokenType, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .audience(audience);
        if (tokenType != null) {
            builder.claims(claims -> claims.put(IamTokenTypeValidator.CLAIM_TOKEN_TYPE, tokenType));
        }
        return builder.build();
    }
}
