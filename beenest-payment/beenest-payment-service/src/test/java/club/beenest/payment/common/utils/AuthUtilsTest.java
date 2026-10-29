package club.beenest.payment.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthUtils 单元测试。
 * <p>
 * 验证 payment 服务在 IAM 资源服务模式下，能够正确从 Spring Security 上下文的
 * IAM 访问令牌中读取用户身份（手机号）、角色和权限。
 */
class AuthUtilsTest {

    private static final String ISSUER = "https://iam.example.com/realms/beenest";
    private static final String PHONE = "15000000000";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证已认证上下文下的用户信息解析。
     */
    @Test
    void shouldResolveCurrentUserFromIamAccessToken() {
        Jwt jwt = Jwt.withTokenValue("access-token-123")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("1024")
                .audience(List.of("urn:iam:app:payment"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("uid", 1024)
                .claim("phone_number", PHONE)
                .claim("preferred_username", "zhangsan")
                .claim("nickname", "测试用户")
                .claim("token_type", "access")
                .claim("token_kind", "APPLICATION")
                .claim("permissions", List.of("wallet:read"))
                .claim("app_roles", List.of("ADMIN"))
                .claim("scope", "openid profile")
                .build();

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(
                jwt,
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("wallet:read"),
                        new SimpleGrantedAuthority("SCOPE_openid")
                ),
                "zhangsan");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertTrue(AuthUtils.isLogin());
        assertEquals(PHONE, AuthUtils.getCurrentUserId());
        assertEquals("测试用户", AuthUtils.getCurrentUsername());
        assertEquals("access-token-123", AuthUtils.getCurrentToken());
        assertTrue(AuthUtils.hasRole("admin"));
        assertTrue(AuthUtils.hasPermission("wallet:read"));
        assertEquals(List.of("admin"), AuthUtils.getRoles());
        assertEquals(List.of("wallet:read"), AuthUtils.getPermissions());
        assertEquals(PHONE, AuthUtils.requireCurrentUserId());
    }

    /**
     * 手机号写法差异必须归一到同一主键，否则同一用户会被开成多个钱包。
     */
    @Test
    void shouldNormalizePhoneNumberVariants() {
        String[] variants = {"+86 150-0000-0000", "008615000000000", "8615000000000", "150 0000 0000"};
        for (String variant : variants) {
            Jwt jwt = Jwt.withTokenValue("access-token-norm")
                    .header("alg", "RS256")
                    .subject("1024")
                    .claim("phone_number", variant)
                    .build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

            assertEquals(PHONE, AuthUtils.getCurrentUserId(), "手机号写法未归一: " + variant);
        }
    }

    /**
     * 令牌没有手机号时不回退到 uid / sub / 登录名，直接视为无法识别用户。
     * <p>钱包按手机号建档，任何回退都会让同一自然人在不同入口落到不同钱包。</p>
     */
    @Test
    void shouldNotFallbackToOtherClaimsWhenPhoneMissing() {
        Jwt jwt = Jwt.withTokenValue("access-token-456")
                .header("alg", "RS256")
                .subject("1024")
                .claim("uid", 1024)
                .claim("preferred_username", "lisi")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertNull(AuthUtils.getCurrentUserId());
        assertThrows(SecurityException.class, AuthUtils::requireCurrentUserId);
    }

    /**
     * 验证微信 openid 能从 idp_identities 渠道身份中读取。
     */
    @Test
    void shouldResolveOpenidFromIdpIdentities() {
        Jwt jwt = Jwt.withTokenValue("access-token-789")
                .header("alg", "RS256")
                .subject("1024")
                .claim("phone_number", PHONE)
                .claim("idp_identities", Map.of("wechat-mini", "o4xt-openid"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertEquals("o4xt-openid", AuthUtils.getCurrentAttribute("openid", "wechatOpenid"));
        assertNull(AuthUtils.getCurrentAttribute("alipayUserId"));
    }

    /**
     * 验证未认证上下文时的兜底行为。
     */
    @Test
    void shouldReturnEmptyValuesWhenUnauthenticated() {
        assertFalse(AuthUtils.isLogin());
        assertNull(AuthUtils.getCurrentUserId());
        assertNull(AuthUtils.getCurrentUsername());
        assertNull(AuthUtils.getCurrentToken());
        assertFalse(AuthUtils.hasRole("admin"));
        assertFalse(AuthUtils.hasPermission("wallet:read"));
        assertTrue(AuthUtils.getRoles().isEmpty());
        assertTrue(AuthUtils.getPermissions().isEmpty());
        assertThrows(SecurityException.class, AuthUtils::requireCurrentUserId);
    }
}
