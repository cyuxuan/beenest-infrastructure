package club.beenest.iam.web.controller;

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import club.beenest.iam.authz.admission.ClientAdmissionPolicy;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.webauthn.WebAuthnProperties;
import club.beenest.iam.common.constant.IamWebConstants;
import club.beenest.iam.common.web.IamOneTimeMessageService;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import club.beenest.iam.web.config.IamThemeSelector;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 登录页控制器。
 * <p>统一登录页挂在全局路径 {@code /login},通过 {@code realm} 参数携带身份域;
 * 表单提交到本路径由 Spring Security 的表单登录过滤器处理(UsernamePasswordAuthenticationFilter),
 * CSRF token 由 Thymeleaf 自动嵌入。</p>
 * <p>登录失败由失败处理器重定向回本页:错误消息经一次性票据传递(票据存共享 Redis,
 * URL 只携带不可推导的令牌,不泄露内部状态,也不受"登录提交与页面渲染落到不同实例"
 * 影响),登录名回显;验证码框由前端按失败计数按需展示。</p>
 * <p>准入过滤:请求带 {@code client_id} 时按该客户端白名单收窄页面展示,
 * 白名单外的登录方式不出入口,避免用户输入凭据后才被拒绝。</p>
 */
@Controller
public class LoginPageController {

    private final IamOneTimeMessageService oneTimeMessageService;
    private final IamThemeSelector themeSelector;
    private final RealmProvider realmProvider;
    private final IamSecurityPolicyManager securityPolicyManager;
    private final LoginMethodCatalog loginMethodCatalog;
    private final RegisteredClientRepository registeredClientRepository;
    private final WebAuthnProperties webAuthnProperties;

    public LoginPageController(IamOneTimeMessageService oneTimeMessageService, IamThemeSelector themeSelector,
                               RealmProvider realmProvider,
                               IamSecurityPolicyManager securityPolicyManager,
                               LoginMethodCatalog loginMethodCatalog,
                               RegisteredClientRepository registeredClientRepository,
                               WebAuthnProperties webAuthnProperties) {
        this.oneTimeMessageService = oneTimeMessageService;
        this.themeSelector = themeSelector;
        this.realmProvider = realmProvider;
        this.securityPolicyManager = securityPolicyManager;
        this.loginMethodCatalog = loginMethodCatalog;
        this.registeredClientRepository = registeredClientRepository;
        this.webAuthnProperties = webAuthnProperties;
    }

