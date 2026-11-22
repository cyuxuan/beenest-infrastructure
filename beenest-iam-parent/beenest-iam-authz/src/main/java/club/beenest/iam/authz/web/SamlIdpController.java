package club.beenest.iam.authz.web;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.saml.SamlIdpAuthnRequest;
import club.beenest.iam.authz.saml.SamlIdpAssertionSpec;
import club.beenest.iam.authz.saml.SamlIdpEndpoints;
import club.beenest.iam.authz.saml.SamlIdpLogoutRequest;
import club.beenest.iam.authz.saml.SamlIdpProtocol;
import club.beenest.iam.authz.saml.SamlIdpService;
import club.beenest.iam.authz.saml.SamlIdpSigningService;
import club.beenest.iam.authz.saml.SamlPostBindingPage;
import club.beenest.iam.authz.saml.SamlSigningMaterial;
import club.beenest.iam.authz.session.IamSessionCookie;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SamlServiceProviderModel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;

/**
 * SAML 2.0 身份提供方端点。
 * <p>本平台作为 IdP,为域内登记的下游应用签发断言。端点路径与协议端点同处
 * {@code /realms/{realmKey}} 之下,由域上下文过滤器解析当前身份域。</p>
 * <p>单点登录是两段式:未登录时先跳到登录页并把原请求地址原样带回,
 * 登录成功后浏览器重新访问本端点,此时才签发断言。因此不需要暂存 AuthnRequest,
 * 报文本身就是自足的;防重放靠请求 ID 的一次性消费,只在真正签发的那一次登记。</p>
 */
@Controller
public class SamlIdpController {

    /** 登录页回跳参数名 */
    private static final String RETURN_TO_PARAMETER = "return_to";

    /** 元数据类型 */
    private static final MediaType SAML_METADATA = MediaType.valueOf("application/samlmetadata+xml");

    /** 页面安全策略:只允许本页的内联脚本与自动提交,不加载任何外部资源 */
    private static final String POST_PAGE_CSP =
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; form-action %s";

    private final SamlIdpService samlIdpService;
    private final SamlIdpSigningService signingService;
    private final IamUserSessionService userSessionService;
    private final IamLogoutService logoutService;
    /** 对外基址:实体标识与 ACS 地址由配置决定,不取 Host 头 */
    private final IamIssuerBaseUrlResolver baseUrlResolver;

    public SamlIdpController(SamlIdpService samlIdpService,
                             SamlIdpSigningService signingService,
                             IamUserSessionService userSessionService,
                             IamLogoutService logoutService,
                             IamIssuerBaseUrlResolver baseUrlResolver) {
        this.samlIdpService = samlIdpService;
        this.signingService = signingService;
        this.userSessionService = userSessionService;
        this.logoutService = logoutService;
        this.baseUrlResolver = baseUrlResolver;
    }

    // ------------------------------------------------------------------ 元数据

    /**
     * 输出 IdP 元数据。
     * <p>元数据只含实体 ID、端点地址与签名证书公钥,不含任何私钥材料,可匿名访问。</p>
     *
     * @param realmKey 身份域标识
     * @param request  当前请求
     * @return 元数据文档
     * @throws BusinessException 身份域不存在或已停用
     */
    @GetMapping({"/realms/{realmKey}/saml/metadata", "/realms/{realmKey}/saml/metadata.xml"})
    public ResponseEntity<String> metadata(@PathVariable String realmKey, HttpServletRequest request) {
        RealmModel realm = samlIdpService.requireRealm(realmKey);
        SamlIdpEndpoints endpoints = SamlIdpEndpoints.of(origin(request), realm.realmKey());
        String xml = samlIdpService.metadata(realm.id(), realm.realmKey(), endpoints);
        return ResponseEntity.ok()
                .contentType(SAML_METADATA)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(xml);
    }

    // ------------------------------------------------------------------ 单点登录

