package club.beenest.iam.authz;

import club.beenest.iam.authz.authentication.IamAuthenticationProvider;
import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.authentication.step.AccountLockStep;
import club.beenest.iam.authz.authentication.step.AccountStatusStep;
import club.beenest.iam.authz.authentication.step.AuthenticationStepChain;
import club.beenest.iam.authz.authentication.step.CaptchaStep;
import club.beenest.iam.authz.authentication.step.CredentialStep;
import club.beenest.iam.authz.authentication.step.FailureResetStep;
import club.beenest.iam.authz.authentication.step.LoginFailureRecorder;
import club.beenest.iam.authz.authentication.step.MfaGateStep;
import club.beenest.iam.authz.authentication.step.RealmResolutionStep;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.ldap.LdapUserAuthenticator;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.security.CaptchaService;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证链路的账号枚举防护。
 * <p>登录接口是唯一完全暴露在公网的入口:只要"账号不存在""口令错误""账号已禁用"
 * 三种情形的响应有任何可区分之处(文案或耗时),攻击者就能据此枚举有效账号,
 * 进而把撞库范围缩小到真实存在的账号上。这里把该不变量固化成测试。</p>
 */
class IamAuthenticationProviderTest {

    private static final String USERNAME = "alice";
    private static final String PASSWORD = "Secret@123";

    private UserProvider userProvider;
    private RealmProvider realmProvider;
    private PermissionProvider permissionProvider;
    private LoginFailureCounter loginFailureCounter;
    private IamSecurityPolicyManager securityPolicyManager;
    private MfaService mfaService;
    private CaptchaService captchaService;
    private LoginEventPublisher loginEventPublisher;
    private PasswordEncoder passwordEncoder;
    private LdapUserAuthenticator ldapUserAuthenticator;
    private IamAuthenticationProvider provider;
    private IamPrincipalFactory principalFactory;

