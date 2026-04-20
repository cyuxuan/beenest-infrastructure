package club.beenest.payment.common.utils;

import org.apereo.cas.beenest.client.authentication.CasBearerTokenAuthenticationToken;
import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.beenest.client.session.CasUserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthUtils 单元测试。
 * <p>
 * 验证 payment 服务在 CAS 资源服务模式下，能够正确从 Spring Security
 * 上下文中读取用户身份、角色和权限。
 */
class AuthUtilsTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证已认证上下文下的用户信息解析。
     */
    @Test
    void shouldResolveCurrentUserFromCasSecurityContext() {
        CasUserSession session = new CasUserSession();
        session.setUserId("user-1001");
        session.setNickname("测试用户");
        session.setAttributes(Map.of());

        CasUserDetails details = new CasUserDetails(
                session,
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("wallet:read")
                ));

        CasBearerTokenAuthenticationToken authentication =
                new CasBearerTokenAuthenticationToken("access-token-123", details);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertTrue(AuthUtils.isLogin());
        assertEquals("user-1001", AuthUtils.getCurrentUserId());
        assertEquals("测试用户", AuthUtils.getCurrentUsername());
        assertEquals("access-token-123", AuthUtils.getCurrentToken());
        assertTrue(AuthUtils.hasRole("admin"));
        assertTrue(AuthUtils.hasPermission("wallet:read"));
        assertEquals(List.of("admin"), AuthUtils.getRoles());
        assertEquals(List.of("wallet:read"), AuthUtils.getPermissions());
        assertEquals("user-1001", AuthUtils.requireCurrentUserId());
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
