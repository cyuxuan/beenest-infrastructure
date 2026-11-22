package club.beenest.iam.authz.web;

import club.beenest.iam.authz.authentication.MfaRequiredException;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.constant.IamWebConstants;
import club.beenest.iam.common.web.IamOneTimeMessageService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

/**
 * 表单登录失败处理器。
 * <p>需要多因子认证时跳转 MFA 验证页并携带一次性票据;其余失败带错误标记跳回登录页。
 * 失败计数与临时锁定由认证流程内的计数器维护。</p>
 */
public class IamAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final MfaPendingService mfaPendingService;
    private final IamOneTimeMessageService oneTimeMessageService;

    public IamAuthenticationFailureHandler(MfaPendingService mfaPendingService,
                                           IamOneTimeMessageService oneTimeMessageService) {
        super("/login?error=1");
        this.mfaPendingService = mfaPendingService;
        this.oneTimeMessageService = oneTimeMessageService;
    }

    /**
     * 按失败类型跳转 MFA 页或登录页。
     *
     * @param request   当前请求
     * @param response  当前响应
     * @param exception 认证异常
     * @throws IOException      跳转失败
     * @throws ServletException 跳转失败
     */
    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof MfaRequiredException mfaRequired) {
            // 1. 签发一次性票据,页面凭票据 + 动态口令完成第二步
            String realmKey = currentRealmKey();
            String token = mfaPendingService.issue(realmKey, mfaRequired.getUserId(), mfaRequired.getLoginType());
            // 2. 强制绑定场景(域策略 force_mfa 且未绑定)携带 enroll 标记
            String target = mfaRequired.isEnrollmentRequired()
                    ? "/login-mfa?realm=" + realmKey + "&mfaToken=" + token + "&enroll=1"
                    : "/login-mfa?realm=" + realmKey + "&mfaToken=" + token;
            getRedirectStrategy().sendRedirect(request, response, target);
            return;
        }
        // 3. 普通失败:错误消息经一次性票据传递,票据存共享 Redis;
        //    同时带回登录名与错误标记,页面据此回填账号并按需展示验证码
        String ticket = oneTimeMessageService.issue(IamWebConstants.LOGIN_ERROR_NAMESPACE,
                exception.getMessage());
        String username = request.getParameter("username");
        // client_id 可能来自表单隐藏域或查询参数:getParameter 两者都覆盖
        String clientId = request.getParameter("client_id");
        String realmKey = currentRealmKey();
        StringBuilder target = new StringBuilder("/login?error=1&realm=").append(realmKey);
        if (ticket != null) {
            target.append("&").append(IamWebConstants.LOGIN_ERROR_TICKET_PARAM).append("=")
                    .append(java.net.URLEncoder.encode(ticket, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (username != null && !username.isBlank()) {
            target.append("&username=").append(java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8));
        }
        // 失败后仍按目标客户端的白名单收窄:丢掉 client_id 会让不收口令的应用重新露出表单
        if (clientId != null && !clientId.isBlank()) {
            target.append("&client_id=")
                    .append(java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8));
        }
        getRedirectStrategy().sendRedirect(request, response, target.toString());
    }

    /**
     * 当前请求的身份域标识。
     *
     * @return 身份域标识;无上下文时为 default
     */
    private static String currentRealmKey() {
        RealmContext context = RealmContextHolder.get();
        return context == null ? "default" : context.realmKey();
    }
}
