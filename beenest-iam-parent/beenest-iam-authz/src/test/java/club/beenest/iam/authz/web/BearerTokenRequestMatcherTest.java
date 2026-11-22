package club.beenest.iam.authz.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bearer 请求匹配器测试:CSRF 豁免必须建立在"令牌验签通过"之上,而不是"请求头存在"。
 */
class BearerTokenRequestMatcherTest {

    /** 验签通过的解码器 */
    private final JwtDecoder accepting = token -> Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("alice")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .audience(List.of("console"))
            .build();

    /** 一律拒绝的解码器 */
    private final JwtDecoder rejecting = token -> {
        throw new BadJwtException("签名不合法");
    };

    @Test
    @DisplayName("有效令牌豁免 CSRF")
    void matchesValidToken() {
        assertThat(new BearerTokenRequestMatcher(accepting).matches(request("Bearer abc.def.ghi"))).isTrue();
    }

    @Test
    @DisplayName("伪造令牌不豁免:只看前缀会让 CSRF 防护被一行请求头绕过")
    void rejectsForgedToken() {
        assertThat(new BearerTokenRequestMatcher(rejecting).matches(request("Bearer forged"))).isFalse();
    }

    @Test
    @DisplayName("没有 Authorization 头不豁免")
    void rejectsMissingHeader() {
        assertThat(new BearerTokenRequestMatcher(accepting).matches(request(null))).isFalse();
    }

    @Test
    @DisplayName("非 Bearer 方案不豁免")
    void rejectsOtherScheme() {
        assertThat(new BearerTokenRequestMatcher(accepting).matches(request("Basic dXNlcjpwYXNz"))).isFalse();
    }

    @Test
    @DisplayName("Bearer 后为空不豁免")
    void rejectsEmptyToken() {
        assertThat(new BearerTokenRequestMatcher(accepting).matches(request("Bearer   "))).isFalse();
    }

    @Test
    @DisplayName("方案名大小写不敏感")
    void schemeIsCaseInsensitive() {
        assertThat(new BearerTokenRequestMatcher(accepting).matches(request("bearer abc.def.ghi"))).isTrue();
    }

    /**
     * 构造带 Authorization 头的请求。
     *
     * @param authorization 头值,可为 null
     * @return 请求
     */
    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