    /**
     * 单点登录端点:接收 AuthnRequest,签发断言并以表单 POST 投递给 SP。
     * <p>未登录时先跳登录页,登录成功后浏览器会带着同样的参数再次访问本端点。</p>
     *
     * @param realmKey    身份域标识
     * @param samlRequest SAMLRequest 参数值(HTTP-Redirect 为 deflate+Base64,HTTP-POST 为纯 Base64)
     * @param relayState  SP 携带的不透明回传串;可为空
     * @param request     当前请求
     * @param response    当前响应
     * @throws IOException 写出响应失败
     */
    @RequestMapping(value = "/realms/{realmKey}/saml/sso", method = {RequestMethod.GET, RequestMethod.POST})
    public void sso(@PathVariable String realmKey,
                    @RequestParam(name = SamlIdpProtocol.PARAM_SAML_REQUEST, required = false) String samlRequest,
                    @RequestParam(name = SamlIdpProtocol.PARAM_RELAY_STATE, required = false) String relayState,
                    HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
        RealmModel realm = samlIdpService.requireRealm(realmKey);
        SamlIdpEndpoints endpoints = SamlIdpEndpoints.of(origin(request), realm.realmKey());
        SamlIdpAuthnRequest authnRequest = SamlIdpProtocol.parseAuthnRequest(samlRequest, relayState);
        SamlServiceProviderModel provider =
                samlIdpService.requireServiceProvider(realm.id(), authnRequest.issuer());
        // 1. 目标地址与消费地址都必须与登记一致,否则断言可能被投递到别处
        requireSameDestination(authnRequest, provider, endpoints);
        // 2. 登记要求验签时必须验:HTTP-Redirect 的签名在查询串上,HTTP-POST 的在报文里
        requireSignedRequestIfNeeded(authnRequest, provider, samlRequest, request);
        // 3. 未登录或会话不够新(强制重认证)时先走登录页
        IamUserPrincipal principal = CurrentUserHolder.optional();
        if (principal == null || needsReauthentication(principal, authnRequest, request)) {
            redirectToLogin(request, response, realm.realmKey());
            return;
        }
        // 4. 防重放:同一个请求 ID 只能签发一次
        samlIdpService.consumeRequestId(realm.realmKey(), authnRequest.id());
        // 5. 签发断言
        String sessionId = IamSessionCookie.read(request);
        SamlIdpAssertionSpec spec = samlIdpService.assertionSpec(
                realm, provider, authnRequest, principal, sessionId, endpoints.entityId());
        String xml = samlIdpService.buildResponse(realm.id(), realm.realmKey(), spec);
        // 6. 表单 POST 投递:断言不经过浏览器查询串,也就不会出现在日志与历史记录里
        writePostBindingPage(response, spec.acsUrl(), xml, relayState, provider.displayName());
    }

    // ------------------------------------------------------------------ 单点登出