    @BeforeEach
    void setUp() {
        userProvider = mock(UserProvider.class);
        realmProvider = mock(RealmProvider.class);
        permissionProvider = mock(PermissionProvider.class);
        loginFailureCounter = mock(LoginFailureCounter.class);
        securityPolicyManager = mock(IamSecurityPolicyManager.class);
        mfaService = mock(MfaService.class);
        captchaService = mock(CaptchaService.class);
        loginEventPublisher = mock(LoginEventPublisher.class);
        passwordEncoder = mock(PasswordEncoder.class);
        ldapUserAuthenticator = mock(LdapUserAuthenticator.class);

        RealmModel realm = new RealmModel(1L, "default", "默认域", true, true,
                300L, 3600L, 1800L, 28800L, SubjectMode.LOGIN_NAME, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
        when(permissionProvider.computePermissionCodes(anyLong(), anyLong())).thenReturn(Set.of());

        // 主体工厂与生产环境同构:主体里的权限即权限提供方的求值结果
        principalFactory = new IamPrincipalFactory(permissionProvider);
        provider = buildProvider(passwordEncoder);
    }

    /**
     * 按生产环境的步骤顺序装配 Provider。
     * <p>步骤与 {@code AuthenticationStepConfiguration} 保持一致:测试覆盖的是真实链路,
     * 不是绕过链路的简化实现,否则重构步骤链时测试会静默失去覆盖。</p>
     *
     * @param encoder 口令编码器;个别用例替换为生产同款编码器
     * @return 装配完成的 Provider
     */
    private IamAuthenticationProvider buildProvider(PasswordEncoder encoder) {
        LoginFailureRecorder failureRecorder = new LoginFailureRecorder(loginEventPublisher);
        AuthenticationStepChain chain = new AuthenticationStepChain(List.of(
                new RealmResolutionStep(realmProvider, securityPolicyManager, failureRecorder),
                new AccountLockStep(loginFailureCounter, failureRecorder),
                new CaptchaStep(captchaService, loginFailureCounter, failureRecorder),
                new CredentialStep(userProvider, ldapUserAuthenticator, encoder, loginFailureCounter, failureRecorder),
                new AccountStatusStep(failureRecorder),
                new FailureResetStep(loginFailureCounter),
                new MfaGateStep(mfaService)));
        return new IamAuthenticationProvider(chain, principalFactory);
    }

    @Test
    @DisplayName("账号不存在与口令错误返回完全相同的文案,且不存在时也执行等强度哈希抹平耗时差")
    void unknownAccountIsIndistinguishableFromWrongPassword() {
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.empty());

        BadCredentialsException unknown = assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(token()));
        // 陪跑:不执行哈希会让"账号不存在"明显更快,形成时序侧信道
        verify(passwordEncoder).matches(eq(PASSWORD), anyString());

        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.of(user(1)));
        when(userProvider.verifyPassword(1L, PASSWORD)).thenReturn(false);

        BadCredentialsException wrongPassword = assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(token()));

        assertEquals(unknown.getMessage(), wrongPassword.getMessage());
    }

    @Test
    @DisplayName("账号不存在时不计入失败计数,避免随机用户名灌满 Redis 计数键")
    void unknownAccountDoesNotConsumeFailureBudget() {
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> provider.authenticate(token()));

        verify(loginFailureCounter, never()).onFailure(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("账号状态只在口令校验通过后才暴露,未持正确口令者无法用状态枚举账号")
    void accountStatusIsRevealedOnlyAfterPasswordVerified() {
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.of(user(0)));
        // 口令错误:即便账号已禁用,也只能拿到通用的凭证错误
        when(userProvider.verifyPassword(1L, PASSWORD)).thenReturn(false);
        assertEquals("账号或密码错误",
                assertThrows(BadCredentialsException.class, () -> provider.authenticate(token())).getMessage());

        // 口令正确:此时才允许告知账号被禁用
        when(userProvider.verifyPassword(1L, PASSWORD)).thenReturn(true);
        assertThrows(DisabledException.class, () -> provider.authenticate(token()));
    }

    @Test
    @DisplayName("账号不存在时用真实编码器也不会抛异常:裸哈希缺前缀会变成 500,等于给枚举账号开门")
    void unknownAccountStaysBadCredentialsWithRealEncoder() {
        // 用生产同款编码器:委派编码器按前缀分派算法,没有前缀的裸哈希会直接抛异常。
        // 那种异常一路冒到全局处理器就是 500——"账号不存在"与"口令错误"于是给出
        // 两种状态码,时序侧信道没堵上,反倒多了一条更省事的枚举通道。
        PasswordEncoder realEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        IamAuthenticationProvider realProvider = buildProvider(realEncoder);
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.empty());

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> realProvider.authenticate(token()));
        assertEquals("账号或密码错误", ex.getMessage(),
                "账号不存在必须与口令错误给出同一个错误,不能是系统内部错误");
    }

    @Test
    @DisplayName("LDAP 兜底认证通过:JIT 用户完成登录,且不重复本地口令校验(JIT 账号无口令凭证)")
    void ldapFallbackUserLogsInWithoutLocalPasswordCheck() {
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.empty());
        UserModel jitUser = new UserModel(7L, 1L, USERNAME, "目录用户", null, null, 1,
                "EXTERNAL", false, 0, Map.of());
        when(ldapUserAuthenticator.authenticate(any(), eq(USERNAME), eq(PASSWORD)))
                .thenReturn(Optional.of(jitUser));

        Authentication authentication = provider.authenticate(token());

        assertTrue(authentication.isAuthenticated());
        // JIT 账号没有 PASSWORD 凭证行:重复走本地口令校验必然失败,
        // 故目录已校验过口令的用户必须跳过本地校验
        verify(userProvider, never()).verifyPassword(eq(7L), anyString());
        // 失败计数必须清除:LDAP 口令正确不等于风控意义上的成功登录前的失败
        verify(loginFailureCounter).onSuccess("default", USERNAME);
    }

    @Test
    @DisplayName("JIT 账号二次登录:本地已存在但无口令凭证,仍走 LDAP 认证(冒烟回归:曾因此二次登录恒失败)")
    void jitAccountSecondLoginStillGoesToLdap() {
        // 冒烟发现的缺陷:JIT 账号第二次登录时本地查得到用户,旧逻辑只在
        // "本地不存在"时走 LDAP,而 JIT 账号没有 PASSWORD 凭证,本地校验必然失败,
        // 导致目录用户首次登录成功后永远无法再登录。
        UserModel jitUser = new UserModel(7L, 1L, USERNAME, "目录用户", null, null, 1,
                "EXTERNAL", false, 0, Map.of());
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.of(jitUser));
        when(userProvider.hasLocalPassword(7L)).thenReturn(false);
        when(ldapUserAuthenticator.authenticate(any(), eq(USERNAME), eq(PASSWORD)))
                .thenReturn(Optional.of(jitUser));

        Authentication authentication = provider.authenticate(token());

        assertTrue(authentication.isAuthenticated());
        verify(userProvider, never()).verifyPassword(eq(7L), anyString());
        verify(ldapUserAuthenticator).authenticate(any(), eq(USERNAME), eq(PASSWORD));
    }

    @Test
    @DisplayName("本地有口令凭证的账号不碰 LDAP:本地优先原则,口令错误也不会转交目录")
    void localAccountWithPasswordNeverTouchesLdap() {
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.of(user(1)));
        when(userProvider.hasLocalPassword(1L)).thenReturn(true);
        when(userProvider.verifyPassword(1L, PASSWORD)).thenReturn(false);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(token()));

        assertEquals("账号或密码错误", ex.getMessage());
        verify(ldapUserAuthenticator, never()).authenticate(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("LDAP 兜底失败(目录不可达/口令错误)对外与账号不存在完全一致")
    void ldapFallbackFailureIsIndistinguishable() {
        when(userProvider.findByUsername(1L, USERNAME)).thenReturn(Optional.empty());
        // 域未启用/目录不可达/口令错误:authenticator 一律返回 empty
        when(ldapUserAuthenticator.authenticate(any(), eq(USERNAME), eq(PASSWORD)))
                .thenReturn(Optional.empty());

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> provider.authenticate(token()));

        assertEquals("账号或密码错误", ex.getMessage());
        // 与"账号不存在"同样只计 IP 维度失败,不灌账号维度计数
        verify(loginFailureCounter, never()).onFailure(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        verify(loginFailureCounter).onIpFailure(eq("default"), any(), org.mockito.ArgumentMatchers.any());
    }

    private static UsernamePasswordAuthenticationToken token() {
        return UsernamePasswordAuthenticationToken.unauthenticated(USERNAME, PASSWORD);
    }

    private static UserModel user(int status) {
        return new UserModel(1L, 1L, USERNAME, "爱丽丝", null, null, status,
                "INTERNAL", false, 0, Map.of());
    }
}
