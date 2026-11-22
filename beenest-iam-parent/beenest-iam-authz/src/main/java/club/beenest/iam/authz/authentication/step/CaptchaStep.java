package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.authentication.LoginFailureCounter;
import club.beenest.iam.authz.security.CaptchaService;
import club.beenest.iam.common.i18n.IamMessages;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 验证码门禁步骤。
 * <p>失败次数达到域策略阈值后才要求验证码,不是每次都要求。
 * REST 登录在控制器内已前置校验并置请求属性标记,本步骤见到标记即跳过,避免重复校验;
 * 表单登录的 captchaId/captchaCode 由登录页表单携带,从请求参数读取。</p>
 */
public class CaptchaStep implements AuthenticationStep {

    /**
     * 请求属性名:REST 登录控制器完成验证码前置校验后置位,避免本步骤重复校验。
     */
    public static final String CAPTCHA_VERIFIED_ATTRIBUTE = CaptchaStep.class.getName() + ".CAPTCHA_VERIFIED";

    private final CaptchaService captchaService;
    private final LoginFailureCounter loginFailureCounter;
    private final LoginFailureRecorder failureRecorder;

    public CaptchaStep(CaptchaService captchaService, LoginFailureCounter loginFailureCounter,
                       LoginFailureRecorder failureRecorder) {
        this.captchaService = captchaService;
        this.loginFailureCounter = loginFailureCounter;
        this.failureRecorder = failureRecorder;
    }

    @Override
    public Stage stage() {
        return Stage.PRE_CHECK;
    }

    @Override
    public String name() {
        return "captcha";
    }

    @Override
    public int order() {
        return 30;
    }

    /**
     * 在达到阈值时校验验证码。
     * <p>验证码错误同时计入账号与来源 IP 两个维度的失败次数:它既可能是单账号被撞,
     * 也可能是同一来源在批量尝试。</p>
     *
     * @param context 认证上下文,需已填充身份域与登录策略
     * @throws AuthenticationException 验证码缺失或错误时抛出
     */
    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        HttpServletRequest request = context.getRequest();
        if (alreadyVerified(request)) {
            return;
        }
        RealmModel realm = context.getRealm();
        String username = context.getUsername();
        if (!loginFailureCounter.captchaRequired(realm.realmKey(), username, context.getLoginPolicy())) {
            return;
        }
        String captchaId = request == null ? null : request.getParameter("captchaId");
        String captchaCode = request == null ? null : request.getParameter("captchaCode");
        if (captchaService.verify(captchaId, captchaCode)) {
            return;
        }
        loginFailureCounter.onFailure(realm.realmKey(), username, context.getLoginPolicy());
        loginFailureCounter.onIpFailure(realm.realmKey(), context.getClientIp(), context.getLoginPolicy());
        failureRecorder.record(context, null, "验证码错误");
        throw new BadCredentialsException(IamMessages.message(ErrorCode.CAPTCHA_INVALID));
    }

    /**
     * REST 登录的验证码是否已在控制器内前置校验。
     *
     * @param request 当前请求,可为 null
     * @return 请求属性已置为 true 时返回 true;无请求上下文或未置位时返回 false
     */
    private static boolean alreadyVerified(HttpServletRequest request) {
        return request != null && Boolean.TRUE.equals(request.getAttribute(CAPTCHA_VERIFIED_ATTRIBUTE));
    }
}
