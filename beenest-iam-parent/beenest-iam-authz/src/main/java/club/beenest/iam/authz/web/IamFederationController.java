package club.beenest.iam.authz.web;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.federation.IamFederationStateService;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamLoginSessionService;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import club.beenest.iam.common.web.IamRateLimiter;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.federation.broker.IdentityBrokerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.util.Map;

/**
 * 三方登录 broker 端点。
 * <p>发起端点把浏览器跳到外部 IdP,并把防伪 state 落 Redis;回调端点凭 state 换回外部身份,
 * 绑定或复用本地账号后建立本平台登录态。端点必须在放行名单内,走这条链路时用户在本平台尚无登录态。</p>
 */
@Controller
public class IamFederationController {

    /**
     * 认不出提供方类型时记入的登录方式。
     * <p>会话表的 login_type 非空,不能传 null;准入判定仍按联邦方式要求补验。</p>
     */
    private static final String FEDERATION_LOGIN_TYPE = "FEDERATION";

    /** state 与浏览器的绑定 cookie 名 */
    private static final String STATE_COOKIE = "IAM_FED_STATE";
    /** 绑定 cookie 存活秒数,与 state 的 5 分钟有效期一致 */
    private static final int STATE_COOKIE_MAX_AGE = 300;
    /** 发起端点单来源每分钟允许次数:每次发起都会落一条 state,不限制则成为放大写入点 */
    private static final int START_LIMIT_PER_MINUTE = 30;

    private final IdentityBrokerService brokerService;
    private final IamFederationStateService stateService;
    private final IamLoginSessionService loginSessionService;
    private final RealmProvider realmProvider;
    private final LoginEventPublisher loginEventPublisher;
    private final IdentityProviderStore identityProviderStore;
    /** 主体工厂:与口令登录共用同一套权限求值口径 */
    private final IamPrincipalFactory principalFactory;
    /** 准入挑战票据:联邦回跳前取回补验成功后的回跳地址 */
    private final AdmissionChallengeService challengeService;
    /** 对外基址:回调地址由配置决定,不取 Host 头 */
    private final IamIssuerBaseUrlResolver baseUrlResolver;
    /** 发起端点限流 */
    private final IamRateLimiter rateLimiter;

