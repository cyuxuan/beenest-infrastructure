package club.beenest.iam.authz.rest;

import club.beenest.iam.authz.admission.ClientAdmissionPolicy;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.authentication.MfaRequiredException;
import club.beenest.iam.authz.authentication.step.CaptchaStep;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.grant.ProviderCodeService;
import club.beenest.iam.authz.mfa.MfaPending;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.authz.rest.dto.CurrentUserResponse;
import club.beenest.iam.authz.rest.dto.IdentityProviderEntry;
import club.beenest.iam.authz.rest.dto.LoginRequest;
import club.beenest.iam.authz.rest.dto.MfaChallengeResponse;
import club.beenest.iam.authz.rest.dto.MfaLoginRequest;
import club.beenest.iam.authz.rest.dto.MfaLoginResponse;
import club.beenest.iam.authz.rest.dto.ProviderCodeRequest;
import club.beenest.iam.authz.rest.dto.ProviderCodeResponse;
import club.beenest.iam.authz.rest.dto.SmsCodeRequest;
import club.beenest.iam.authz.rest.dto.SmsLoginRequest;
import club.beenest.iam.authz.security.CaptchaService;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.sms.SmsCodeService;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.federation.registry.IdentityProviderRegistry;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证接口。
 * <p>面向前后端分离的调用方提供会话式登录、注销与当前用户查询;登录成功后在同一容器会话上建立登录态,
 * 后续请求凭 Cookie 恢复。</p>
 * <p>MFA:口令通过后若账号已绑定 TOTP 或域策略强制 MFA,登录返回一次性票据,
 * 客户端凭票据与动态口令调用 {@code /login-mfa} 完成第二步。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final IamLogoutService logoutService;
    private final MfaPendingService mfaPendingService;
    private final MfaService mfaService;
    private final CaptchaService captchaService;
    private final IamSecurityPolicyManager securityPolicyManager;
    private final LoginFailureCounter loginFailureCounter;
    private final RealmProvider realmProvider;
    private final UserProvider userProvider;
    private final LoginEventPublisher loginEventPublisher;
    private final IamLoginSessionService loginSessionService;
    private final IdentityProviderStore identityProviderStore;
    private final IdentityProviderRegistry identityProviderRegistry;
    private final ProviderCodeService providerCodeService;
    private final SmsCodeService smsCodeService;
    /** 提供方入口按客户端白名单收窄:策略随客户端对象下发,不额外查库 */
    private final RegisteredClientRepository registeredClientRepository;

    /** 登录页探测接口的限流:每 IP 每分钟最多 30 次 */
    private static final int CAPTCHA_PROBE_LIMIT = 30;
    /** 验证码签发限流:每 IP 每分钟最多 60 次,挡住批量刷图与识别后继续撞库 */
    private static final int CAPTCHA_GENERATE_LIMIT = 60;
    /** 登录页探测接口的限流窗口 */
    private static final java.time.Duration CAPTCHA_PROBE_WINDOW = java.time.Duration.ofMinutes(1);

    public AuthController(AuthService authService, IamLogoutService logoutService,
                          SecurityContextRepository securityContextRepository,
                          MfaPendingService mfaPendingService, MfaService mfaService,
                          CaptchaService captchaService, IamSecurityPolicyManager securityPolicyManager,
                          LoginFailureCounter loginFailureCounter, RealmProvider realmProvider,
                          UserProvider userProvider, LoginEventPublisher loginEventPublisher,
                          IamLoginSessionService loginSessionService,
                          IdentityProviderStore identityProviderStore,
                          IdentityProviderRegistry identityProviderRegistry,
                          ProviderCodeService providerCodeService,
                          SmsCodeService smsCodeService,
                          RegisteredClientRepository registeredClientRepository) {
        this.authService = authService;
        this.logoutService = logoutService;
        this.mfaPendingService = mfaPendingService;
        this.mfaService = mfaService;
        this.captchaService = captchaService;
        this.securityPolicyManager = securityPolicyManager;
        this.loginFailureCounter = loginFailureCounter;
        this.realmProvider = realmProvider;
        this.userProvider = userProvider;
        this.loginEventPublisher = loginEventPublisher;
        this.loginSessionService = loginSessionService;
        this.identityProviderStore = identityProviderStore;
        this.identityProviderRegistry = identityProviderRegistry;
        this.providerCodeService = providerCodeService;
        this.smsCodeService = smsCodeService;
        this.registeredClientRepository = registeredClientRepository;
    }

    /**
     * 账号密码登录。
     * <p>失败次数达到域策略阈值后必须携带验证码;需要 MFA 时返回票据而非登录态。</p>
     *
     * @param request      账号与口令(可带验证码)
     * @param httpRequest  用于取会话与来源信息
     * @param httpResponse 用于回写登录态
     * @return 当前用户信息与权限码;或 MFA 质询(票据)
     * @throws BusinessException 验证码错误或账号凭据不匹配
     */
    @PostMapping("/login")
    public Result<CurrentUserResponse> login(@Valid @RequestBody LoginRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        // 1. 验证码门禁:失败次数达到域策略阈值时强制校验
        String realmKey = currentRealmKey();
        LoginPolicy loginPolicy = currentLoginPolicy(realmKey);
        if (loginFailureCounter.captchaRequired(realmKey, request.username(), loginPolicy)) {
            try {
                captchaService.verifyOrThrow(request.captchaId(), request.captchaCode());
            } catch (BusinessException e) {
                // 验证码前置失败同样是一次登录失败,留痕后按原语义抛出
                loginEventPublisher.publish(realmKey, null, request.username(),
                        LoginEventEvent.LOGIN_FAILURE, "PASSWORD",
                        httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                        e.getMessage());
                throw e;
            }
            // 已前置校验,置标记避免认证 Provider 内重复校验
            httpRequest.setAttribute(CaptchaStep.CAPTCHA_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        }
        // 2. 账号密码认证:与表单登录共用认证管理器,失败计数/状态检查一致
        IamUserPrincipal principal;
        try {
            principal = authService.authenticate(request.username(), request.password());
        } catch (MfaRequiredException e) {
            // 3. 需要第二因子:签发一次性票据,等待动态口令
            String mfaToken = mfaPendingService.issue(realmKey, e.getUserId(), e.getLoginType());
            return Result.success(CurrentUserResponse.mfaChallenge(
                    new MfaChallengeResponse(mfaToken, e.isEnrollmentRequired(), realmKey)));
        }
        // 4. 建立登录态并返回
        return Result.success(establishSession(httpRequest, httpResponse, principal,
                loginTypeOrPassword(principal.getLoginType()), request.challengeToken()));
    }

    /**
     * MFA 第二步登录:票据 + 动态口令(或恢复码)。
     *
     * @param request      一次性票据与动态口令
     * @param httpRequest  用于取来源信息
     * @param httpResponse 用于回写登录态
     * @return 登录用户、权限集与回跳地址
     * @throws BusinessException 票据无效或已过期、动态口令错误
     */
    @PostMapping("/login-mfa")
    public Result<MfaLoginResponse> loginMfa(@Valid @RequestBody MfaLoginRequest request,
                                             HttpServletRequest httpRequest,
                                             HttpServletResponse httpResponse) {
        String realmKey = currentRealmKey();
        // 1. 消费一次性票据,防止绕过口令直接提交动态口令
        MfaPending pending = mfaPendingService.consumeTicket(realmKey, request.mfaToken());
        if (pending == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录票据无效或已过期,请重新登录");
        }
        Long userId = pending.userId();
        // 票据没有携带方式(存量票据)时,审计标签与会话行按口令登录记
        String loginType = loginTypeOrPassword(pending.loginType());
        // 2. 校验动态口令/恢复码(含失败计数与防重放)
        if (!mfaService.verifyLoginCode(realmKey, userId, request.code())) {
            // 二步验证失败:口令已过但登录未完成,按 MFA 失败单独归类
            String username = userProvider.findById(userId).map(UserModel::username).orElse(null);
            loginEventPublisher.publish(realmKey, userId, username,
                    LoginEventEvent.MFA_FAILURE, loginType,
                    httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"),
                    "动态口令或恢复码错误");
            throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
        }
        // 3. 构建主体并建立登录态
        //    主体是准入判定的输入,必须用票据原值(可为 null);
        //    把未知方式回落成 PASSWORD 会让受限客户端误以为这是一次口令登录而放行
        IamUserPrincipal principal = authService.completeMfaLogin(realmKey, userId, pending.loginType());
        // 登录期 MFA 不是准入挑战:补验票据按会话 Cookie 定位,这里不传令牌
        CurrentUserResponse user = establishSession(httpRequest, httpResponse, principal, loginType, null);
        // 4. 回跳地址:登录前保存的原始请求(如表单登录触发的授权端点),消费后移除
        return Result.success(new MfaLoginResponse(user, consumeSavedRequest(httpRequest, httpResponse)));
    }

    /**
     * 发送短信验证码。
     * <p>手机号未注册或未验证时同样返回成功且不发送,响应不可区分,避免批量探测号码注册状态。</p>
     *
     * @param request     手机号
     * @param httpRequest 用于取来源地址做限流
     * @return 统一成功结果
     */
    @PostMapping("/sms-code")
    public Result<Void> smsCode(@Valid @RequestBody SmsCodeRequest request,
                                HttpServletRequest httpRequest) {
        smsCodeService.sendCode(currentRealmKey(), request.phone(), httpRequest.getRemoteAddr());
        return Result.success();
    }

    /**
     * 短信验证码登录:手机号 + 验证码,独立第一因素。
     * <p>验证码错误、手机号未注册、账号被禁用或锁定,对外统一为验证码错误,真实原因只进登录事件。</p>
     *
     * @param request      手机号、验证码与准入挑战令牌
     * @param httpRequest  用于取来源信息
     * @param httpResponse 用于回写登录态
     * @return 当前用户信息与权限码
     * @throws BusinessException 域未开启短信登录、验证码错误或账号不可用
     */
    @PostMapping("/login-sms")
    public Result<CurrentUserResponse> loginSms(@Valid @RequestBody SmsLoginRequest request,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        String realmKey = currentRealmKey();
        String ip = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader("User-Agent");
        // 1. 域开关:未开启短信登录的域,验证码链路整体不可用
        LoginPolicy loginPolicy = currentLoginPolicy(realmKey);
        if (loginPolicy == null || !Boolean.TRUE.equals(loginPolicy.getSmsLoginEnabled())) {
            throw new BusinessException(ErrorCode.SMS_CHANNEL_NOT_CONFIGURED);
        }
        // 2. 校验验证码(一次性消费;失败即登录失败,留痕)
        if (!smsCodeService.verifyCode(realmKey, request.phone(), request.code())) {
            publishSmsFailure(realmKey, null, request.phone(), ip, userAgent, "验证码错误或已过期");
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }
        // 3. 定位账号:只有已验证手机号能通过校验,查不到同样是"验证码问题"
        RealmContext context = RealmContextHolder.get();
        Long realmId = context == null ? null : context.realm().id();
        UserModel user = realmId == null ? null
                : userProvider.findByPhone(realmId, request.phone()).orElse(null);
        if (user == null) {
            publishSmsFailure(realmKey, null, request.phone(), ip, userAgent, "手机号未注册或未验证");
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }
        // 4. 构建主体:账号在验证码有效期内被禁用/锁定,同样转统一文案
        IamUserPrincipal principal;
        try {
            principal = authService.completeMfaLogin(realmKey, user.id(), LoginMethod.SMS.code());
        } catch (BusinessException | DisabledException e) {
            publishSmsFailure(realmKey, user.id(), request.phone(), ip, userAgent,
                    "账号状态异常: " + e.getMessage());
            throw new BusinessException(ErrorCode.SMS_CODE_INVALID);
        }
        // 5. 建立登录态(登录方式 SMS,与密码登录区分留痕)
        return Result.success(establishSession(httpRequest, httpResponse, principal, "SMS",
                request.challengeToken()));
    }

    /**
     * 短信登录失败留痕,登录名位记手机号。
     *
     * @param realmKey  身份域标识
     * @param userId    用户主键;无法定位账号时为 null
     * @param phone     手机号
     * @param ip        来源地址
     * @param userAgent 客户端标识
     * @param reason    失败原因
     */
    private void publishSmsFailure(String realmKey, Long userId, String phone,
                                   String ip, String userAgent, String reason) {
        loginEventPublisher.publish(realmKey, userId, phone,
                LoginEventEvent.LOGIN_FAILURE, "SMS", ip, userAgent, reason);
    }

    /**
     * 凭据类登录:把第三方凭据换成一个授权码。
     * <p>令牌由标准授权码流程签发,签发、刷新、吊销走协议栈。</p>
     *
     * @param request     提供方别名、第三方凭据与客户端凭据
     * @param httpRequest 用于推导签发方与来源留痕
     * @return 授权码与回调地址
     * @throws BusinessException 客户端凭据无效、客户端未获授权码流程或第三方凭据校验失败
     */
    @PostMapping("/provider-code")
    public Result<ProviderCodeResponse> providerCode(@Valid @RequestBody ProviderCodeRequest request,
                                                     HttpServletRequest httpRequest) {
        ProviderCodeService.IssuedCode issued = providerCodeService.issueCode(
                request.provider(), request.credentials(), request.clientId(), request.clientSecret(),
                request.codeChallenge(), httpRequest);
        return Result.success(new ProviderCodeResponse(issued.code(), issued.redirectUri()));
    }

    /**
     * 生成图形验证码。
     * <p>签发不要求登录,故按来源 IP 限流:否则攻击者可以无限取图、识别后继续撞库,
     * 也会让未取用的验证码持续占用 Redis 内存。</p>
     *
     * @param httpRequest 用于取来源 IP
     * @return 验证码标识与图片
     */
    @GetMapping("/captcha")
    public Result<CaptchaService.CaptchaImage> captcha(HttpServletRequest httpRequest) {
        captchaService.checkRateLimit("captcha-generate", httpRequest.getRemoteAddr(),
                CAPTCHA_GENERATE_LIMIT, CAPTCHA_PROBE_WINDOW);
        return Result.success(captchaService.generate());
    }

    /**
     * 列出当前身份域可用的三方登录入口。
     * <p>仅返回已接入且启用的提供方;带 {@code client_id} 时再按该客户端的准入白名单收窄,
     * 登录页不展示客户端不接受的方式。</p>
     *
     * @param clientId 客户端标识;为空时不按客户端收窄
     * @return 登录入口列表
     */
    @GetMapping("/idps")
    public Result<List<IdentityProviderEntry>> identityProviders(
            @RequestParam(value = "client_id", required = false) String clientId) {
        RealmContext context = RealmContextHolder.get();
        Long realmId = context == null ? null : context.realm().id();
        if (realmId == null) {
            return Result.success(List.of());
        }
        ClientAdmissionPolicy policy = clientAdmissionPolicy(clientId);
        List<IdentityProviderEntry> entries = identityProviderStore.listByRealm(realmId).stream()
                .filter(model -> model.enabled())
                .map(model -> new IdentityProviderEntry(
                        model.alias(), model.displayName(), model.providerId(),
                        "/broker/" + context.realmKey() + "/" + model.alias() + "/login",
                        identityProviderRegistry.supportsRedirect(model.providerId())))
                .filter(entry -> admits(policy, entry.providerId()))
                .toList();
        return Result.success(entries);
    }

    /**
     * 目标客户端的准入策略。
     *
     * @param clientId 客户端标识
     * @return 准入策略;未传 client_id 或客户端不存在时返回 null,表示不限制
     */
    private ClientAdmissionPolicy clientAdmissionPolicy(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        RegisteredClient registered = registeredClientRepository.findByClientId(clientId);
        return registered == null ? null : IamRegisteredClientRepository.policyOf(registered);
    }

    /**
     * 提供方类型映射出的登录方式是否在客户端白名单内。
     * <p>空白名单不限制;denyAll 一律拒绝;映射不出登录方式的提供方仅在客户端不限定时保留。</p>
     *
     * @param policy     客户端准入策略
     * @param providerId 提供方类型
     * @return true 表示允许展示该入口
     */
    private static boolean admits(ClientAdmissionPolicy policy, String providerId) {
        if (policy == null) {
            return true;
        }
        if (policy.denyAll()) {
            return false;
        }
        if (policy.allowedMethods().isEmpty()) {
            return true;
        }
        LoginMethod method = LoginMethodCatalog.toLoginMethod(providerId);
        return method != null && policy.allowedMethods().contains(method);
    }

    /**
     * 查询某账号当前登录是否已要求验证码。
     * <p>登录页在账号输入后查询,失败次数达到域策略阈值时显示验证码输入框。</p>
     *
     * @param username    登录名
     * @param httpRequest 用于取来源地址做限流
     * @return 是否要求验证码
     * @throws BusinessException 探测次数超出限流阈值
     */
    @GetMapping("/captcha-required")
    public Result<Boolean> captchaRequired(@RequestParam("username") String username,
                                            HttpServletRequest httpRequest) {
        // 限流:该接口未登录即可调用,不限流就等于开放了账号状态探测
        captchaService.checkRateLimit("captcha-required", httpRequest.getRemoteAddr(),
                CAPTCHA_PROBE_LIMIT, CAPTCHA_PROBE_WINDOW);
        String realmKey = currentRealmKey();
        LoginPolicy loginPolicy = currentLoginPolicy(realmKey);
        return Result.success(loginFailureCounter.captchaRequired(realmKey, username, loginPolicy));
    }

    /**
     * 注销当前会话。
     *
     * @param httpRequest  用于读取会话标识与来源信息
     * @param httpResponse 用于清除会话 Cookie
     * @return 统一成功结果
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 1. 结束会话记录并清掉会话 Cookie
        String sid = IamSessionCookie.read(httpRequest);
        if (sid != null) {
            if (CurrentUserHolder.optional() instanceof IamUserPrincipal principal) {
                // 主动注销留痕:与被动过期区分,是"用户自知"的退出
                loginEventPublisher.publish(principal.getRealmKey(), principal.getUserId(),
                        principal.getUsername(), LoginEventEvent.LOGOUT, "PASSWORD",
                        httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"), null);
            }
            logoutService.logout(sid);
        }
        IamSessionCookie.clear(httpResponse);
        // 2. 清理线程内的登录态
        SecurityContextHolder.clearContext();
        return Result.success();
    }

    /**
     * 全端下线:递增令牌版本使全部已签发令牌失效,并结束该用户所有会话。
     *
     * @param httpRequest  用于读取会话标识
     * @param httpResponse 用于清除会话 Cookie
     * @return 被结束的会话数量
     */
    @PostMapping("/logout-all")
    public Result<Integer> logoutAll(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // 1. 取当前登录主体(本接口在业务链上,调用时必然已认证)
        IamUserPrincipal principal = CurrentUserHolder.require();
        // 2. 令牌版本递增 + 全部会话置为已注销 + 权限缓存失效
        int ended = authService.logoutAll(principal.getRealmId(), principal.getRealmKey(), principal.getUserId());
        // 3. 当前会话已包含在"全部会话"里一并结束,这里只需清掉 Cookie
        IamSessionCookie.clear(httpResponse);
        SecurityContextHolder.clearContext();
        return Result.success(ended);
    }

    /**
     * 当前登录用户与权限集,前端启动时调用,用于渲染菜单与按钮。
     *
     * @return 用户信息与权限码
     */
    @GetMapping("/me")
    public Result<CurrentUserResponse> me() {
        return Result.success(CurrentUserResponse.from(CurrentUserHolder.require()));
    }

    // ===== 内部实现 =====

    /**
     * 建立会话登录态。
     * <p>防固定、会话登记、登录事件与安全上下文由 {@link IamLoginSessionService} 统一处理,
     * 口令登录、MFA 二步、短信登录与三方登录回调共用同一份实现。</p>
     *
     * @param httpRequest    当前请求
     * @param httpResponse   当前响应
     * @param principal      认证通过的主体
     * @param loginType      登录方式(密码登录 PASSWORD / 短信登录 SMS 等),进登录事件
     * @param challengeToken 准入挑战令牌;登录期 MFA 传 null,补验票据按会话 Cookie 定位
     * @return 当前用户信息与权限码
     */
    private CurrentUserResponse establishSession(HttpServletRequest httpRequest,
                                                 HttpServletResponse httpResponse,
                                                 IamUserPrincipal principal, String loginType,
                                                 String challengeToken) {
        loginSessionService.establish(httpRequest, httpResponse, principal, loginType, challengeToken);
        return CurrentUserResponse.from(principal);
    }

    /**
     * 登录方式缺失时按口令登录记。
     * <p>存量 MFA 票据与升级前的主体可能没有这个值,留空会让管理端显示成空标签。</p>
     *
     * @param loginType 登录方式
     * @return 非空的登录方式
     */
    private static String loginTypeOrPassword(String loginType) {
        return loginType == null || loginType.isBlank() ? LoginMethod.PASSWORD.code() : loginType;
    }

    /**
     * 读取并消费登录前保存的原始请求。
     * <p>表单登录被拦截时由 Spring Security 写入会话;MFA 走 REST 完成认证,需在此手动消费。</p>
     *
     * @param httpRequest  当前请求
     * @param httpResponse 当前响应
     * @return 回跳地址;无保存的请求时回落 /
     */
    private static String consumeSavedRequest(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        SavedRequest saved = cache.getRequest(httpRequest, httpResponse);
        cache.removeRequest(httpRequest, httpResponse);
        return saved == null ? "/" : saved.getRedirectUrl();
    }

    /**
     * 当前请求的身份域标识。
     *
     * @return 域标识;无上下文时回落 default
     */
    private static String currentRealmKey() {
        RealmContext context = RealmContextHolder.get();
        return context == null ? "default" : context.realmKey();
    }

    /**
     * 当前域的登录策略(带缓存)。
     *
     * @param realmKey 身份域标识
     * @return 登录策略;域不存在时返回 null
     */
    private LoginPolicy currentLoginPolicy(String realmKey) {
        RealmContext context = RealmContextHolder.get();
        Long realmId = context == null ? null : context.realm().id();
        if (realmId == null) {
            // 域上下文缺失(如未携带 realm 参数的探测请求):回落默认域策略
            return realmProvider.findByRealmKey(realmKey == null ? "default" : realmKey)
                    .map(realm -> securityPolicyManager.getLoginPolicy(realm.id(), realm.realmKey()))
                    .orElse(null);
        }
        return securityPolicyManager.getLoginPolicy(realmId, realmKey);
    }
}
