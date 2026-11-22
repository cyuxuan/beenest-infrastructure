package club.beenest.iam.authz.session;

import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话在线状态订正测试。
 * <p>数据库行的 ACTIVE 状态要等巡检才订正,会话列表直接照搬会把已经失效的会话
 * 显示成在线;读列表时以 Redis 快照为准订正一次,Redis 不可用时宁可按在线处理。</p>
 */
class IamUserSessionServiceLivenessTest {

    private static final String SID = "sid-1";
    private static final String KEY = "iam:session:" + SID;

    private final UserSessionStore store = mock(UserSessionStore.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final IamUserSessionService service = new IamUserSessionService(store, redisUtils);

    @Test
    @DisplayName("快照还在:判定在线,不改库")
    void keepsLiveSession() {
        when(redisUtils.hasKey(KEY)).thenReturn(true);

        assertThat(service.expireIfStale(SID)).isTrue();
        verify(store, never()).updateState(anyString(), anyString());
    }

    @Test
    @DisplayName("快照已消失:判定失效并把库行订正为已过期")
    void marksStaleSessionExpired() {
        when(redisUtils.hasKey(KEY)).thenReturn(false);

        assertThat(service.expireIfStale(SID)).isFalse();
        verify(store).updateState(SID, UserSessionModel.STATE_EXPIRED);
    }

    @Test
    @DisplayName("Redis 不可用时按在线处理,不误伤用户")
    void keepsSessionWhenRedisFails() {
        when(redisUtils.hasKey(KEY)).thenThrow(new IllegalStateException("redis down"));

        assertThat(service.expireIfStale(SID)).isTrue();
        verify(store, never()).updateState(anyString(), anyString());
    }

    @Test
    @DisplayName("空会话标识直接判定失效")
    void treatsBlankSidAsDead() {
        assertThat(service.expireIfStale(null)).isFalse();
        assertThat(service.expireIfStale(" ")).isFalse();
    }
}
