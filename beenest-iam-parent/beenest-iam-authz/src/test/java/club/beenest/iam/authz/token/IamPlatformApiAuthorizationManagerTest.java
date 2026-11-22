package club.beenest.iam.authz.token;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IAM 自身接口的令牌类别判定测试。
 */
class IamPlatformApiAuthorizationManagerTest {

    private final IamPlatformApiAuthorizationManager manager = new IamPlatformApiAuthorizationManager();

    @Test
    @DisplayName("浏览器会话放行:权限粒度交给方法级权限码")
    void allowsSessionAuthentication() {
        IamUserPrincipal principal = new IamUserPrincipal(1L, 1L, "default", "admin", "管理员", 0,
                List.of("user:view"), Map.of(), 1L);
        assertThat(decide(new TestingAuthenticationToken(principal, null))).isTrue();
    }

    @Test
    @DisplayName("平台第一方令牌放行")
    void allowsPlatformToken() {
        assertThat(decide(jwtAuthentication(IamPlatformApiAuthorizationManager.KIND_PLATFORM))).isTrue();
    }

    @Test
    @DisplayName("凭据模式的机器令牌放行:它本就是为调用管理接口而生")
    void allowsMachineToken() {
        assertThat(decide(jwtAuthentication(IamPlatformApiAuthorizationManager.KIND_MACHINE))).isTrue();
    }

    @Test
    @DisplayName("业务应用令牌拒绝:应用令牌不该反过来调 IAM 自身接口")
    void rejectsApplicationToken() {
        assertThat(decide(jwtAuthentication(IamPlatformApiAuthorizationManager.KIND_APPLICATION))).isFalse();
    }

    @Test
    @DisplayName("缺失类别标记的令牌拒绝:默认拒绝优于默认放行")
    void rejectsTokenWithoutKind() {
        assertThat(decide(jwtAuthentication(null))).isFalse();
    }

    @Test
    @DisplayName("匿名请求拒绝,由异常转换环节给出 401")
    void rejectsAnonymous() {
        AnonymousAuthenticationToken anonymous = new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(decide(anonymous)).isFalse();
    }

    /**
     * 执行判定。
     *
     * @param authentication 当前认证
     * @return 是否放行
     */
    private boolean decide(org.springframework.security.core.Authentication authentication) {
        Supplier<org.springframework.security.core.Authentication> supplier = () -> authentication;
        AuthorizationDecision decision = manager.authorize(supplier, null);
        return decision.isGranted();
    }

    /**
     * 构造携带类别声明的令牌认证。
     *
     * @param kind 令牌类别,可为 null
     * @return 令牌认证
     */
    private static JwtAuthenticationToken jwtAuthentication(String kind) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .audience(List.of("console"));
        if (kind != null) {
            builder.claims(claims -> claims.putAll(Map.of(
                    IamPlatformApiAuthorizationManager.CLAIM_TOKEN_KIND, kind)));
        }
        return new JwtAuthenticationToken(builder.build());
    }
}
