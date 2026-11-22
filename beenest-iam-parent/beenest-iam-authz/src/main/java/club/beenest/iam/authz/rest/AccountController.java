package club.beenest.iam.authz.rest;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.requiredaction.RequiredActionService;
import club.beenest.iam.authz.rest.dto.PasswordResetConfirmRequest;
import club.beenest.iam.authz.rest.dto.PasswordResetRequest;
import club.beenest.iam.authz.rest.dto.RequiredActionResponse;
import club.beenest.iam.authz.security.EmailVerificationService;
import club.beenest.iam.authz.security.PasswordResetService;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.result.Result;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账户自助接口:找回口令、待办事项查询与邮箱验证。
 * <p>找回口令未登录可达;待办与邮箱验证要求登录,办理入口复用既有接口(改密、MFA 绑定)。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {

    /** 对外基址:优先取 iam.issuer-base-url,避免按 Host 头拼接出可被伪造的邮件链接 */
    private final IamIssuerBaseUrlResolver baseUrlResolver;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final RequiredActionService requiredActionService;

    public AccountController(IamIssuerBaseUrlResolver baseUrlResolver, PasswordResetService passwordResetService,
                             EmailVerificationService emailVerificationService,
                             RequiredActionService requiredActionService) {
        this.baseUrlResolver = baseUrlResolver;
        this.passwordResetService = passwordResetService;
        this.emailVerificationService = emailVerificationService;
        this.requiredActionService = requiredActionService;
    }

    /**
     * 申请找回口令。
     * <p>账号是否存在都返回同一结果,存在性不可通过该接口探测。</p>
     *
     * @param request     账号(邮箱或手机号)
     * @param httpRequest 用于取来源地址与对外基础地址
     * @return 统一成功结果
     */
    @PostMapping("/password-reset/request")
    public Result<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                             HttpServletRequest httpRequest) {
        passwordResetService.requestReset(currentRealmKey(), request.account(),
                httpRequest.getRemoteAddr(), baseUrlResolver.resolve(httpRequest));
        return Result.success();
    }

    /**
     * 凭票据设置新口令。
     *
     * @param request 重置票据与新口令
     * @return 统一成功结果
     */
    @PostMapping("/password-reset/confirm")
    public Result<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirmReset(currentRealmKey(), request.ticket(), request.newPassword());
        return Result.success();
    }

    /**
     * 当前账号未完成的待办事项。
     *
     * @return 待办代码与展示名列表
     */
    @GetMapping("/required-actions")
    public Result<List<RequiredActionResponse>> requiredActions() {
        IamUserPrincipal principal = CurrentUserHolder.require();
        List<RequiredActionResponse> actions = requiredActionService.pending(principal.getUserId()).stream()
                .map(action -> new RequiredActionResponse(action.name(), action.getDisplayName()))
                .toList();
        return Result.success(actions);
    }

    /**
     * 向当前账号邮箱发送验证链接。
     *
     * @param httpRequest 用于拼接邮件中的链接
     * @return 统一成功结果
     */
    @PostMapping("/required-actions/email-verification")
    public Result<Void> sendEmailVerification(HttpServletRequest httpRequest) {
        IamUserPrincipal principal = CurrentUserHolder.require();
        emailVerificationService.sendVerification(currentRealmKey(), principal.getUserId(),
                baseUrlResolver.resolve(httpRequest));
        return Result.success();
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
}
