package club.beenest.iam.authz.logout;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.core.model.UserSessionModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一登出入口测试。
 * <p>钉住三条:登出要标记会话吊销(访问令牌随之失效)、要广播到应用、要结束本地会话;
 * 全端下线对每个活跃会话走同一套动作。</p>
 */
class IamLogoutServiceTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final String REALM_KEY = "default";
    private static final String SID = "sid-1";

    private IamUserSessionService userSessionService;
    private IamLogoutBroadcastService broadcastService;
    private SessionRevocationService revocationService;
    private IamLogoutService service;

    @BeforeEach
    void setUp() {
        userSessionService = mock(IamUserSessionService.class);
        broadcastService = mock(IamLogoutBroadcastService.class);
        revocationService = mock(SessionRevocationService.class);
        service = new IamLogoutService(userSessionService, broadcastService, revocationService);
    }

    @Test
    @DisplayName("登出:标记吊销、广播、结束会话三步都执行")
    void logoutRevokesBroadcastsAndEndsSession() {
        IamUserPrincipal principal = principal();
        when(userSessionService.loadSession(SID)).thenReturn(payload(principal));
        when(userSessionService.findBySid(SID)).thenReturn(session(SID));

        service.logout(SID);

        verify(revocationService).revoke(eq(SID), any(Duration.class));
        verify(broadcastService).broadcast(REALM_ID, REALM_KEY, SID, principal);
        verify(userSessionService).endSession(SID);
    }

    @Test
    @DisplayName("会话快照已消失:仍标记吊销并结束会话,广播按库中域定位")
    void logoutWithoutSnapshotStillEndsSession() {
        when(userSessionService.loadSession(SID)).thenReturn(null);
        when(userSessionService.findBySid(SID)).thenReturn(session(SID));

        service.logout(SID);

        verify(revocationService).revoke(eq(SID), org.mockito.ArgumentMatchers.nullable(Duration.class));
        verify(broadcastService).broadcast(eq(REALM_ID), any(), eq(SID), org.mockito.ArgumentMatchers.isNull());
        verify(userSessionService).endSession(SID);
    }

    @Test
    @DisplayName("广播异常不阻断本地会话结束")
    void broadcastFailureDoesNotBlockLogout() {
        when(userSessionService.loadSession(SID)).thenReturn(payload(principal()));
        when(userSessionService.findBySid(SID)).thenReturn(session(SID));
        when(broadcastService.broadcast(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("网络不可达"));

        service.logout(SID);

        verify(userSessionService).endSession(SID);
    }

    @Test
    @DisplayName("空会话标识不做任何动作")
    void blankSidIsIgnored() {
        service.logout("  ");

        verify(userSessionService, never()).endSession(any());
        verify(revocationService, never()).revoke(any(), any());
    }

    @Test
    @DisplayName("全端下线:每个活跃会话各自吊销并广播")
    void logoutAllHandlesEveryActiveSession() {
        when(userSessionService.loadSession("sid-1")).thenReturn(payload(principal()));
        when(userSessionService.loadSession("sid-2")).thenReturn(payload(principal()));
        when(userSessionService.listSessions(REALM_ID, USER_ID)).thenReturn(List.of(
                session("sid-1"), session("sid-2"), sessionWithState("sid-3", UserSessionModel.STATE_EXPIRED)));

        int ended = service.logoutAll(REALM_ID, USER_ID);

        org.junit.jupiter.api.Assertions.assertEquals(2, ended);
        verify(userSessionService).endSession("sid-1");
        verify(userSessionService).endSession("sid-2");
        verify(userSessionService, never()).endSession("sid-3");
        verify(broadcastService, times(2)).broadcast(eq(REALM_ID), eq(REALM_KEY), any(), any());
    }

    // ===== 测试数据 =====

    private static IamUserPrincipal principal() {
        return new IamUserPrincipal(USER_ID, REALM_ID, REALM_KEY, "alice", "爱丽丝", 0,
                List.of(), Map.of());
    }

    private static IamSessionPayload payload(IamUserPrincipal principal) {
        long now = System.currentTimeMillis();
        return new IamSessionPayload(principal, now, 3600L, 1800, now, null, Set.of());
    }

    private static UserSessionModel session(String sid) {
        return sessionWithState(sid, UserSessionModel.STATE_ACTIVE);
    }

    private static UserSessionModel sessionWithState(String sid, String state) {
        return new UserSessionModel(1L, REALM_ID, sid, USER_ID, "PASSWORD", "10.0.0.1",
                "JUnit", state, null, null, null);
    }
}
