package club.beenest.iam.authz.admission;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挑战票据测试。
 * <p>票据是一次性的:补验成功后必须消费掉,否则用户可以在同一张票据上
 * 反复尝试不同的方式,等于把准入判定降级成"多试几次总能过"。</p>
 */
class AdmissionChallengeServiceTest {

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final AdmissionChallengeService service = new AdmissionChallengeService(redisUtils);

    @Test
    @DisplayName("签发后能按 sid 找回,按 token 也能找回")
    void issuesAndFinds() {
        when(redisUtils.getObject(any(), eq(AdmissionChallenge.class))).thenReturn(null);

        AdmissionChallenge challenge = service.issue("default", "sid-1", "app",
                new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false),
                "/realms/default/oauth2/authorize?client_id=app");

        assertNotNull(challenge);
        assertEquals("sid-1", challenge.sid());
        assertEquals("app", challenge.clientId());
        assertTrue(challenge.allows("SMS"));
        assertFalse(challenge.allows("PASSWORD"));
        // 两条索引必须都写:只写 sid 的话,SAML 的跨站 POST 回跳(不带 Cookie)
        // 就永远找不到票据,联邦用户会在补验后被当成新登录
        verify(redisUtils).setObject(eq("iam:default:admission:sid:sid-1"),
                any(AdmissionChallenge.class), any(Duration.class));
        verify(redisUtils).setObject(eq("iam:default:admission:token:" + challenge.challengeToken()),
                any(AdmissionChallenge.class), any(Duration.class));
    }

    @Test
    @DisplayName("消费后票据被删除")
    void consumeDeletesTicket() {
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);

        service.consume("default", challenge);

        verify(redisUtils).delete(eq("iam:default:admission:sid:sid-1"));
        verify(redisUtils).delete(eq("iam:default:admission:token:tok"));
    }

    @Test
    @DisplayName("回跳地址不是本域授权端点时拒绝签发")
    void rejectsForeignReturnTo() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false);

        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "https://evil.example.com/steal"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "//evil.example.com/steal"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "/realms/other/oauth2/authorize"));
        // 反斜杠在部分浏览器里等价于 "//",同样会跳出本站
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "/\\evil.example.com/steal"));
        // 路径前缀对但不在授权端点下,一样不能放行
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "/realms/default/evil"));
    }

    @Test
    @DisplayName("本域授权端点的回跳地址放行")
    void acceptsOwnAuthorizeEndpoint() {
        assertTrue(AdmissionChallengeService.isSafeReturnTo(
                "/realms/default/oauth2/authorize?client_id=app", "default"));
    }

    @Test
    @DisplayName("只有令牌没有 sid 时也能定位:跨站联邦回跳不带 Cookie 的情形")
    void resolvesByTokenWithoutSid() {
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
        when(redisUtils.getObject(eq("iam:default:admission:token:tok"), eq(AdmissionChallenge.class)))
                .thenReturn(challenge);

        assertEquals(challenge, service.resolve("default", "tok", null));
    }

    @Test
    @DisplayName("令牌不匹配时定位不到")
    void rejectsWrongToken() {
        when(redisUtils.getObject(eq("iam:default:admission:token:bad"), eq(AdmissionChallenge.class)))
                .thenReturn(null);

        assertNull(service.resolve("default", "bad", null));
    }

    @Test
    @DisplayName("令牌属于别的会话时不予采用:否则可借他人票据改写本会话")
    void rejectsTokenOfAnotherSession() {
        AdmissionChallenge otherSessions = new AdmissionChallenge(
                "other", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
        when(redisUtils.getObject(eq("iam:default:admission:token:tok"), eq(AdmissionChallenge.class)))
                .thenReturn(otherSessions);

        // 本会话(sid-1)没有待处理票据,回落 sid 索引也为空
        assertNull(service.resolve("default", "tok", "sid-1"));
    }

    @Test
    @DisplayName("会话标识为空时只靠令牌定位,不受一致性校验影响")
    void stillResolvesByTokenWithoutSid() {
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
        when(redisUtils.getObject(eq("iam:default:admission:token:tok"), eq(AdmissionChallenge.class)))
                .thenReturn(challenge);

        assertEquals(challenge, service.resolve("default", "tok", ""));
    }

    @Test
    @DisplayName("配置损坏(denyAll)的策略不签发挑战:否则空白名单会被当成放行一切")
    void rejectsDenyAllPolicy() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", ClientAdmissionPolicy.denyAllPolicy(),
                "/realms/default/oauth2/authorize"));

        assertTrue(error.getMessage().contains("不可用"));
    }

    @Test
    @DisplayName("缺少会话标识时不签发:无 sid 票据会让任何会话凭 token 采用")
    void rejectsMissingSid() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false);

        assertThrows(BusinessException.class, () -> service.issue(
                "default", null, "app", policy, "/realms/default/oauth2/authorize"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", " ", "app", policy, "/realms/default/oauth2/authorize"));
    }
}
