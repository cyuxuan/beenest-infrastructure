package club.beenest.iam.authz.session;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.common.concurrent.IamDistributedLock;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话巡检测试。
 * <p>会话状态存在共享 Redis,数据库行只在会话被显式结束时才改写。
 * 用户关掉浏览器、实例重启等场景下 Redis 键会自然消失,而数据库行仍停在
 * ACTIVE——巡检任务负责把这类"僵尸会话"收尾,否则"在线会话"列表长期失真。</p>
 */
class IamSessionSweeperJobTest {

    private final UserSessionStore userSessionStore = mock(UserSessionStore.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);
    private final UserProvider userProvider = mock(UserProvider.class);
    private final LoginEventPublisher loginEventPublisher = mock(LoginEventPublisher.class);
    private final IamDistributedLock distributedLock = mock(IamDistributedLock.class);

    private final IamSessionSweeperJob job = new IamSessionSweeperJob(
            userSessionStore, redisUtils, realmProvider, userProvider, loginEventPublisher, distributedLock);

    @Test
    @DisplayName("Redis 键已消失的会话:置为过期并留痕")
    void expiresSessionWhoseSnapshotIsGone() {
        // 1. 数据库行仍是活跃,但 Redis 键已经不在
        when(userSessionStore.listActive(500)).thenReturn(List.of(
                new UserSessionModel(1L, 2L, "sid-1", 3L, "PASSWORD",
                        "10.0.0.1", "UA", UserSessionModel.STATE_ACTIVE, null, null, null)));
        when(redisUtils.hasKey(IamUserSessionService.sessionKey("sid-1"))).thenReturn(false);
        when(userSessionStore.expireIfActive("sid-1")).thenReturn(true);
        when(realmProvider.findById(2L)).thenReturn(Optional.of(
                new RealmModel(2L, "hr", "人力资源", true, false,
                        1800L, 604800L, 1800L, 28800L, null, null)));
        when(userProvider.findById(3L)).thenReturn(Optional.of(
                new UserModel(3L, 2L, "zhangsan", "张三", null, null, 1, "INTERNAL", false, 0, Map.of())));

        // 2. 巡检一轮
        job.sweepOnce();

        // 3. 记录置为过期,并按"会话超时"留痕(与主动注销分开)
        verify(userSessionStore).expireIfActive("sid-1");
        verify(loginEventPublisher).publish(eq("hr"), eq(3L), eq("zhangsan"),
                eq(LoginEventEvent.SESSION_TIMEOUT), eq("PASSWORD"),
                eq("10.0.0.1"), eq("UA"), anyString());
    }

    @Test
    @DisplayName("Redis 键仍在的会话:不动它")
    void keepsSessionWhoseSnapshotIsAlive() {
        when(userSessionStore.listActive(500)).thenReturn(List.of(
                new UserSessionModel(1L, 2L, "sid-2", 3L, "PASSWORD",
                        "10.0.0.1", "UA", UserSessionModel.STATE_ACTIVE, null, null, null)));
        when(redisUtils.hasKey(IamUserSessionService.sessionKey("sid-2"))).thenReturn(true);

        job.sweepOnce();

        verify(userSessionStore, never()).expireIfActive(anyString());
        verify(loginEventPublisher, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("别的实例已抢先收尾时:不重复记账")
    void doesNotDoubleCountWhenOthersWonTheRace() {
        when(userSessionStore.listActive(500)).thenReturn(List.of(
                new UserSessionModel(1L, 2L, "sid-3", 3L, "PASSWORD",
                        "10.0.0.1", "UA", UserSessionModel.STATE_ACTIVE, null, null, null)));
        when(redisUtils.hasKey(IamUserSessionService.sessionKey("sid-3"))).thenReturn(false);
        // 条件更新返回 false:说明这条记录已被别的路径改成过期了
        when(userSessionStore.expireIfActive("sid-3")).thenReturn(false);

        job.sweepOnce();

        verify(loginEventPublisher, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("多实例下抢不到锁就跳过,不重复巡检")
    void skipsWhenAnotherInstanceHoldsTheLock() {
        when(distributedLock.tryLock(anyString(), any())).thenReturn(null);

        job.sweep();

        verify(userSessionStore, never()).listActive(anyInt());
    }

    @Test
    @DisplayName("没有活跃会话时不做事")
    void doesNothingWhenNoActiveSession() {
        when(userSessionStore.listActive(500)).thenReturn(List.of());

        job.sweepOnce();

        verify(userSessionStore, never()).expireIfActive(anyString());
    }
}
