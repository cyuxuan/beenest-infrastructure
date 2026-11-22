package club.beenest.iam.authz.mfa;

import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话 MFA 票据测试。
 * <p>票据要带上 sid:补验针对的是"已有会话",不是一次新登录。
 * 丢了 sid 就只能新建会话,那等于把补验变成重新登录。</p>
 */
class MfaPendingServiceSessionTest {

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final MfaPendingService service = new MfaPendingService(redisUtils);

    @Test
    @DisplayName("票据消费后带回 sid 与认证方式")
    void consumeReturnsSessionContext() {
        when(redisUtils.get(anyString())).thenReturn("7:sid-9:SMS");

        MfaSessionPending pending = service.consumeSession("default", "tok");

        assertEquals(7L, pending.userId());
        assertEquals("sid-9", pending.sid());
        assertEquals("SMS", pending.loginType());
    }

    @Test
    @DisplayName("票据不存在时返回 null")
    void missingTicket() {
        when(redisUtils.get(anyString())).thenReturn(null);

        assertNull(service.consumeSession("default", "tok"));
    }
}