    /**
     * 单点登出端点:接收 LogoutRequest,结束 IdP 会话并回送 LogoutResponse。
     * <p>结束的是该主体的全部会话——SAML 的单点登出语义是退出 IdP 会话,
     * 而不是只退发起登出的那一个应用。</p>
     *
     * @param realmKey    身份域标识
     * @param samlRequest SAMLRequest 参数值(承载 LogoutRequest)
     * @param relayState  SP 携带的不透明回传串;可为空
     * @param request     当前请求
     * @param response    当前响应
     * @throws IOException 写出响应失败
     */
    @RequestMapping(value = "/realms/{realmKey}/saml/slo", method = {RequestMethod.GET, RequestMethod.POST})
    public void slo(@PathVariable String realmKey,
                    @RequestParam(name = SamlIdpProtocol.PARAM_SAML_REQUEST, required = false) String samlRequest,
                    @RequestParam(name = SamlIdpProtocol.PARAM_RELAY_STATE, required = false) String relayState,
                    HttpServletRequest request,
                    HttpServletResponse response) throws IOException {
        RealmModel realm = samlIdpService.requireRealm(realmKey);
        SamlIdpEndpoints endpoints = SamlIdpEndpoints.of(origin(request), realm.realmKey());
        SamlIdpLogoutRequest logoutRequest = SamlIdpProtocol.parseLogoutRequest(samlRequest);
        SamlServiceProviderModel provider =
                samlIdpService.requireServiceProvider(realm.id(), logoutRequest.issuer());
        // 1. 同一个登出请求只能生效一次
        samlIdpService.consumeRequestId(realm.realmKey(), logoutRequest.id());
        // 2. 请求能验签才按 NameID 结束该主体的全部会话;否则只结束当前浏览器自己的会话。
        //    未验签就按 NameID 全量登出,等于给了一个任人可用的踢下线接口
        if (verifyLogoutRequest(logoutRequest, provider, samlRequest, request)) {
            samlIdpService.findUserIdByNameId(realm.id(), logoutRequest.nameId())
                    .ifPresent(userId -> logoutService.logoutAll(realm.id(), userId));
        } else {
            String sessionId = IamSessionCookie.read(request);
            if (sessionId != null) {
                logoutService.logout(sessionId);
            }
        }
        IamSessionCookie.clear(response);
        // 3. 回送 LogoutResponse。SP 未登记登出地址时无处可回,直接结束
        if (!provider.supportsSlo()) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        SamlSigningMaterial material = signingService.material(realm.id(), realm.realmKey());
        String xml = SamlIdpProtocol.buildLogoutResponse(endpoints.entityId(), provider.sloUrl(),
                logoutRequest.id(), material);
        StringBuilder target = new StringBuilder(provider.sloUrl());
        target.append(provider.sloUrl().contains("?") ? '&' : '?')
                .append(SamlIdpProtocol.PARAM_SAML_RESPONSE).append('=')
                .append(SamlIdpProtocol.urlEncode(SamlIdpProtocol.deflateAndBase64(xml)));
        if (relayState != null && !relayState.isBlank()) {
            target.append('&').append(SamlIdpProtocol.PARAM_RELAY_STATE).append('=')
                    .append(SamlIdpProtocol.urlEncode(relayState));
        }
        response.sendRedirect(target.toString());
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 校验请求目标地址与断言消费地址都与登记一致。
     *
     * @param authnRequest AuthnRequest 解析结果
     * @param provider     SP 登记项
     * @param endpoints    IdP 端点
     * @throws BusinessException 地址不一致
     */
    private static void requireSameDestination(SamlIdpAuthnRequest authnRequest,
                                               SamlServiceProviderModel provider,
                                               SamlIdpEndpoints endpoints) {
        if (isPresent(authnRequest.destination()) && !authnRequest.destination().equals(endpoints.ssoUrl())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "AuthnRequest 的目标地址与本平台单点登录地址不一致");
        }
        if (isPresent(authnRequest.assertionConsumerServiceUrl())
                && !authnRequest.assertionConsumerServiceUrl().equals(provider.acsUrl())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "AuthnRequest 的断言消费地址与登记不一致");
        }
    }

    /**
     * 登记要求验签时校验 AuthnRequest 签名。
     *
     * @param authnRequest AuthnRequest 解析结果
     * @param provider     SP 登记项
     * @param samlRequest  原始 SAMLRequest 参数值
     * @param request      当前请求
     * @throws BusinessException 未签名、缺少证书或验签不通过
     */
    private static void requireSignedRequestIfNeeded(SamlIdpAuthnRequest authnRequest,
                                                     SamlServiceProviderModel provider,
                                                     String samlRequest,
                                                     HttpServletRequest request) {
        if (!provider.requireSignedAuthnRequest()) {
            return;
        }
        if (!provider.hasCertificate()) {
            // 要求验签却没登记证书:这是配置问题,不能按"通过"处理
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "该应用要求签名请求,但未登记签名证书");
        }
        X509Certificate certificate = SamlIdpProtocol.certificateFromPem(provider.spCertificate());
        boolean valid = authnRequest.signed()
                ? SamlIdpProtocol.verifyAuthnRequestSignature(samlRequest, certificate)
                // 未内嵌 XML 签名时,签名只可能在查询串上(HTTP-Redirect 绑定)
                : SamlIdpProtocol.verifyRedirectSignature(request.getQueryString(), certificate);
        if (!valid) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "AuthnRequest 签名校验失败");
        }
    }

    /**
     * 校验登出请求的签名。
     * <p>未登记 SP 证书时没有可用的信任锚,返回 false 表示"无法确认请求来自该 SP";
     * 此时调用方只能结束当前浏览器自己的会话。登记了证书但验签不通过,视为伪造请求直接拒绝。</p>
     *
     * @param logoutRequest LogoutRequest 解析结果
     * @param provider      SP 登记项
     * @param samlRequest   原始 SAMLRequest 参数值
     * @param request       当前请求
     * @return true 表示签名有效且请求可归属到该 SP
     * @throws BusinessException 登记了证书但验签不通过
     */
    private static boolean verifyLogoutRequest(SamlIdpLogoutRequest logoutRequest,
                                               SamlServiceProviderModel provider,
                                               String samlRequest,
                                               HttpServletRequest request) {
        if (!provider.hasCertificate()) {
            return false;
        }
        X509Certificate certificate = SamlIdpProtocol.certificateFromPem(provider.spCertificate());
        boolean valid = logoutRequest.signed()
                ? SamlIdpProtocol.verifyLogoutRequestSignature(samlRequest, certificate)
                : SamlIdpProtocol.verifyRedirectSignature(request.getQueryString(), certificate);
        if (!valid) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "LogoutRequest 签名校验失败");
        }
        return true;
    }

    /**
     * 是否需要重新认证。
     * <p>SP 标记 ForceAuthn 时,要求会话建立在本次请求发起之后:
     * 旧会话不满足,跳一次登录页即可,重新登录后时间自然更新,不会形成循环。</p>
     *
     * @param principal    当前登录主体
     * @param authnRequest AuthnRequest 解析结果
     * @param request      当前请求
     * @return 需要重新认证时为 true
     */
    private boolean needsReauthentication(IamUserPrincipal principal,
                                          SamlIdpAuthnRequest authnRequest,
                                          HttpServletRequest request) {
        if (!authnRequest.forceAuthn() || authnRequest.issueInstant() == null) {
            return false;
        }
        String sessionId = IamSessionCookie.read(request);
        if (sessionId == null) {
            return true;
        }
        IamSessionPayload payload = userSessionService.loadSession(sessionId);
        if (payload == null) {
            return true;
        }
        return Instant.ofEpochMilli(payload.createdTime()).isBefore(authnRequest.issueInstant());
    }

    /**
     * 跳登录页并带回原请求地址。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param realmKey 身份域标识
     * @throws IOException 跳转失败
     */
    private static void redirectToLogin(HttpServletRequest request, HttpServletResponse response, String realmKey)
            throws IOException {
        String returnTo = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            returnTo = returnTo + "?" + query;
        }
        String target = UriComponentsBuilder.fromPath("/login")
                .queryParam(RETURN_TO_PARAMETER, returnTo)
                .queryParam("realm", realmKey)
                .build()
                .encode()
                .toUriString();
        response.sendRedirect(target);
    }

    /**
     * 输出自动提交页。
     *
     * @param response    当前响应
     * @param actionUrl   SP 的断言消费地址
     * @param xml         SAMLResponse 的 XML 文本
     * @param relayState  SP 携带的不透明回传串
     * @param displayName SP 展示名
     * @throws IOException 写出失败
     */
    private static void writePostBindingPage(HttpServletResponse response, String actionUrl, String xml,
                                             String relayState, String displayName) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Frame-Options", "DENY");
        // 不用 no-referrer:该策略会把表单提交的 Origin 变成字面量 null,
        // SP 侧若校验来源将直接拒绝;same-origin 同样不在跨源时传 referrer,
        // 既满足不回传 SAML 端点地址,又保住真实的 Origin
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", String.format(POST_PAGE_CSP, originOf(actionUrl)));
        response.getWriter().write(SamlPostBindingPage.render(
                actionUrl, SamlIdpProtocol.base64(xml), relayState, displayName));
    }

    /**
     * 当前请求的站点源地址。
     *
     * @param request 当前请求
     * @return 形如 {@code https://iam.example.com} 的源地址
     */
    private String origin(HttpServletRequest request) {
        return baseUrlResolver.resolve(request);
    }

    /**
     * 从绝对地址取站点源地址,用于收窄表单提交目标。
     *
     * @param url 绝对地址
     * @return 源地址;地址不含协议时返回 {@code 'self'}
     */
    private static String originOf(String url) {
        int index = url == null ? -1 : url.indexOf("://");
        if (index < 0) {
            return "'self'";
        }
        int slash = url.indexOf('/', index + 3);
        return slash < 0 ? url : url.substring(0, slash);
    }

    /**
     * 判断字符串非空。
     *
     * @param value 待判定字符串
     * @return 非 null 且含非空白字符时为 true
     */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
