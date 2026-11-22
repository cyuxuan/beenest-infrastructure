package club.beenest.iam.authz.rest;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.rest.dto.CurrentUserResponse;
import club.beenest.iam.authz.rest.dto.WebAuthnAssertionRequest;
import club.beenest.iam.authz.rest.dto.WebAuthnLoginOptionsRequest;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.authz.webauthn.WebAuthnCredentialService;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Passkey 登录接口。
 * <p>两步式:先取挑战与请求选项,浏览器调用 {@code navigator.credentials.get()},
 * 再把断言提交回来换取登录态。挑战一次性消费,断言不可重放。</p>
 * <p>断言校验失败与账号状态异常对外统一为验证失败,真实原因只进登录事件。</p>
 */
@RestController
@RequestMapping("/api/v1/auth/webauthn")
public class PasskeyController {

    private final WebAuthnCredentialService credentialService;
    private final AuthService authService;
    private final IamLoginSessionService loginSessionService;
    private final LoginEventPublisher loginEventPublisher;

    public PasskeyController(WebAuthnCredentialService credentialService,
                             AuthService authService,
                             IamLoginSessionService loginSessionService,
                             LoginEventPublisher loginEventPublisher) {
        this.credentialService = credentialService;
        this.authService = authService;
        this.loginSessionService = loginSessionService;
        this.loginEventPublisher = loginEventPublisher;
    }

    /**
     * 取登录挑战与请求选项。
     *
     * @param request 账号;为空表示无用户名登录
     * @return 浏览器请求选项(challenge / rpId / allowCredentials 等)
     * @throws BusinessException Passkey 未启用
     */
    @PostMapping("/options")
    public Result<Map<String, Object>> options(@Valid @RequestBody WebAuthnLoginOptionsRequest request) {
        RealmContext context = RealmContextHolder.get();
        Long realmId = context == null ? null : context.realm().id();
        return Result.success(credentialService.beginAuthentication(
                currentRealmKey(), realmId, request.account()));
    }

    /**
     * 校验断言并建立登录态。
     *
     * @param request      浏览器返回的断言
     * @param httpRequest  用于取来源信息
     * @param httpResponse 用于回写登录态
     * @return 当前用户与权限码
     * @throws BusinessException Passkey 未启用、挑战过期或校验失败
     */
    @PostMapping("/authenticate")
    public Result<CurrentUserResponse> authenticate(@Valid @RequestBody WebAuthnAssertionRequest request,
                                                    HttpServletRequest httpRequest,
                                                    HttpServletResponse httpResponse) {
        String realmKey = currentRealmKey();
        String ip = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader("User-Agent");
        WebAuthnCredentialService.AssertionResult result;
        try {
            result = credentialService.finishAuthentication(realmKey, request);
        } catch (BusinessException e) {
            loginEventPublisher.publish(realmKey, null, null,
                    LoginEventEvent.LOGIN_FAILURE, LoginMethod.WEBAUTHN.code(), ip, userAgent,
                    "Passkey 校验失败: " + e.getMessage());
            throw e;
        }
        IamUserPrincipal principal;
        try {
            principal = authService.completeMfaLogin(realmKey, result.userId(), LoginMethod.WEBAUTHN.code());
        } catch (BusinessException | DisabledException e) {
            // 断言通过但账号不可用(禁用/锁定/已删除):对外仍按验证失败处理
            loginEventPublisher.publish(realmKey, result.userId(), null,
                    LoginEventEvent.LOGIN_FAILURE, LoginMethod.WEBAUTHN.code(), ip, userAgent,
                    "账号状态异常: " + e.getMessage());
            throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
        }
        loginSessionService.establish(httpRequest, httpResponse, principal,
                LoginMethod.WEBAUTHN.code());
        return Result.success(CurrentUserResponse.from(principal));
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
