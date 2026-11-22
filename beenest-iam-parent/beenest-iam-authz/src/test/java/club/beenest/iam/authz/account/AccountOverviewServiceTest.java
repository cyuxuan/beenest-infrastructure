package club.beenest.iam.authz.account;

import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.authorization.RedisOAuth2AuthorizationConsentService;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.webauthn.WebAuthnCredentialService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RequiredAction;
import club.beenest.iam.core.model.UserSessionModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户中心数据装配测试。
 * <p>重点钉住三件事:跨域客户端不进列表、跨域撤销按不存在处理、当前设备不能从设备列表里注销。
 * 这三条都是"看起来能跑但会越权或把自己踢下线"的地方。</p>
 */
class AccountOverviewServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long REALM_ID = 1L;
    private static final String USERNAME = "alice";
    private static final String CURRENT_SID = "sid-current";

    private IamUserManager userManager;
    private UserProvider userProvider;
    private MfaService mfaService;
    private WebAuthnCredentialService webAuthnCredentialService;
    private RedisOAuth2AuthorizationConsentService consentService;
    private ApplicationAccessRevocationService revocationService;
    private ClientProvider clientProvider;
    private IamUserSessionService userSessionService;
    private IamLogoutService logoutService;
    private RequiredActionService requiredActionService;
    private MessageSource messageSource;

    private AccountOverviewService service;

    @BeforeEach
    void setUp() {
        userManager = mock(IamUserManager.class);
        userProvider = mock(UserProvider.class);
        mfaService = mock(MfaService.class);
        webAuthnCredentialService = mock(WebAuthnCredentialService.class);
        consentService = mock(RedisOAuth2AuthorizationConsentService.class);
        revocationService = mock(ApplicationAccessRevocationService.class);
        revocationService = mock(ApplicationAccessRevocationService.class);
        clientProvider = mock(ClientProvider.class);
        userSessionService = mock(IamUserSessionService.class);
        logoutService = mock(IamLogoutService.class);
        requiredActionService = mock(RequiredActionService.class);
        messageSource = mock(MessageSource.class);

        // scope 文案缺失时回落 scope 标识本身
        when(messageSource.getMessage(anyString(), isNull(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        service = new AccountOverviewService(userManager, userProvider, mfaService,
                webAuthnCredentialService, consentService, revocationService, clientProvider,
                userSessionService, logoutService, requiredActionService, messageSource);
        login();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("装配资料、凭证、授权、设备与待办")
    void overviewAssemblesAllSections() {
        stubUser();
        when(mfaService.status(USER_ID)).thenReturn(new MfaService.MfaStatus(true, 8));
        when(webAuthnCredentialService.isAvailable()).thenReturn(true);
        when(webAuthnCredentialService.list(REALM_ID, USER_ID)).thenReturn(List.of());
        when(consentService.findByPrincipal(USERNAME))
                .thenReturn(Map.of("3", Set.of("openid", "profile")));
        when(consentService.grantedAt("3", USERNAME)).thenReturn(1_700_000_000_000L);
        when(clientProvider.findById(3L)).thenReturn(Optional.of(client(3L, REALM_ID, "demo", "示例应用")));
        when(userSessionService.listSessions(REALM_ID, USER_ID))
                .thenReturn(List.of(session(CURRENT_SID, USER_ID), session("sid-other", USER_ID)));
        when(requiredActionService.pending(USER_ID)).thenReturn(Set.of(RequiredAction.CONFIGURE_TOTP));

        AccountOverview overview = service.overview(CURRENT_SID);

        assertEquals(USERNAME, overview.profile().username());
        assertTrue(overview.profile().emailVerified());
        assertTrue(overview.credentials().passwordSet());
        assertEquals(8, overview.credentials().recoveryCodesRemaining());

        // scope 按标识排序,展示名回落为标识本身
        assertEquals(List.of("openid", "profile"),
                overview.consents().get(0).scopes().stream()
                        .map(AccountOverview.ScopeItem::code).toList());
        assertEquals("示例应用", overview.consents().get(0).clientName());
        // 授予时间由 epoch 毫秒按系统时区还原,只校验非空,避免用例绑定时区
        assertNotNull(overview.consents().get(0).grantedTime());

        assertTrue(overview.sessions().get(0).current());
        assertFalse(overview.sessions().get(1).current());
        assertEquals(List.of("CONFIGURE_TOTP"), overview.pendingActions());
    }

    @Test
    @DisplayName("跨域或已删除的客户端不出现在授权列表")
    void consentsSkipForeignClients() {
        stubUser();
        when(consentService.findByPrincipal(USERNAME))
                .thenReturn(Map.of("3", Set.of("openid"), "4", Set.of("email")));
        when(clientProvider.findById(3L)).thenReturn(Optional.of(client(3L, REALM_ID, "demo", "示例应用")));
        // 客户端属于另一个域
        when(clientProvider.findById(4L)).thenReturn(Optional.of(client(4L, 99L, "other", "外部应用")));

        AccountOverview overview = service.overview(CURRENT_SID);

        assertEquals(1, overview.consents().size());
        assertEquals("demo", overview.consents().get(0).clientKey());
    }

    @Test
    @DisplayName("客户端不属于当前域时撤销按不存在处理")
    void revokeConsentRejectsForeignClient() {
        when(clientProvider.findByClientKey(REALM_ID, "other"))
                .thenReturn(Optional.of(client(4L, 99L, "other", "外部应用")));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.revokeConsent("other"));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getCode());
        verify(consentService, never()).revoke(anyString(), anyString());
    }

    @Test
    @DisplayName("撤销成功时按客户端主键与登录名删除同意记录")
    void revokeConsentUsesRegisteredClientId() {
        when(clientProvider.findByClientKey(REALM_ID, "demo"))
                .thenReturn(Optional.of(client(3L, REALM_ID, "demo", "示例应用")));
        when(consentService.revoke("3", USERNAME)).thenReturn(true);

        service.revokeConsent("demo");

        verify(consentService).revoke("3", USERNAME);
    }

    @Test
    @DisplayName("当前设备不允许从设备列表注销")
    void revokeSessionRejectsCurrentDevice() {
        when(userSessionService.findBySid(CURRENT_SID)).thenReturn(session(CURRENT_SID, USER_ID));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.revokeSession(CURRENT_SID, CURRENT_SID));

        assertEquals(ErrorCode.STATE_CONFLICT.getCode(), error.getCode());
        verify(userSessionService, never()).endSession(anyString());
    }

    @Test
    @DisplayName("他人设备按不存在处理")
    void revokeSessionRejectsForeignSession() {
        when(userSessionService.findBySid("sid-other")).thenReturn(session("sid-other", 99L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.revokeSession("sid-other", CURRENT_SID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getCode());
        verify(userSessionService, never()).endSession(anyString());
    }

    @Test
    @DisplayName("注销其它设备走统一登出入口")
    void revokeSessionEndsOtherDevice() {
        when(userSessionService.findBySid("sid-other")).thenReturn(session("sid-other", USER_ID));

        service.revokeSession("sid-other", CURRENT_SID);

        verify(logoutService).logout("sid-other");
    }

    @Test
    @DisplayName("用户记录不存在时抛出未找到")
    void overviewFailsWhenUserMissing() {
        when(userManager.getById(USER_ID)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.overview(null));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getCode());
    }

    @Test
    @DisplayName("未登录时拒绝装配")
    void overviewRequiresLogin() {
        SecurityContextHolder.clearContext();

        BusinessException error = assertThrows(BusinessException.class, () -> service.overview(null));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), error.getCode());
    }

    /**
     * 把当前登录主体放进安全上下文。
     */
    private void login() {
        IamUserPrincipal principal = new IamUserPrincipal(USER_ID, REALM_ID, "default", USERNAME,
                "爱丽丝", 0, List.of(), Map.of(), -1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /**
     * 打桩用户、口令与 MFA 等默认返回值。
     */
    private void stubUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setRealmId(REALM_ID);
        user.setUsername(USERNAME);
        user.setNickname("爱丽丝");
        user.setEmail("alice@example.com");
        user.setEmailVerified(true);
        user.setPhone("13800000000");
        user.setPhoneVerified(true);
        user.setUserType("INTERNAL");
        when(userManager.getById(USER_ID)).thenReturn(user);
        when(userProvider.hasLocalPassword(USER_ID)).thenReturn(true);
        when(mfaService.status(USER_ID)).thenReturn(new MfaService.MfaStatus(false, 0));
        when(webAuthnCredentialService.isAvailable()).thenReturn(false);
        when(webAuthnCredentialService.list(anyLong(), anyLong())).thenReturn(List.of());
        when(consentService.findByPrincipal(anyString())).thenReturn(Map.of());
        when(userSessionService.listSessions(anyLong(), anyLong())).thenReturn(List.of());
        when(requiredActionService.pending(anyLong())).thenReturn(Set.of());
    }

    /**
     * 构造客户端模型。
     *
     * @param id      主键
     * @param realmId 所属域
     * @param key     客户端标识
     * @param name    展示名
     * @return 客户端模型
     */
    private static ClientModel client(Long id, Long realmId, String key, String name) {
        return new ClientModel(id, realmId, key, name, ClientModel.ClientType.PUBLIC, null,
                Set.of(), Set.of(), null, false, Set.of(), true, true, true, null,
                null, null, Set.of("authorization_code"), Set.of(), false, null);
    }

    /**
     * 构造会话记录。
     *
     * @param sid    会话标识
     * @param userId 用户主键
     * @return 会话记录
     */
    private static UserSessionModel session(String sid, Long userId) {
        LocalDateTime now = LocalDateTime.of(2026, 10, 27, 15, 0);
        return new UserSessionModel(1L, REALM_ID, sid, userId, "PASSWORD", "127.0.0.1",
                "Mozilla/5.0", UserSessionModel.STATE_ACTIVE, now, now, now.plusHours(1));
    }
}
