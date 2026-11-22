package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.mfa.MfaSessionPending;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话级 MFA 补验测试。
 * <p>补验成功的标志是同一个 sid 被置上二步验证完成态,且票据被消费;
 * 断言"没新建会话、没改第一因素"才能防止实现退回成一次普通登录。</p>
 * <p>会话级补验还要证明"动态口令的持有者就是会话主人":挑战页允许输入任意账号,
 * 缺了这条校验,拿着他人会话的攻击者可以用自己的口令把受害者的会话升级。</p>
 */
class AdmissionChallengeMfaTest {

    @Test
    @DisplayName("动态口令正确时升级原会话并消费票据")
    void upgradesSessionOnValidCode() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        IamLoginSessionService loginSessionService = mock(IamLoginSessionService.class);
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-1", "PASSWORD"));
        when(userSessionService.loadSession("sid-1")).thenReturn(sessionPayload(7L));
        when(mfaService.verifyLoginCode("default", 7L, "123456")).thenReturn(true);
        when(userSessionService.upgradeAuthentication("sid-1", null, Boolean.TRUE)).thenReturn(true);

        AdmissionChallengeController controller = controller(challengeService, loginSessionService,
                mfaPendingService, userSessionService, mfaService);

        String view = controller.submitChallenge("default", "tok", null, null,
                "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse());

        assertEquals("redirect:/realms/default/oauth2/authorize", view);
        verify(userSessionService).upgradeAuthentication("sid-1", null, Boolean.TRUE);
        verify(challengeService).consume("default", challenge);
        // 补验只升级原会话:establish 意味着退化成了一次普通登录。
        // 按五参重载断言:控制器走的就是带挑战令牌的那条,四参重载永远不会被命中
        verify(loginSessionService, never()).establish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("MFA 票据属于另一个会话时拒绝,不升级任何会话")
    void rejectsTicketFromAnotherSession() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        IamLoginSessionService loginSessionService = mock(IamLoginSessionService.class);
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-2", "PASSWORD"));
        // 口令校验设为通过:这样拒绝的唯一理由只能是票据与会话不匹配。
        // 若让它默认返回 false,本用例在 sid 判断被摘掉时仍会因"口令错误"而拒绝,
        // 断言就退化成永远为真,守不住那条判断
        when(mfaService.verifyLoginCode("default", 7L, "123456")).thenReturn(true);

        AdmissionChallengeController controller = controller(challengeService, loginSessionService,
                mfaPendingService, userSessionService, mfaService);

        assertThrows(BusinessException.class,
                () -> controller.submitChallenge("default", "tok", null, null,
                        "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse()));
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
        verify(challengeService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("动态口令错误时拒绝,不升级会话也不消费票据")
    void invalidCodeIsRejected() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        IamLoginSessionService loginSessionService = mock(IamLoginSessionService.class);
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-1", "PASSWORD"));
        when(userSessionService.loadSession("sid-1")).thenReturn(sessionPayload(7L));
        when(mfaService.verifyLoginCode("default", 7L, "000000")).thenReturn(false);
        when(userSessionService.upgradeAuthentication("sid-1", null, Boolean.TRUE)).thenReturn(true);

        AdmissionChallengeController controller = controller(challengeService, loginSessionService,
                mfaPendingService, userSessionService, mfaService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitChallenge("default", "tok", null, null,
                        "mfa-1", "000000", new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(ErrorCode.MFA_CODE_INVALID.getCode(), ex.getCode());
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
        verify(challengeService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("动态口令属于另一个用户时拒绝,不升级会话也不消费票据")
    void rejectsCodeFromAnotherUser() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        IamLoginSessionService loginSessionService = mock(IamLoginSessionService.class);
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-1", "PASSWORD"));
        // 票据署名用户 7,会话却属于用户 8:攻击者拿自己的口令升级受害者的会话
        when(userSessionService.loadSession("sid-1")).thenReturn(sessionPayload(8L));
        // 口令本身是正确的那一个:拒绝的唯一理由只能是主体不匹配
        when(mfaService.verifyLoginCode("default", 7L, "123456")).thenReturn(true);
        when(userSessionService.upgradeAuthentication("sid-1", null, Boolean.TRUE)).thenReturn(true);

        AdmissionChallengeController controller = controller(challengeService, loginSessionService,
                mfaPendingService, userSessionService, mfaService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitChallenge("default", "tok", null, null,
                        "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
        verify(challengeService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("会话已消失时拒绝,不升级也不消费票据")
    void rejectsWhenSessionGone() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        IamLoginSessionService loginSessionService = mock(IamLoginSessionService.class);
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-1", "PASSWORD"));
        when(userSessionService.loadSession("sid-1")).thenReturn(null);
        when(mfaService.verifyLoginCode("default", 7L, "123456")).thenReturn(true);
        when(userSessionService.upgradeAuthentication("sid-1", null, Boolean.TRUE)).thenReturn(true);

        AdmissionChallengeController controller = controller(challengeService, loginSessionService,
                mfaPendingService, userSessionService, mfaService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitChallenge("default", "tok", null, null,
                        "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
        verify(challengeService, never()).consume(any(), any());
    }

    @Test
    @DisplayName("升级时会话消失则报失败,不把失败当成功")
    void doesNotReportSuccessWhenSessionVanishesAtUpgrade() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        IamLoginSessionService loginSessionService = mock(IamLoginSessionService.class);
        AdmissionChallenge challenge = challenge();
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-1", "PASSWORD"));
        when(userSessionService.loadSession("sid-1")).thenReturn(sessionPayload(7L));
        when(mfaService.verifyLoginCode("default", 7L, "123456")).thenReturn(true);
        // 读取之后、升级之前会话被并发删除:upgradeAuthentication 返回 false
        when(userSessionService.upgradeAuthentication("sid-1", null, Boolean.TRUE)).thenReturn(false);

        AdmissionChallengeController controller = controller(challengeService, loginSessionService,
                mfaPendingService, userSessionService, mfaService);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitChallenge("default", "tok", null, null,
                        "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        // 票据不消费:调用方不能据此认为补验成功
        verify(challengeService, never()).consume(any(), any());
    }

    private static AdmissionChallenge challenge() {
        return new AdmissionChallenge(
                "sid-1", "app", Set.of(), true,
                "/realms/default/oauth2/authorize", "tok", 1L);
    }

    private static AdmissionChallengeController controller(AdmissionChallengeService challengeService,
                                                           IamLoginSessionService loginSessionService,
                                                           MfaPendingService mfaPendingService,
                                                           IamUserSessionService userSessionService,
                                                           MfaService mfaService) {
        return new AdmissionChallengeController(
                challengeService, mock(LoginMethodCatalog.class), mock(RealmProvider.class),
                mock(IamThemeSelector.class), mock(AuthService.class), loginSessionService,
                mfaPendingService, mock(IdentityProviderStore.class), userSessionService, mfaService);
    }

    private static IamSessionPayload sessionPayload(Long userId) {
        return new IamSessionPayload(
                new IamUserPrincipal(userId, 1L, "default", "user-" + userId, "User",
                        0, List.of(), Map.of(), -1L, "PASSWORD"),
                System.currentTimeMillis(), 3600L, 1800L, System.currentTimeMillis(), false);
    }
}
