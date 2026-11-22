package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话升级测试。
 * <p>补验走"升级"而非"新建"的核心原因:新建会让存续时长重新计时,
 * 反复补验就成了无限续期的后门。这里把这条不变式钉死。</p>
 */
class IamUserSessionServiceUpgradeTest {

    private final UserSessionStore store = mock(UserSessionStore.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final IamUserSessionService service = new IamUserSessionService(store, redisUtils);

    @Test
    @DisplayName("升级保留存续起算点与最大时长,TTL 按剩余时长算")
    void upgradeKeepsLifetimeAndDoesNotResetTtl() {
        long now = System.currentTimeMillis();
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        // 已空闲 1500 秒:剩余空闲只剩约 300 秒,与满额的 1800 / 3600 差距足够大,
        // 才能区分"按剩余时长算"与"满额重置"
        IamSessionPayload payload = new IamSessionPayload(
                principal, now - 60_000L, 3600L, 1800L, now - 1_500_000L, null);
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(payload);

        service.upgradeAuthentication("sid-1", "SMS", Boolean.TRUE);

        ArgumentCaptor<IamSessionPayload> captor = ArgumentCaptor.forClass(IamSessionPayload.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisUtils).setObject(eq("iam:session:sid-1"), captor.capture(), ttlCaptor.capture());
        IamSessionPayload upgraded = captor.getValue();
        assertEquals(now - 60_000L, upgraded.createdTime());
        assertEquals(3600L, upgraded.maxLifetimeSeconds());
        assertEquals("SMS", upgraded.principal().getLoginType());
        assertTrue(Boolean.TRUE.equals(upgraded.mfaVerified()));
        // 补验不能成为续期后门:TTL 必须是剩余时长(约 300 秒),
        // 不是 1800(空闲满额)也不是 3600(最大存续满额)
        long ttlSeconds = ttlCaptor.getValue().toSeconds();
        assertTrue(ttlSeconds >= 295 && ttlSeconds <= 300, "TTL 应为剩余时长,实际 " + ttlSeconds);
        // 数据库行也要同步,否则管理端"在线会话"还显示首次登录的方式
        verify(store).updateLoginType("sid-1", "SMS");
    }

    @Test
    @DisplayName("会话不存在时静默返回,不写库也不写 Redis")
    void missingSessionIsNoop() {
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(null);

        service.upgradeAuthentication("sid-1", "SMS", Boolean.TRUE);

        verify(redisUtils, never()).setObject(any(), any(), any(Duration.class));
        verify(store, never()).updateLoginType(any(), any());
    }

    @Test
    @DisplayName("空闲超时未配置时按最大存续时长算 TTL,不把会话压成 1 秒")
    void upgradeWithoutIdleTimeoutUsesMaxLifetime() {
        // idleSeconds = 0 表示域未配置空闲超时,不构成约束;
        // 直接拿它相减会得到负数,升级后会话 1 秒即失效、用户当场掉登录
        long now = System.currentTimeMillis();
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        IamSessionPayload payload = new IamSessionPayload(
                principal, now, 3600L, 0L, now, null);
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(payload);

        service.upgradeAuthentication("sid-1", "SMS", Boolean.TRUE);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisUtils).setObject(eq("iam:session:sid-1"), any(), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().getSeconds() > 3000L);
    }

    @Test
    @DisplayName("活跃刷新保留 MFA 完成态,不把它清空")
    void touchSessionKeepsMfaVerified() {
        // touchSession 会重建整份快照。若重建时把 mfaVerified 补成 null,
        // 用户补验成功后,下一次请求(默认每分钟一次刷新)就会退回未验证状态,
        // 表现为"补验永远过不去"。这条用例钉住那个静默回退
        long now = System.currentTimeMillis();
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "SMS");
        // lastTouchMillis 距今超过节流窗口(60 秒),才会真正走重建分支
        IamSessionPayload payload = new IamSessionPayload(
                principal, now - 600_000L, 3600L, 1800L, now - 120_000L, Boolean.TRUE);
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(payload);

        service.touchSession("sid-1");

        ArgumentCaptor<IamSessionPayload> captor = ArgumentCaptor.forClass(IamSessionPayload.class);
        verify(redisUtils).setObject(eq("iam:session:sid-1"), captor.capture(), any(Duration.class));
        assertTrue(Boolean.TRUE.equals(captor.getValue().mfaVerified()));
        assertEquals("SMS", captor.getValue().principal().getLoginType());
    }
}
