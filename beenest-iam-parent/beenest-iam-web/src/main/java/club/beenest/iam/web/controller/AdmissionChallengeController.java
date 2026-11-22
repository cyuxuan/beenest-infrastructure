package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.authentication.MfaRequiredException;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.mfa.MfaSessionPending;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.web.config.IamThemeSelector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 准入补验页控制器。
 * <p>挑战页展示的登录方式为"票据白名单 ∩ 域级可用方式"的交集,
 * 交集之外的方式不展示入口,避免用户尝试客户端不接受的登录方式。</p>
 */
@Controller
public class AdmissionChallengeController {

    private final AdmissionChallengeService challengeService;
    private final LoginMethodCatalog loginMethodCatalog;
    private final RealmProvider realmProvider;
    private final IamThemeSelector themeSelector;
    private final AuthService authService;
    private final IamLoginSessionService loginSessionService;
    private final MfaPendingService mfaPendingService;
    private final IdentityProviderStore identityProviderStore;
    /** 会话服务:会话级 MFA 补验只升级原会话,不新建会话 */
    private final IamUserSessionService userSessionService;
    /** 动态口令校验与口令登录的第二步走同一条实现,失败计数口径也一致 */
    private final MfaService mfaService;

    public AdmissionChallengeController(AdmissionChallengeService challengeService,
                                        LoginMethodCatalog loginMethodCatalog,
                                        RealmProvider realmProvider,
                                        IamThemeSelector themeSelector,
                                        AuthService authService,
                                        IamLoginSessionService loginSessionService,
                                        MfaPendingService mfaPendingService,
                                        IdentityProviderStore identityProviderStore,
                                        IamUserSessionService userSessionService,
                                        MfaService mfaService) {
        this.challengeService = challengeService;
        this.loginMethodCatalog = loginMethodCatalog;
        this.realmProvider = realmProvider;
        this.themeSelector = themeSelector;
        this.authService = authService;
        this.loginSessionService = loginSessionService;
        this.mfaPendingService = mfaPendingService;
        this.identityProviderStore = identityProviderStore;
        this.userSessionService = userSessionService;
        this.mfaService = mfaService;
    }

