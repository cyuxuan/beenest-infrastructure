package club.beenest.iam.authz.config;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.authentication.step.AccountLockStep;
import club.beenest.iam.authz.authentication.step.AccountStatusStep;
import club.beenest.iam.authz.authentication.step.AuthenticationStep;
import club.beenest.iam.authz.authentication.step.AuthenticationStepChain;
import club.beenest.iam.authz.authentication.step.CaptchaStep;
import club.beenest.iam.authz.authentication.step.CredentialStep;
import club.beenest.iam.authz.authentication.step.FailureResetStep;
import club.beenest.iam.authz.authentication.step.LoginFailureRecorder;
import club.beenest.iam.authz.authentication.step.MfaGateStep;
import club.beenest.iam.authz.authentication.step.RealmResolutionStep;
import club.beenest.iam.authz.ldap.LdapUserAuthenticator;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.security.CaptchaService;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * 认证步骤链装配。
 * <p>内置七个步骤覆盖登录主干:域解析 → 失败锁定 → 验证码门禁 → 凭据校验 →
 * 账号状态 → 失败计数清理 → 二步验证门禁。</p>
 * <p>扩展方式:在任意被扫描的配置类中声明 {@link AuthenticationStep} 类型的 bean,
 * 即被自动纳入链;顺序由 {@code stage()} 与 {@code order()} 决定,无需改动本类或 Provider。
 * 新增第一因素用 {@code Stage.FIRST_FACTOR},前置检查用 {@code Stage.PRE_CHECK}。</p>
 */
@Configuration
public class AuthenticationStepConfiguration {

    /**
     * 登录失败审计记录器,各步骤共用。
     *
     * @param loginEventPublisher 登录事件发布器
     * @return 失败记录器
     */
    @Bean
    public LoginFailureRecorder loginFailureRecorder(LoginEventPublisher loginEventPublisher) {
        return new LoginFailureRecorder(loginEventPublisher);
    }

    /**
     * 身份域解析步骤:确定认证所属域并加载登录策略。
     *
     * @param realmProvider         身份域读取
     * @param securityPolicyManager 登录策略读取
     * @param failureRecorder       失败记录器
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep realmResolutionStep(RealmProvider realmProvider,
                                                  IamSecurityPolicyManager securityPolicyManager,
                                                  LoginFailureRecorder failureRecorder) {
        return new RealmResolutionStep(realmProvider, securityPolicyManager, failureRecorder);
    }

    /**
     * 失败锁定检查步骤:账号维度与来源 IP 维度。
     *
     * @param loginFailureCounter 失败计数器
     * @param failureRecorder     失败记录器
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep accountLockStep(LoginFailureCounter loginFailureCounter,
                                              LoginFailureRecorder failureRecorder) {
        return new AccountLockStep(loginFailureCounter, failureRecorder);
    }

    /**
     * 验证码门禁步骤:达到阈值后才要求。
     *
     * @param captchaService      验证码服务
     * @param loginFailureCounter 失败计数器
     * @param failureRecorder     失败记录器
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep captchaStep(CaptchaService captchaService,
                                          LoginFailureCounter loginFailureCounter,
                                          LoginFailureRecorder failureRecorder) {
        return new CaptchaStep(captchaService, loginFailureCounter, failureRecorder);
    }

    /**
     * 凭据校验步骤:本地口令优先,目录 bind 兜底。
     *
     * @param userProvider         用户读取与口令校验
     * @param ldapUserAuthenticator 目录兜底认证
     * @param passwordEncoder      口令编码器
     * @param loginFailureCounter  失败计数器
     * @param failureRecorder      失败记录器
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep credentialStep(UserProvider userProvider,
                                             LdapUserAuthenticator ldapUserAuthenticator,
                                             PasswordEncoder passwordEncoder,
                                             LoginFailureCounter loginFailureCounter,
                                             LoginFailureRecorder failureRecorder) {
        return new CredentialStep(userProvider, ldapUserAuthenticator, passwordEncoder,
                loginFailureCounter, failureRecorder);
    }

    /**
     * 账号状态校验步骤:状态与服务账号限制。
     *
     * @param failureRecorder 失败记录器
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep accountStatusStep(LoginFailureRecorder failureRecorder) {
        return new AccountStatusStep(failureRecorder);
    }

    /**
     * 失败计数清理步骤:凭据与状态都通过后清除账号维度计数。
     *
     * @param loginFailureCounter 失败计数器
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep failureResetStep(LoginFailureCounter loginFailureCounter) {
        return new FailureResetStep(loginFailureCounter);
    }

    /**
     * 第二因素门禁步骤:判定是否需要二步验证。
     *
     * @param mfaService 二步验证服务
     * @return 步骤实例
     */
    @Bean
    public AuthenticationStep mfaGateStep(MfaService mfaService) {
        return new MfaGateStep(mfaService);
    }

    /**
     * 认证步骤链:收集容器内全部步骤并按阶段排序。
     *
     * @param steps 容器内全部 {@link AuthenticationStep} bean,含扩展包注册的步骤
     * @return 已排序的步骤链
     */
    @Bean
    public AuthenticationStepChain authenticationStepChain(List<AuthenticationStep> steps) {
        return new AuthenticationStepChain(steps);
    }
}