    public IamFederationController(IdentityBrokerService brokerService,
                                   IamFederationStateService stateService,
                                   IamLoginSessionService loginSessionService,
                                   RealmProvider realmProvider,
                                   LoginEventPublisher loginEventPublisher,
                                   IdentityProviderStore identityProviderStore,
                                   IamPrincipalFactory principalFactory,
                                   AdmissionChallengeService challengeService,
                                   IamIssuerBaseUrlResolver baseUrlResolver,
                                   IamRateLimiter rateLimiter) {
        this.brokerService = brokerService;
        this.stateService = stateService;
        this.loginSessionService = loginSessionService;
        this.realmProvider = realmProvider;
        this.loginEventPublisher = loginEventPublisher;
        this.identityProviderStore = identityProviderStore;
        this.principalFactory = principalFactory;
        this.challengeService = challengeService;
        this.baseUrlResolver = baseUrlResolver;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 发起三方登录:跳转到外部 IdP。
     * <p>挑战页发起的联邦登录会带上挑战令牌,编入 state 随请求过一遍外部 IdP,
     * 回跳时用于定位待处理的准入挑战。</p>
     *
     * @param realmKey       身份域标识
     * @param alias          提供方别名
     * @param target         登录成功后回跳的站内地址;为空时使用默认地址
     * @param challengeToken 准入挑战令牌;非挑战场景为空
     * @param request        当前请求
     * @param response       当前响应
     * @throws java.io.IOException 跳转失败
     */
    @GetMapping("/broker/{realmKey}/{alias}/login")
    public void login(@PathVariable String realmKey,
                      @PathVariable String alias,
                      @RequestParam(required = false) String target,
                      @RequestParam(required = false) String challengeToken,
                      HttpServletRequest request,
                      HttpServletResponse response) throws java.io.IOException {
        // 1. 限流后域必须存在且启用
        rateLimiter.check("broker-login", request.getRemoteAddr(), START_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
        requireRealm(realmKey);
        // 2. 回调地址:本 IAM 的 broker 回调端点(外部 IdP 需按此地址登记)
        String redirectUri = brokerCallbackUrl(request, realmKey, alias);
        // 3. 回跳目标编入 state;令牌不在此校验,未知令牌在回跳侧等价于没有挑战。
        //    令牌含分隔符会污染载荷结构,此类令牌不可能签发过,按无挑战处理
        String safeChallengeToken = (challengeToken == null || challengeToken.indexOf('|') >= 0)
                ? null : challengeToken;
        // 载荷:realmKey|alias|challengeToken|target(目标地址放末段,可含分隔符)
        String state = stateService.issue(new FederationStatePayload(
                realmKey, alias, safeChallengeToken, safeTarget(target)).encode());
        // 4. 把 state 同时写进浏览器 cookie:回调时比对,防止他人把自己的外部账号
        //    塞进受害者浏览器完成登录(登录 CSRF)
        bindStateCookie(request, response, state);
        // 5. 跳转到外部 IdP
        String authorizationUrl = brokerService.buildAuthorizationUrl(realmKey, alias, redirectUri, state);
        if (authorizationUrl == null || authorizationUrl.isBlank()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "身份提供方未配置授权地址: " + alias);
        }
        response.sendRedirect(authorizationUrl);
    }

    /**
     * 三方登录回调:换回外部身份并建立本平台登录态。
     *
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @param code     OAuth2 授权码;为空时按空凭据处理
     * @param state    一次性 state
     * @param request  当前请求
     * @param response 当前响应
     * @throws java.io.IOException 跳转失败
     */
    @GetMapping("/broker/{realmKey}/{alias}/callback")
    public void callback(@PathVariable String realmKey,
                         @PathVariable String alias,
                         @RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         HttpServletRequest request,
                         HttpServletResponse response) throws java.io.IOException {
        completeLogin(realmKey, alias, state, code, Map.of(), request, response);
    }

    /**
     * SAML 断言消费端点(ACS)。
     * <p>断言由 IdP 以表单 POST 提交,浏览器只负责转交,与 OAuth2 回调同路径不同方法。
     * 防伪由 RelayState(本平台签发的 state)承担;不支持 IdP 发起的单点登录。</p>
     *
     * @param realmKey     身份域标识
     * @param alias        提供方别名
     * @param samlResponse SAML 断言;为空时按空凭据处理
     * @param RelayState   SAML 侧的 state 携带位
     * @param state        部分 IdP 同时回传的 state 参数
     * @param request      当前请求
     * @param response     当前响应
     * @throws java.io.IOException 跳转失败
     */
    @PostMapping("/broker/{realmKey}/{alias}/callback")
    public void samlCallback(@PathVariable String realmKey,
                             @PathVariable String alias,
                             @RequestParam(name = "SAMLResponse", required = false) String samlResponse,
                             @RequestParam(required = false) String RelayState,
                             @RequestParam(required = false) String state,
                             HttpServletRequest request,
                             HttpServletResponse response) throws java.io.IOException {
        // 1. RelayState 优先:它是 SAML 侧的 state 携带位,部分 IdP 会同时回传 state 参数
        String relayState = (RelayState != null && !RelayState.isBlank()) ? RelayState : state;
        Map<String, String> credentials = samlResponse == null
                ? Map.of() : Map.of("SAMLResponse", samlResponse);
        completeLogin(realmKey, alias, relayState, null, credentials, request, response);
    }

    /**
     * 服务方(SP)元数据:供企业 IdP 登记本平台使用。
     * <p>元数据只含实体 ID 与 ACS 地址,不含密钥,可匿名访问;提供方类型不支持时返回 404。</p>
     *
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @param request  当前请求
     * @return SAML 元数据文档
     * @throws BusinessException 身份域不存在、停用或提供方不提供元数据
     */
    @GetMapping({"/broker/{realmKey}/{alias}/metadata", "/broker/{realmKey}/{alias}/metadata.xml"})
    public org.springframework.http.ResponseEntity<String> metadata(@PathVariable String realmKey,
                                                                   @PathVariable String alias,
                                                                   HttpServletRequest request) {
        requireRealm(realmKey);
        String acsUrl = brokerCallbackUrl(request, realmKey, alias);
        return brokerService.serviceProviderMetadata(realmKey, alias, acsUrl)
                .map(xml -> org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.valueOf("application/samlmetadata+xml"))
                        .body(xml))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "身份提供方不提供元数据: " + alias));
    }

    /**
     * 回调落地:消费 state、换取外部身份、建立登录态并回跳。
     * <p>OAuth2 与 SAML 共用此流程,差异仅在凭据形态。state 载荷携带的准入挑战令牌用于
     * 补验成功时取回授权端点地址,无需补验时回跳发起时的目标地址。</p>
     *
     * @param realmKey    身份域标识
     * @param alias       提供方别名
     * @param state       一次性 state
     * @param code        OAuth2 授权码;SAML 回调为 null
     * @param credentials 额外凭据(SAML 断言)
     * @param request     当前请求
     * @param response    当前响应
     * @throws java.io.IOException 跳转失败
     * @throws BusinessException   state 无效、域或提供方不匹配、账号不可用
     */
    private void completeLogin(String realmKey,
                               String alias,
                               String state,
                               String code,
                               Map<String, String> credentials,
                               HttpServletRequest request,
                               HttpServletResponse response) throws java.io.IOException {
        // 1. state 必须由当前浏览器发起:只比对 Redis 里的存在性挡不住登录 CSRF
        requireStateBoundToBrowser(request, response, state);
        // 2. 消费 state:一次性、防伪造。不匹配直接拒绝,不落到"自动绑定"路径
        String payload = stateService.consume(state);
        if (payload == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录请求已失效,请重新发起三方登录");
        }
        // 解析载荷:域与提供方必须与本次回调一致,回跳目标再过一次白名单
        // (值从 Redis 取回,发起时的校验不能当作回跳侧的依据)
        FederationStatePayload statePayload;
        try {
            statePayload = FederationStatePayload.decode(payload);
        } catch (IllegalArgumentException e) {
            // 载荷损坏或来自未知版本:这是"请求不可用",不是服务端故障
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录请求已失效,请重新发起三方登录");
        }
        if (!realmKey.equals(statePayload.realmKey()) || !alias.equals(statePayload.alias())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录请求与身份提供方不匹配");
        }
        String target = safeTarget(statePayload.target());
        String challengeToken = statePayload.challengeToken();
        RealmModel realm = requireRealm(realmKey);
        // 本次三方登录对应哪一类第一因素:失败留痕、会话留痕与主体打标共用同一个值
        String loginType = loginTypeOf(realm, alias);
        // 3. 换取外部身份并绑定/复用本地用户
        String redirectUri = brokerCallbackUrl(request, realmKey, alias);
        UserModel user;
        try {
            user = brokerService.completeLogin(realmKey, alias,
                    new CallbackContext(code, state, realmKey, redirectUri, credentials));
        } catch (BusinessException e) {
            // 三方登录失败同样留痕:外部 IdP 故障、授权码失效、外部身份冲突均记一条登录失败
            loginEventPublisher.publish(realm.realmKey(), null, alias,
                    LoginEventEvent.LOGIN_FAILURE, loginType,
                    request.getRemoteAddr(), request.getHeader("User-Agent"), e.getMessage());
            throw e;
        }
        if (user.status() != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "账号已禁用或锁定");
        }
        // 4. 建立本平台登录态(与口令登录同一套主体与权限口径)
        IamUserPrincipal principal = buildPrincipal(realm, user, loginType);
        // 先取回跳地址:establish 的升级分支会消费票据,之后再 resolve 只能拿到 null。
        // 定位口径与 establish 一致,携带请求中的会话标识;跨站 SAML POST 无 Cookie 时令牌是唯一定位键
        AdmissionChallenge challenge = challengeToken == null
                ? null : challengeService.resolve(realmKey, challengeToken, IamSessionCookie.read(request));
        String challengeReturnTo = challenge == null ? null : challenge.returnTo();
        boolean upgraded = loginSessionService.establish(request, response, principal,
                loginType, challengeToken);
        // 5. 留痕:三方登录同样是登录事件,风控与审计不能缺这一路
        loginEventPublisher.publish(realm.realmKey(), user.id(), user.username(),
                LoginEventEvent.LOGIN_SUCCESS, loginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"), null);
        // 6. 回跳:补验成功回到客户端准入要求的授权端点,而不是发起时的目标
        String finalTarget = upgraded && challengeReturnTo != null ? challengeReturnTo : target;
        response.sendRedirect(finalTarget);
    }

    /**
     * 构造登录主体。
     * <p>权限由工厂按与口令登录相同的口径计算。</p>
     *
     * @param realm     所属身份域
     * @param user      用户
     * @param loginType 本次三方登录对应的第一因素方式;未知传 null
     * @return 登录主体
     */
    private IamUserPrincipal buildPrincipal(RealmModel realm, UserModel user, String loginType) {
        return principalFactory.build(realm, user, loginType);
    }

    /**
     * 本次三方登录对应的第一因素方式。
     * <p>按提供方类型推导:微信归并为 WECHAT,外部 OIDC 与 SAML 各自独立;
     * 认不出的类型记为 FEDERATION。</p>
     *
     * @param realm 所属身份域
     * @param alias 提供方别名
     * @return 登录方式存储码
     */
    private String loginTypeOf(RealmModel realm, String alias) {
        LoginMethod method = identityProviderStore.findByIdp(realm.id(), alias)
                .map(IdentityProviderModel::providerId)
                .map(LoginMethodCatalog::toLoginMethod)
                .orElse(null);
        return method == null ? FEDERATION_LOGIN_TYPE : method.code();
    }

    /**
     * 身份域必须存在且启用。
     *
     * @param realmKey 身份域标识
     * @return 身份域模型
     * @throws BusinessException 域不存在或已停用
     */
    private RealmModel requireRealm(String realmKey) {
        RealmModel realm = realmProvider.findByRealmKey(realmKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realmKey));
        if (!realm.enabled()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "身份域已停用: " + realmKey);
        }
        return realm;
    }

    /**
     * broker 回调地址。
     *
     * @param request  当前请求
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @return 绝对回调地址,外部 IdP 按此登记
     */
    private String brokerCallbackUrl(HttpServletRequest request, String realmKey, String alias) {
        return baseUrlResolver.resolve(request) + "/broker/" + realmKey + "/" + alias + "/callback";
    }

    /**
     * 把 state 写进浏览器 cookie,作为"本次登录由这个浏览器发起"的凭据。
     * <p>跨站 POST(SAML 的 ACS)也要携带,故在 HTTPS 下用 {@code SameSite=None; Secure};
     * 明文部署下浏览器会丢弃该组合,退化为 {@code Lax}(仅覆盖 GET 回调)。</p>
     *
     * @param request  当前请求,用于判定是否 HTTPS
     * @param response 当前响应
     * @param state    本次签发的 state
     */
    private static void bindStateCookie(HttpServletRequest request, HttpServletResponse response, String state) {
        StringBuilder cookie = new StringBuilder(STATE_COOKIE).append('=').append(state)
                .append("; Path=/; Max-Age=").append(STATE_COOKIE_MAX_AGE).append("; HttpOnly");
        cookie.append(request.isSecure() ? "; Secure; SameSite=None" : "; SameSite=Lax");
        response.addHeader("Set-Cookie", cookie.toString());
    }

    /**
     * 校验 state 由当前浏览器发起,并清掉绑定 cookie。
     * <p>明文部署下跨站 POST 拿不到绑定值,此时不阻断(该环境本身已无传输安全);
     * HTTPS 下必须严格一致,否则他人可把自己的外部账号塞进受害者浏览器完成登录。</p>
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param state    回调带回的 state
     * @throws BusinessException 绑定值缺失或与 state 不一致
     */
    private static void requireStateBoundToBrowser(HttpServletRequest request, HttpServletResponse response,
                                                   String state) {
        String bound = readCookie(request, STATE_COOKIE);
        response.addHeader("Set-Cookie", STATE_COOKIE + "=; Path=/; Max-Age=0; HttpOnly");
        if (!request.isSecure()) {
            return;
        }
        if (bound == null || !bound.equals(state)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录请求与当前浏览器不匹配,请重新发起三方登录");
        }
    }

    /**
     * 读取指定名称的 cookie。
     *
     * @param request 当前请求
     * @param name    cookie 名
     * @return cookie 值;不存在时返回 null
     */
    private static String readCookie(HttpServletRequest request, String name) {
        jakarta.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 回跳目标只允许站内相对路径。
     *
     * @param target 回跳地址
     * @return 合法地址;为空或不合法时返回 /portal
     */
    private static String safeTarget(String target) {
        if (target == null || target.isBlank()) {
            return "/portal";
        }
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        // 必须以单个斜杠开头,拒绝协议相对地址("//evil.com")与绝对地址;
        // 反斜杠会被浏览器归一成斜杠("/\evil.com" → "//evil.com"),
        // 百分号编码的分隔符同理,一并拒绝
        if (!target.startsWith("/") || target.startsWith("//")
                || target.startsWith("/\\") || target.indexOf('\\') >= 0
                || lower.contains("%5c") || lower.contains("%2f")) {
            return "/portal";
        }
        for (int i = 0; i < target.length(); i++) {
            if (target.charAt(i) < 0x20) {
                return "/portal";
            }
        }
        return target;
    }
}
