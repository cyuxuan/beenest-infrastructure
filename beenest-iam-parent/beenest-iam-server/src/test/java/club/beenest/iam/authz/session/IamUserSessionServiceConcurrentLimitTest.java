package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 并发会话上限测试。
 * <p>策略语义是"新登录顶掉最旧会话",不是"拒绝新登录";
 * 这里钉住淘汰顺序、存活判定与不限并发时的零副作用。</p>
 */
class IamUserSessionServiceConcurrentLimitTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 7L;

    private final UserSessionStore store = mock(UserSessionStore.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
    private final IamUserSessionService service =
            new IamUserSessionService(store, redisUtils, null, policyManager);

    @AfterEach
    void clearContext() {
        RealmContextHolder.clear();
    }

    @Test
    @DisplayName("超限时顶掉最旧会话并记为 REPLACED,跳过只剩库记录的僵尸会话")
    void evictsOldestLiveSessionAndSkipsStaleOnes() {
        RealmContextHolder.set(new RealmContext(REALM_ID, "default", realm()));
        when(policyManager.getLoginPolicy(REALM_ID, "default")).thenReturn(policy(2));
        // 由新到旧:最新一条、一条旧会话、一条库行仍为 ACTIVE 但 Redis 已失效的僵尸会话
        when(store.listActiveByUser(REALM_ID, USER_ID)).thenReturn(List.of(
                session("sid-latest"),
                session("sid-old"),
                session("sid-stale")));
        when(redisUtils.hasKey("iam:session:sid-latest")).thenReturn(true);
        when(redisUtils.hasKey("iam:session:sid-old")).thenReturn(true);
        when(redisUtils.hasKey("iam:session:sid-stale")).thenReturn(false);

        service.startSession(principal(), "PASSWORD", "10.0.0.1", "UA");

        // 上限 2、新会话占一个名额:只留下最新一条,旧会话被顶下线
        verify(redisUtils).delete("iam:session:sid-old");
        verify(store).updateState("sid-old", UserSessionModel.STATE_REPLACED);
        // 僵尸会话没有活跃快照,不该占用淘汰名额,也不该被改写状态
        verify(redisUtils, never()).delete("iam:session:sid-stale");
        verify(store, never()).updateState(eq("sid-stale"), anyString());
        verify(redisUtils, never()).delete("iam:session:sid-latest");
    }

    @Test
    @DisplayName("0 表示不限制,不查会话也不淘汰")
    void zeroLimitMeansUnlimited() {
        RealmContextHolder.set(new RealmContext(REALM_ID, "default", realm()));
        when(policyManager.getLoginPolicy(REALM_ID, "default")).thenReturn(policy(0));

        service.startSession(principal(), "PASSWORD", "10.0.0.1", "UA");

        verify(store, never()).listActiveByUser(any(), any());
        verify(redisUtils, never()).delete(anyString());
    }

    @Test
    @DisplayName("策略读取失败时放行登录,不因限流把用户锁在门外")
    void policyReadFailureDoesNotBlockLogin() {
        RealmContextHolder.set(new RealmContext(REALM_ID, "default", realm()));
        when(policyManager.getLoginPolicy(REALM_ID, "default"))
                .thenThrow(new IllegalStateException("redis down"));

        service.startSession(principal(), "PASSWORD", "10.0.0.1", "UA");

        verify(store, never()).listActiveByUser(any(), any());
        verify(store).insert(any(UserSessionModel.class));
    }

    private static LoginPolicy policy(int maxConcurrentSessions) {
        LoginPolicy policy = new LoginPolicy();
        policy.setMaxConcurrentSessions(maxConcurrentSessions);
        return policy;
    }

    private static UserSessionModel session(String sid) {
        return new UserSessionModel(null, REALM_ID, sid, USER_ID, "PASSWORD", "10.0.0.1", "UA",
                UserSessionModel.STATE_ACTIVE, null, null, null);
    }

    private static IamUserPrincipal principal() {
        return new IamUserPrincipal(USER_ID, REALM_ID, "default", "zhang", "张三", 0,
                List.of(), Map.of(), 3L, "PASSWORD");
    }

    private static RealmModel realm() {
        return new RealmModel(REALM_ID, "default", "默认域", true, true,
                300L, 3600L, 1800L, 3600L, SubjectMode.LOGIN_NAME, null);
    }
}