    /**
     * 渲染补验页。
     *
     * @param realm     身份域标识
     * @param token     挑战令牌
     * @param mfaToken  MFA 待验证票据(挑战页的 MFA 形态使用)
     * @param model     视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化补验页视图名
     * @throws club.beenest.iam.common.exception.BusinessException 挑战令牌无效或身份域不存在
     */
    @GetMapping("/login-challenge")
    public String challengePage(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                @RequestParam(value = "token", required = false) String token,
                                @RequestParam(value = "mfa", required = false) String mfaToken,
                                Model model,
                                CsrfToken csrfToken) {
        // GET 侧无会话上下文时也允许按令牌定位:跨站联邦回跳不携带 Cookie
        AdmissionChallenge challenge = challengeService.resolve(realm, token, null);
        if (challenge == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
        }
        Long realmId = realmProvider.findByRealmKey(realm)
                .map(realmModel -> realmModel.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realm));
        // 白名单 ∩ 域级可用方式
        Set<LoginMethod> available = loginMethodCatalog.availableInRealm(realmId, realm);
        Set<String> allowed = new LinkedHashSet<>();
        for (String code : challenge.allowedLoginTypes()) {
            LoginMethod method = LoginMethod.fromCode(code);
            if (method != null && available.contains(method)) {
                allowed.add(code);
            }
        }
        model.addAttribute("realmKey", realm);
        model.addAttribute("challengeToken", token);
        model.addAttribute("clientId", challenge.clientId() == null ? "" : challenge.clientId());
        model.addAttribute("allowedMethods", allowed);
        model.addAttribute("requireMfa", challenge.requireMfa());
        model.addAttribute("mfaToken", mfaToken == null ? "" : mfaToken);
        model.addAttribute("_csrf", csrfToken);
        // 联邦入口:白名单命中 WECHAT / OIDC / SAML 时,列出本域可用的提供方;
        // 同一类型可能有多个别名,逐个列出,不按类型合并
        List<ChallengeIdp> idps = new ArrayList<>();
        identityProviderStore.listByRealm(realmId).stream()
                .filter(IdentityProviderModel::enabled)
                .map(idp -> Map.entry(idp, LoginMethodCatalog.toLoginMethod(idp.providerId())))
                .filter(entry -> entry.getValue() != null && allowed.contains(entry.getValue().code()))
                .forEach(entry -> idps.add(new ChallengeIdp(entry.getKey().alias(),
                        entry.getKey().displayName(), entry.getValue().code())));
        model.addAttribute("idps", idps);
        model.addAttribute("smsAllowed", allowed.contains(LoginMethod.SMS.code()));
        return themeSelector.view("login/challenge");
    }

    /**
     * 提交补验。
     * <p>MFA 补验与口令补验共用这一个端点,按是否携带 {@code mfaToken} 分流;
     * 端点按表单提交(application/x-www-form-urlencoded)设计。</p>
     * <p>口令校验复用平台的 {@link AuthService},与口令登录同一实现,
     * 账号状态、失败计数、权限计算口径一致。</p>
     * <p>账号已绑定 TOTP 时 {@code IamAuthenticationProvider} 抛
     * {@link MfaRequiredException}:本次提交已完成第一因素,改走会话级 MFA 补验
     * (见 {@link MfaPendingService#issueForSession})。</p>
     *
     * @param realm    身份域标识
     * @param token    挑战令牌
     * @param username 口令补验的账号
     * @param password 口令补验的口令
     * @param mfaToken MFA 补验票据;为空表示走口令补验
     * @param code     动态口令或恢复码(MFA 补验用)
     * @param request  当前请求,读取会话标识
     * @param response 当前响应,升级会话时写入 Cookie
     * @return 重定向地址:成功回挑战目标,方式不命中或需要 MFA 回补验页,认证失败回登录页
     * @throws club.beenest.iam.common.exception.BusinessException 令牌过期、票据与会话不匹配、口令错误等
     */
    @PostMapping("/login-challenge")
    public String submitChallenge(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                  @RequestParam(value = "token", required = false) String token,
                                  @RequestParam(value = "username", required = false) String username,
                                  @RequestParam(value = "password", required = false) String password,
                                  @RequestParam(value = "mfaToken", required = false) String mfaToken,
                                  @RequestParam(value = "code", required = false) String code,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        // 1. 定位票据,带上网关会话标识:票据与会话绑定,
        //    泄露的令牌无法把凭据验证结果升级到另一个会话上
        String sid = IamSessionCookie.read(request);
        AdmissionChallenge challenge = challengeService.resolve(realm, token, sid);
        if (challenge == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
        }
        // 2. 二步验证补验:票据 + 动态口令
        if (mfaToken != null && !mfaToken.isBlank()) {
            MfaSessionPending pending = mfaPendingService.consumeSession(realm, mfaToken);
            if (pending == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
            }
            // MFA 票据只能升级它签发时的会话:两张票据 sid 不一致即拒绝
            if (pending.sid() == null || !pending.sid().equals(challenge.sid())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求与会话不匹配,请重新发起登录");
            }
            // 校验 MFA 票据的 userId 与会话主体一致,防止用自己的动态口令升级他人会话
            IamSessionPayload payload = userSessionService.loadSession(pending.sid());
            if (payload == null || payload.principal() == null
                    || !Objects.equals(payload.principal().getUserId(), pending.userId())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求与会话不匹配,请重新发起登录");
            }
            if (!mfaService.verifyLoginCode(realm, pending.userId(), code)) {
                throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
            }
            // 只置位二步验证态:loginType 传 null 保留原第一因素方式,存续时长不变
            if (!userSessionService.upgradeAuthentication(pending.sid(), null, Boolean.TRUE)) {
                // 升级失败不消费挑战票据,失败可重试
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "会话已失效,请重新发起登录");
            }
            // 该分支不走 establish,挑战票据在此消费
            challengeService.consume(realm, challenge);
            return "redirect:" + challenge.returnTo();
        }
        // 3. 第一因素补验:缺字段说明不是这个表单提交的
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写账号与口令");
        }
        try {
            // 4. 复用统一认证服务:账号状态、失败计数、权限计算与口令登录完全一致
            IamUserPrincipal principal = authService.authenticate(username, password);
            String loginType = principal.getLoginType();
            // 5. 方式不命中时不调 establish(它会退回新建会话):
            //    保留票据,用户换一种方式再试
            if (!challenge.allows(loginType)) {
                return "redirect:/login-challenge?realm=" + realm + "&token=" + token;
            }
            // 6. establish 升级会话时已消费票据,成功路径不再重复消费
            loginSessionService.establish(request, response, principal, loginType, token);
            return "redirect:" + challenge.returnTo();
        } catch (MfaRequiredException e) {
            // 账号绑定了 TOTP:签会话级票据,回挑战页的 MFA 形态
            String newMfaToken = mfaPendingService.issueForSession(
                    realm, e.getUserId(), sid, e.getLoginType());
            return "redirect:/login-challenge?realm=" + realm + "&token=" + token + "&mfa=" + newMfaToken;
        } catch (AuthenticationException e) {
            // 账密错误、账号锁定、失败次数触发的验证码要求统一交回登录页处理:
            // 登录页按失败计数展示验证码框并给出错误提示;
            // 登录成功后按 sid 找回本票据升级会话,流程不中断
            String returnTo = UriUtils.encodeQueryParam(challenge.returnTo(), StandardCharsets.UTF_8);
            StringBuilder fallback = new StringBuilder("/login?realm=").append(realm)
                    .append("&return_to=").append(returnTo);
            // 带上目标客户端:登录页按同一份准入白名单继续收窄
            if (challenge.clientId() != null && !challenge.clientId().isBlank()) {
                fallback.append("&client_id=")
                        .append(UriUtils.encodeQueryParam(challenge.clientId(), StandardCharsets.UTF_8));
            }
            return "redirect:" + fallback;
        }
    }

    /**
     * 挑战页上的联邦入口。
     *
     * @param alias       提供方别名
     * @param displayName 展示名
     * @param method      对应的登录方式编码
     */
    public record ChallengeIdp(String alias, String displayName, String method) {
    }
}