    /**
     * 渲染登录页。
     *
     * @param realm    身份域 key(缺省 default)
     * @param returnTo 登录成功后回跳地址(SAS SavedRequest 恢复用)
     * @param clientId 发起登录的客户端(为空表示门户直达,不按客户端收窄展示)
     * @param error    是否登录失败(展示错误提示)
     * @param username 上次登录失败时使用的登录名(回显用)
     * @param errToken 失败消息的一次性票据
     * @param model    视图模型
     * @param csrfToken CSRF 令牌
     * @return 主题化登录页视图名
     */
    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "realm", defaultValue = "default") String realm,
                            @RequestParam(value = "return_to", required = false) String returnTo,
                            @RequestParam(value = "client_id", required = false) String clientId,
                            @RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "username", required = false) String username,
                            @RequestParam(value = IamWebConstants.LOGIN_ERROR_TICKET_PARAM,
                                    required = false) String errToken,
                            Model model,
                            CsrfToken csrfToken) {
        model.addAttribute("realmKey", realm);
        model.addAttribute("returnTo", returnTo == null ? "" : returnTo);
        model.addAttribute("loginFailed", error != null);
        model.addAttribute("lastUsername", username == null ? "" : username);
        // 一次性错误消息:取走即失效,刷新页面不再显示
        String message = oneTimeMessageService.consume(IamWebConstants.LOGIN_ERROR_NAMESPACE, errToken);
        model.addAttribute("errorMessage", message == null ? "" : message);
        model.addAttribute("_csrf", csrfToken);
        // 客户端准入:只展示目标客户端接受、且本域真能执行的方式
        ClientAdmissionPolicy policy = resolveClientPolicy(clientId);
        boolean restricted = isRestricting(policy);
        Set<String> allowed = restricted
                ? intersectWithRealm(realm, policy.allowedMethodCodes())
                : Set.of();
        model.addAttribute("clientId", clientId == null ? "" : clientId);
        model.addAttribute("allowedMethods", allowed);
        // 口令与 LDAP 共用同一张"账号 + 口令"表单,任一放行即展示
        model.addAttribute("passwordLoginVisible",
                !restricted || allowed.contains(LoginMethod.PASSWORD.code())
                        || allowed.contains(LoginMethod.LDAP.code()));
        // 短信 Tab:域策略开启且服务端配好短信通道时展示;客户端受限时还须白名单放行
        model.addAttribute("smsLoginEnabled",
                isSmsLoginEnabled(realm) && (!restricted || allowed.contains(LoginMethod.SMS.code())));
        // Passkey 入口:依赖方配置完整才展示,客户端受限时同样按白名单收窄
        model.addAttribute("passkeyAvailable",
                webAuthnProperties.isAvailable()
                        && (!restricted || allowed.contains(LoginMethod.WEBAUTHN.code())));
        // 视图名按当前域主题拼接:域主题不存在时选择器已回落全局主题
        return themeSelector.view("login/login");
    }

    /**
     * 判断该策略是否构成本次展示的限制。
     * <p>空白名单表示该客户端未配置限制,沿用域级可用方式;其余情况一律按限制处理,
     * 包括 denyAll(配置损坏)与"白名单和域级可用方式交集为空"两种情形。
     * 交集为空同样按限制处理,避免一份本域执行不了的配置把全部入口放开。</p>
     *
     * @param policy 目标客户端的准入策略,可为 null
     * @return 需要按白名单收窄展示时返回 true
     */
    private static boolean isRestricting(ClientAdmissionPolicy policy) {
        return policy != null && (policy.denyAll() || !policy.allowedMethods().isEmpty());
    }

    /**
     * 客户端白名单与域级可用方式的交集。
     * <p>域不存在时返回空集:无法确认本域能执行哪些方式,按全部不可用处理。</p>
     *
     * @param realmKey    身份域标识
     * @param clientAllowed 客户端白名单中的方式编码
     * @return 交集后的方式编码集合
     */
    private Set<String> intersectWithRealm(String realmKey, Set<String> clientAllowed) {
        Long realmId = realmProvider.findByRealmKey(realmKey).map(RealmModel::id).orElse(null);
        if (realmId == null) {
            return Set.of();
        }
        Set<String> available = new LinkedHashSet<>();
        loginMethodCatalog.availableInRealm(realmId, realmKey)
                .forEach(method -> available.add(method.code()));
        Set<String> result = new LinkedHashSet<>();
        for (String code : clientAllowed) {
            if (available.contains(code)) {
                result.add(code);
            }
        }
        return result;
    }

    /**
     * 解析目标客户端的准入策略。
     * <p>无 {@code client_id} 或客户端不存在时返回 null(不限制):门户直达没有目标
     * 客户端,口令表单作为保底入口展示。</p>
     *
     * @param clientId 目标客户端标识,可为空
     * @return 准入策略;无目标客户端时为 null
     */
    private ClientAdmissionPolicy resolveClientPolicy(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        RegisteredClient registered = registeredClientRepository.findByClientId(clientId);
        return registered == null ? null : IamRegisteredClientRepository.policyOf(registered);
    }

    /**
     * 当前域是否开启短信验证码登录。
     * <p>域不存在或策略缺失时按未开启处理。</p>
     *
     * @param realmKey 身份域标识
     * @return 域策略开启短信登录时返回 true
     */
    private boolean isSmsLoginEnabled(String realmKey) {
        return realmProvider.findByRealmKey(realmKey)
                .map(RealmModel::id)
                .map(realmId -> securityPolicyManager.getLoginPolicy(realmId, realmKey))
                .map(LoginPolicy::getSmsLoginEnabled)
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }
}
