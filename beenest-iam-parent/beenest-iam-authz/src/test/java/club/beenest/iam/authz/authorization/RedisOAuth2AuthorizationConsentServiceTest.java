package club.beenest.iam.authz.authorization;

import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 授权同意存储测试。
 * <p>授予时间与同意记录分两个键存放,任何一侧漏删都会让用户中心显示出"已撤销但仍在授权"的假象,
 * 用例把写入与删除两侧都钉住。</p>
 */
class RedisOAuth2AuthorizationConsentServiceTest {

    private static final String CONSENT_KEY = "iam:consent:3:alice";
    private static final String GRANTED_KEY = "iam:consent-granted:3:alice";

    private RedisUtils redisUtils;
    private RedisOAuth2AuthorizationConsentService service;

    @BeforeEach
    void setUp() {
        redisUtils = mock(RedisUtils.class);
        service = new RedisOAuth2AuthorizationConsentService(redisUtils);
    }

    @Test
    @DisplayName("保存同意时同时记录首次授予时间")
    void saveWritesGrantedTime() {
        service.save(consent(Set.of("openid", "profile")));

        verify(redisUtils).setObject(eq(CONSENT_KEY), any(), any(Duration.class));
        verify(redisUtils).setIfAbsent(eq(GRANTED_KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("删除同意时两个键一起删除")
    void removeDeletesBothKeys() {
        service.remove(consent(Set.of("openid")));

        verify(redisUtils).delete(CONSENT_KEY, GRANTED_KEY);
    }

    @Test
    @DisplayName("按登录名列举只返回完全匹配的记录")
    void findByPrincipalMatchesExactly() {
        when(redisUtils.executeScan(anyString()))
                .thenReturn(new LinkedHashSet<>(Set.of(
                        "iam:consent:3:alice",
                        "iam:consent:3:alice2",
                        "iam:consent:4:alice")));
        when(redisUtils.getObject("iam:consent:3:alice", Set.class)).thenReturn(Set.of("openid"));
        when(redisUtils.getObject("iam:consent:4:alice", Set.class)).thenReturn(Set.of("email"));

        Map<String, Set<String>> result = service.findByPrincipal("alice");

        assertEquals(2, result.size());
        assertTrue(result.containsKey("3"));
        assertTrue(result.containsKey("4"));
        assertEquals(Set.of("openid"), result.get("3"));
    }

    @Test
    @DisplayName("登录名为空时不扫键")
    void findByPrincipalSkipsBlank() {
        assertTrue(service.findByPrincipal("  ").isEmpty());
    }

    @Test
    @DisplayName("授予时间按毫秒解析,非法值回落为 null")
    void grantedAtParsesEpochMillis() {
        when(redisUtils.get(GRANTED_KEY)).thenReturn("1700000000000");
        assertEquals(1_700_000_000_000L, service.grantedAt("3", "alice"));

        when(redisUtils.get(GRANTED_KEY)).thenReturn("not-a-number");
        assertNull(service.grantedAt("3", "alice"));
    }

    @Test
    @DisplayName("撤销时同意记录不存在返回 false")
    void revokeReturnsFalseWhenAbsent() {
        when(redisUtils.hasKey(CONSENT_KEY)).thenReturn(false);

        assertFalse(service.revoke("3", "alice"));
    }

    @Test
    @DisplayName("撤销时同意记录存在则一并清理授予时间")
    void revokeDeletesBothKeys() {
        when(redisUtils.hasKey(CONSENT_KEY)).thenReturn(true);

        assertTrue(service.revoke("3", "alice"));
        verify(redisUtils).delete(CONSENT_KEY, GRANTED_KEY);
    }

    /**
     * 构造一条同意记录。
     *
     * @param scopes 同意的 scope
     * @return 同意记录
     */
    private static OAuth2AuthorizationConsent consent(Set<String> scopes) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        scopes.forEach(scope -> authorities.add(new SimpleGrantedAuthority(scope)));
        return OAuth2AuthorizationConsent.withId("3", "alice")
                .authorities(set -> set.addAll(authorities))
                .build();
    }
}
