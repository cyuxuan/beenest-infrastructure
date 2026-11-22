package club.beenest.iam.authz.session;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话升级的入口测试。
 * <p>补验成功的标志是"没有新建会话",而是同一个 sid 被升级。
 * 断言 userSessionService.startSession 没被调用,才能防止实现悄悄退回新建。</p>
 */
class IamLoginSessionServiceUpgradeTest {

    private static final String SID = "sid-1";
    private static final String REALM_KEY = "default";
    /** 登录主体的用户主键;与被升级会话的主体默认一致 */
    private static final Long USER_ID = 1L;

    private final IamUserSessionService userSessionService = mock(IamUserSessionService.class);
    private final AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        // 升级路径不该写安全上下文;普通登录路径会写。用例之间互相隔离,断言才可信
        SecurityContextHolder.clearContext();
    }

    private IamLoginSessionService service() {
        // 构造参数取自现有实现,只把 challengeService 插在第 2 位:
        // (userSessionService, challengeService, loginEventPublisher, securityContextRepository, cookieSecure)
        return new IamLoginSessionService(userSessionService, challengeService,
                mock(club.beenest.iam.authz.audit.LoginEventPublisher.class),
                mock(org.springframework.security.web.context.SecurityContextRepository.class), false);
    }

    @Test
    @DisplayName("挑战中且方式命中时升级会话,不新建")
    void upgradesInsteadOfCreating() {
        IamLoginSessionService service = service();
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve(REALM_KEY, null, SID)).thenReturn(challenge);
        // 既有会话已完成二步验证:升级必须把它原样带过去。
        // mfaVerified 的 null 语义是"重置为未验证",与 loginType 的 null 恰好相反
        when(userSessionService.loadSession(SID)).thenReturn(payload(USER_ID, Boolean.TRUE));
        when(userSessionService.upgradeAuthentication(SID, "SMS", Boolean.TRUE)).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean upgraded = service.establish(requestWithSessionCookie(), response, principal(), "SMS", null);

        assertTrue(upgraded);
        // 定位挑战靠请求里的会话 Cookie:不读 Cookie 的实现不该通过
        verify(challengeService).resolve(REALM_KEY, null, SID);
        verify(userSessionService).upgradeAuthentication(SID, "SMS", Boolean.TRUE);
        verify(userSessionService, never()).startSession(any(), anyString(), any(), any());
        // 票据一次性:补验成功必须消费掉,否则同一张票据可以反复尝试不同方式
        verify(challengeService).consume(REALM_KEY, challenge);
        // 升级不碰浏览器侧:不写 Cookie、不重设安全上下文,否则等于又建了一次登录态
        assertEquals(0, response.getCookies().length);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("会话已不存在时不报成功,孤儿票顺手烧掉")
    void doesNotReportSuccessWhenSessionGone() {
        IamLoginSessionService service = service();
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve(REALM_KEY, null, SID)).thenReturn(challenge);
        // 会话被全端下线、注销后 Cookie 未清或已过期:票据成了孤儿票
        when(userSessionService.loadSession(SID)).thenReturn(null);

        boolean upgraded = service.establish(requestWithSessionCookie(), new MockHttpServletResponse(),
                principal(), "SMS", null);

        // 不能返回 true:调用方会据此把浏览器送回授权端点,而那里根本没有登录态
        assertFalse(upgraded);
        verify(userSessionService).startSession(any(), eq("SMS"), any(), any());
        // 孤儿票不会再被任何会话用到,顺手烧掉;但升级绝不能发生
        verify(challengeService).consume(REALM_KEY, challenge);
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
    }

    @Test
    @DisplayName("票据属于别人的会话时忽略,不消费也不升级")
    void ignoresTicketOwnedByAnotherUser() {
        IamLoginSessionService service = service();
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve(REALM_KEY, null, SID)).thenReturn(challenge);
        // 令牌出现在挑战页 URL 与联邦回跳参数里:另一个账号拿到它也不能改写受害会话
        when(userSessionService.loadSession(SID)).thenReturn(payload(2L, Boolean.TRUE));

        boolean upgraded = service.establish(requestWithSessionCookie(), new MockHttpServletResponse(),
                principal(), "SMS", null);

        assertFalse(upgraded);
        verify(userSessionService).startSession(any(), eq("SMS"), any(), any());
        // 不消费:票据要留给它的主人,消费掉等于帮攻击者把证据一起抹了
        verify(challengeService, never()).consume(any(), any());
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
    }

    @Test
    @DisplayName("挑战中但方式不命中时按普通登录处理")
    void fallsBackToNormalLogin() {
        IamLoginSessionService service = service();
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve(REALM_KEY, null, SID)).thenReturn(challenge);

        boolean upgraded = service.establish(requestWithSessionCookie(), new MockHttpServletResponse(),
                principal(), "PASSWORD", null);

        assertFalse(upgraded);
        verify(userSessionService).startSession(any(), eq("PASSWORD"), any(), any());
        // 方式不命中时票据必须保留:用户可能只是去访问门户,换一种方式补验的机会不能被吃掉
        verify(challengeService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("浏览器残留同一账号旧会话时,新登录先结束旧会话")
    void endsPreviousSessionOfSameUser() {
        IamLoginSessionService service = service();
        // 关浏览器不会结束服务端会话:重开登录时旧 sid 仍在 Redis 里
        when(userSessionService.loadSession(SID)).thenReturn(payload(USER_ID, Boolean.TRUE));

        boolean upgraded = service.establish(requestWithSessionCookie(), new MockHttpServletResponse(),
                principal(), "PASSWORD", null);

        assertFalse(upgraded);
        // 不收尾就会同时挂着两条活跃会话,会话列表也会出现两条"在线"
        verify(userSessionService).replaceSession(SID);
        verify(userSessionService).startSession(any(), eq("PASSWORD"), any(), any());
    }

    @Test
    @DisplayName("残留会话属于其他账号时不结束:共用设备不能顺手把别人踢下线")
    void keepsPreviousSessionOfAnotherUser() {
        IamLoginSessionService service = service();
        when(userSessionService.loadSession(SID)).thenReturn(payload(2L, Boolean.TRUE));

        service.establish(requestWithSessionCookie(), new MockHttpServletResponse(),
                principal(), "PASSWORD", null);

        verify(userSessionService, never()).replaceSession(any());
        verify(userSessionService).startSession(any(), eq("PASSWORD"), any(), any());
    }

    /** 待处理的准入挑战:只接受短信方式补验。 */
    private static AdmissionChallenge challenge() {
        return new AdmissionChallenge(
                SID, "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
    }

    /** 登录主体,与被升级会话默认属于同一个用户。 */
    private static IamUserPrincipal principal() {
        return new IamUserPrincipal(USER_ID, 1L, REALM_KEY, "zhang", "张三", 0,
                List.of(), Map.of(), 3L, "PASSWORD");
    }

    /** 既有会话快照。 */
    private static IamSessionPayload payload(Long userId, Boolean mfaVerified) {
        long now = System.currentTimeMillis();
        IamUserPrincipal sessionPrincipal = new IamUserPrincipal(userId, 1L, REALM_KEY, "zhang", "张三",
                0, List.of(), Map.of(), 3L, "PASSWORD");
        return new IamSessionPayload(sessionPrincipal, now, 3600L, 1800L, now, mfaVerified);
    }

    /** 带着既有会话 Cookie 的请求。 */
    private static MockHttpServletRequest requestWithSessionCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(IamSessionCookie.COOKIE_NAME, SID));
        return request;
    }
}
