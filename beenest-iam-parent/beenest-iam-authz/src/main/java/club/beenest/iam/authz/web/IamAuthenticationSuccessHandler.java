package club.beenest.iam.authz.web;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamLoginSessionService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

import java.io.IOException;

/**
 * 表单登录成功处理器。
 * <p>会话登记与登录事件由 {@link IamLoginSessionService} 完成,表单登录、REST 登录与三方登录回调共用同一套建立逻辑。</p>
 */
public class IamAuthenticationSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private final IamLoginSessionService loginSessionService;

    /** 登录后默认落点:应用门户 */
    private static final String DEFAULT_TARGET = "/portal";
    /** 回跳地址参数名(登录页把它带在表单里) */
    private static final String RETURN_TO_PARAMETER = "return_to";

    public IamAuthenticationSuccessHandler(IamLoginSessionService loginSessionService) {
        this.loginSessionService = loginSessionService;
        setDefaultTargetUrl(DEFAULT_TARGET);
    }

    /**
     * 决定登录后的跳转地址。
     * <p>优先使用登录页带来的 {@code return_to},仅接受站内相对路径。</p>
     *
     * @param request  当前请求
     * @param response 当前响应
     * @return 跳转地址
     */
    @Override
    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response) {
        String returnTo = request.getParameter(RETURN_TO_PARAMETER);
        if (isSafeReturnTo(returnTo)) {
            return returnTo;
        }
        return super.determineTargetUrl(request, response);
    }

    /**
     * 是否为安全的站内相对路径。
     *
     * @param returnTo 回跳地址
     * @return 以单个斜杠开头且不含协议相对与反斜杠形式时为 true
     */
    private static boolean isSafeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        // 必须以单个斜杠开头:拒绝协议相对地址("//evil.com")与绝对地址
        if (!returnTo.startsWith("/") || returnTo.startsWith("//")) {
            return false;
        }
        // 反斜杠在部分浏览器下会被当作路径分隔符,等价于 "//"
        return !returnTo.startsWith("/\\");
    }

    /**
     * 建立登录态后继续默认跳转。
     *
     * @param request        当前请求
     * @param response       当前响应
     * @param authentication 认证结果
     * @throws IOException      跳转失败
     * @throws ServletException 跳转失败
     */
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        // 1. 建立登录态:签发会话标识、登记会话记录、记登录事件
        if (authentication.getPrincipal() instanceof IamUserPrincipal principal) {
            // 方式取自主体:LDAP 兜底登录的主体记录为 LDAP
            String loginType = principal.getLoginType() == null || principal.getLoginType().isBlank()
                    ? LoginMethod.PASSWORD.code() : principal.getLoginType();
            // 挑战令牌随登录表单回传:跨站联邦回跳时浏览器不带会话 Cookie,只能靠令牌定位挑战
            loginSessionService.establish(request, response, principal, loginType,
                    request.getParameter("challengeToken"));
        }
        // 2. 继续默认行为:回跳保存的请求或默认页
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
