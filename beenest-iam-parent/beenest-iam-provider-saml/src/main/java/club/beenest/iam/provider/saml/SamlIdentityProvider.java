package club.beenest.iam.provider.saml;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.saml.saml2.core.NameIDType;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;
import org.springframework.security.saml2.provider.service.metadata.OpenSaml5MetadataResolver;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * SAML 2.0 服务方(SP)提供方:本平台对接企业 IdP(AD FS / Shibboleth / Azure AD / Keycloak)。
 *
 * <p>与 OAuth2 类提供方的差别在报文形态:授权请求是一段 deflate + Base64 的
 * AuthnRequest,以 HTTP-Redirect 绑定送出;回调是 IdP 主动 POST 过来的 SAMLResponse,
 * 身份直接来自签名过的断言,没有换令牌环节。校验覆盖以下各项:</p>
 * <ul>
 *   <li><b>签名</b>:响应或断言必须由登记的 IdP 证书签过名,未签名一律拒绝;</li>
 *   <li><b>Destination / Recipient</b>:必须是本平台该提供方的回调地址,防报文被投递到别处;</li>
 *   <li><b>Audience</b>:必须是本平台的 SP 实体 ID,防发给 A 应用的断言被拿到 B 应用用;</li>
 *   <li><b>Issuer</b>:必须是配置里登记的 IdP 实体 ID;</li>
 *   <li><b>Conditions / SubjectConfirmation</b>:断言必须在有效期内且处于 bearer 确认窗口内;</li>
 *   <li><b>InResponseTo</b>:必须对应本次发起的请求,防跨会话重放。</li>
 * </ul>
 * <p>以上校验由 Spring Security 的断言校验器执行,本类只做配置装配
 * ({@link RelyingPartyRegistration})与身份映射。</p>
 */
public class SamlIdentityProvider implements IdentityProvider<IdentityProviderConfig> {

    static {
        /*
         * OpenSAML 的解析器/编组器注册表必须在构造任何 SAML 对象之前装载。
         * 初始化幂等,静态块保证首次使用前完成装载。
         */
        OpenSamlInitializationService.initialize();
    }

    /** SP 实体 ID:留空时按回调地址推导,便于先跑通再按 IdP 要求改名 */
    public static final String KEY_SP_ENTITY_ID = "spEntityId";
    /** IdP 实体 ID:断言里的 Issuer 必须与它一致 */
    public static final String KEY_IDP_ENTITY_ID = "idpEntityId";
    /** IdP 的单点登录地址(HTTP-Redirect 绑定) */
    public static final String KEY_SSO_URL = "ssoUrl";
    /** IdP 的签名证书(PEM,可带或不带 BEGIN/END 头) */
    public static final String KEY_IDP_CERTIFICATE = "idpCertificate";
    /** NameID 格式,默认 unspecified(AD FS / Azure AD 都接受) */
    public static final String KEY_NAME_ID_FORMAT = "nameIdFormat";
    /** 用户名取值属性(可写多个,逗号分隔,按顺序取第一个有值的) */
    public static final String KEY_USERNAME_ATTRIBUTE = "usernameAttribute";
    /** 邮箱取值属性 */
    public static final String KEY_EMAIL_ATTRIBUTE = "emailAttribute";
    /** 昵称取值属性 */
    public static final String KEY_NICKNAME_ATTRIBUTE = "nicknameAttribute";

    /** 回调里 SAML 响应的参数名(协议固定) */
    public static final String PARAM_SAML_RESPONSE = "SAMLResponse";

    /** SAML 2.0 HTTP-POST 绑定 URI:ACS 用它接收断言 */
    private static final String POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    /** 未配置属性映射时的候选键:AD FS / Shibboleth / Keycloak 的常见命名各取一个 */
    private static final List<String> USERNAME_CANDIDATES = List.of(
            "username", "uid", "name",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name");
    private static final List<String> EMAIL_CANDIDATES = List.of(
            "email", "mail",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress");
    private static final List<String> NICKNAME_CANDIDATES = List.of(
            "nickname", "displayName", "cn",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/displayname");

    /** 断言校验器:用 Spring Security 的默认规则(签名/受众/有效期/收件人/签发方/InResponseTo) */
    private final OpenSaml5AuthenticationProvider authenticationProvider = new OpenSaml5AuthenticationProvider();
    private final OpenSaml5MetadataResolver metadataResolver = new OpenSaml5MetadataResolver();
    private final IdentityProviderConfig config;

    public SamlIdentityProvider(IdentityProviderConfig config) {
        this.config = config;
    }

    @Override
    public IdentityProviderConfig getConfig() {
        return config;
    }

    // ------------------------------------------------------------------ 发起授权

    /**
     * 构建 AuthnRequest 跳转地址(HTTP-Redirect 绑定)。
     *
     * <p>请求 ID 由 RelayState 做 SHA-256 摘要推导,不额外存储;回调时按同一规则
     * 重算并比对 InResponseTo,响应投递到其他会话时 ID 对不上,校验失败。</p>
     *
     * @param request 授权请求,携带回调地址与 state(即 RelayState)
     * @return IdP 单点登录地址,附 SAMLRequest 与 RelayState 参数
     */
    @Override
    public String authorizationUrl(AuthorizationRequest request) {
        String ssoUrl = config.require(KEY_SSO_URL);
        String acsUrl = request.redirectUri();
        String requestXml = buildAuthnRequest(requestId(request.state()), spEntityId(acsUrl), ssoUrl, acsUrl);
        String encoded = deflateAndEncode(requestXml);
        StringBuilder url = new StringBuilder(ssoUrl);
        url.append(ssoUrl.contains("?") ? '&' : '?')
                .append("SAMLRequest=").append(urlEncode(encoded));
        if (request.state() != null && !request.state().isBlank()) {
            url.append("&RelayState=").append(urlEncode(request.state()));
        }
        return url.toString();
    }

    /**
     * 组装 AuthnRequest XML(未压缩)。
     *
     * @param id          请求 ID
     * @param spEntityId  SP 实体 ID,写入 Issuer
     * @param ssoUrl      IdP 单点登录地址,写入 Destination
     * @param acsUrl      断言消费地址
     * @return AuthnRequest 的 XML 文本
     */
    private String buildAuthnRequest(String id, String spEntityId, String ssoUrl, String acsUrl) {
        AuthnRequest authnRequest = newObject(AuthnRequest.DEFAULT_ELEMENT_NAME);
        authnRequest.setID(id);
        authnRequest.setVersion(SAMLVersion.VERSION_20);
        authnRequest.setIssueInstant(Instant.now());
        authnRequest.setDestination(ssoUrl);
        authnRequest.setAssertionConsumerServiceURL(acsUrl);
        // 回调走 HTTP-POST:IdP 主动把断言 POST 到 ACS,不经过浏览器查询串
        authnRequest.setProtocolBinding(POST_BINDING);
        authnRequest.setIssuer(issuer(spEntityId));
        NameIDPolicy nameIdPolicy = newObject(NameIDPolicy.DEFAULT_ELEMENT_NAME);
        nameIdPolicy.setFormat(nameIdFormat());
        // AllowCreate:企业 IdP 对首次登录的用户通常还没有 NameID,不允许创建时 IdP 直接报错
        nameIdPolicy.setAllowCreate(Boolean.TRUE);
        authnRequest.setNameIDPolicy(nameIdPolicy);
        return serialize(authnRequest);
    }

    // ------------------------------------------------------------------ 处理回调

    @Override
    public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
        // 1. 回调必须带 SAMLResponse
        String samlResponse = ctx.credential(PARAM_SAML_RESPONSE);
        if (samlResponse == null || samlResponse.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 回调缺少 SAMLResponse");
        }
        // 2. 按本次回调地址装配登记信息:Destination 校验依赖它,地址必须与发起时一致
        String acsUrl = ctx.redirectUri();
        RelyingPartyRegistration registration = registration(acsUrl);
        // 3. 解析并校验断言。签名、受众、签发方、有效期、收件人、InResponseTo 都在这一步
        Saml2AuthenticationToken token = new Saml2AuthenticationToken(
                registration, decode(samlResponse), storedRequest(registration, ctx.state()));
        Authentication authentication;
        try {
            authentication = authenticationProvider.authenticate(token);
        } catch (Saml2AuthenticationException e) {
            /*
             * 校验失败属于本次登录不成立(报文被篡改、过期、投递地址不符),
             * 与 IdP 可用性无关,统一报 UNAUTHORIZED;报 IDP_UNAVAILABLE
             * 会把排查方向带到网络上。
             */
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 断言校验失败: " + e.getMessage());
        }
        // 4. 断言 → 经纪身份
        if (!(authentication instanceof Saml2AssertionAuthentication assertionAuthentication)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 断言未携带主体");
        }
        return toIdentity(assertionAuthentication.getCredentials());
    }

    /**
     * 断言主体 → 经纪身份上下文。
     * <p>外部唯一标识取 NameID:它在 IdP 侧对同一个用户稳定,改名或换邮箱不影响绑定关系。
     * 用户名只用于展示与本地建号,按配置的属性名映射,未配置时按内置候选键匹配。</p>
     *
     * @param assertion 通过校验的断言摘要,提供 NameID 与属性集合
     * @return 经纪身份上下文
     * @throws BusinessException NameID 缺失时
     */
    BrokeredIdentityContext toIdentity(Saml2ResponseAssertionAccessor assertion) {
        String nameId = assertion.getNameId();
        if (nameId == null || nameId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 断言缺少 NameID");
        }
        BrokeredIdentityContext identity =
                new BrokeredIdentityContext(nameId, config.providerId(), config.alias());
        identity.setUsername(firstAttribute(assertion,
                configuredAttributes(KEY_USERNAME_ATTRIBUTE, USERNAME_CANDIDATES)).orElse(nameId));
        identity.setEmail(firstAttribute(assertion,
                configuredAttributes(KEY_EMAIL_ATTRIBUTE, EMAIL_CANDIDATES)).orElse(null));
        identity.setNickname(firstAttribute(assertion,
                configuredAttributes(KEY_NICKNAME_ATTRIBUTE, NICKNAME_CANDIDATES)).orElse(null));
        // 原始属性全量带回:字段映射器要能取到 IdP 下发的任意断言属性(部门、工号、组)
        assertion.getAttributes().forEach((name, values) -> identity.setAttribute(name, join(values)));
        return identity;
    }

    // ------------------------------------------------------------------ SP 元数据

    /**
     * 生成 SP 元数据(XML)。
     * <p>企业 IdP 登记依赖方时可直接导入该文件(AD FS 走"添加信赖方信任",
     * Azure AD 走"上载元数据文件"),替代手工逐项填写 ACS 地址等字段。</p>
     *
     * @param acsUrl 断言消费地址
     * @return 元数据 XML
     */
    @Override
    public Optional<String> serviceProviderMetadata(String acsUrl) {
        return Optional.of(metadataResolver.resolve(registration(acsUrl)));
    }

    // ------------------------------------------------------------------ 内部装配

    /**
     * 按回调地址装配登记信息(SP 自身 + 对端 IdP)。
     * <p>Destination 校验依赖 acsUrl,必须与发起授权时携带的回调地址一致。</p>
     *
     * @param acsUrl 断言消费地址
     * @return 依赖方登记信息
     */
    private RelyingPartyRegistration registration(String acsUrl) {
        String idpEntityId = config.require(KEY_IDP_ENTITY_ID);
        String ssoUrl = config.require(KEY_SSO_URL);
        X509Certificate certificate = idpCertificate();
        return RelyingPartyRegistration.withRegistrationId(config.alias())
                .entityId(spEntityId(acsUrl))
                .assertionConsumerServiceLocation(acsUrl)
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .nameIdFormat(nameIdFormat())
                .assertingPartyMetadata(details -> details
                        .entityId(idpEntityId)
                        .singleSignOnServiceLocation(ssoUrl)
                        .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)
                        .verificationX509Credentials(credentials ->
                                credentials.add(Saml2X509Credential.verification(certificate))))
                .build();
    }

    /**
     * 还原发起时的认证请求。
     * <p>InResponseTo 与 SubjectConfirmation 的比对只依赖请求 ID;请求体不留存,
     * ID 由 RelayState 摘要按同一规则重算得到。</p>
     *
     * @param registration 依赖方登记信息
     * @param state        回调携带的 RelayState
     * @return 原认证请求的还原对象
     * @throws BusinessException RelayState 缺失时
     */
    private Saml2RedirectAuthenticationRequest storedRequest(RelyingPartyRegistration registration, String state) {
        if (state == null || state.isBlank()) {
            // RelayState 缺失时无法把响应绑回某次发起
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 回调缺少 RelayState");
        }
        return Saml2RedirectAuthenticationRequest.withRelyingPartyRegistration(registration)
                .id(requestId(state))
                // 请求体不参与校验,但不能为空(构造器要求有值)
                .samlRequest("-")
                .relayState(state)
                .authenticationRequestUri(config.require(KEY_SSO_URL))
                .build();
    }

    /**
     * AuthnRequest 的 ID:state 的 SHA-256 摘要,前缀下划线。
     * <p>必须是合法的 XML ID(NCName):以 "_" 开头、只含十六进制字符。
     * state 是 Base64URL,可能以数字开头或带 "-",不能直接当 ID 用。</p>
     *
     * @param state 发起时签发的 state(RelayState)
     * @return 合法 XML ID 形态的请求 ID
     * @throws BusinessException state 缺失时
     */
    private static String requestId(String state) {
        if (state == null || state.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 回调缺少 RelayState");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder("_");
            for (int i = 0; i < 20; i++) {
                id.append(String.format("%02x", digest[i]));
            }
            return id.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 规范要求必选的算法
            throw new IllegalStateException(e);
        }
    }

    /**
     * SP 实体 ID:配置优先,未配置时按回调地址去掉 /callback 后缀推导。
     *
     * @param acsUrl 断言消费地址
     * @return SP 实体 ID
     */
    private String spEntityId(String acsUrl) {
        String configured = config.get(KEY_SP_ENTITY_ID);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String suffix = "/callback";
        return acsUrl.endsWith(suffix) ? acsUrl.substring(0, acsUrl.length() - suffix.length()) : acsUrl;
    }

    /**
     * 请求的 NameID 格式:配置项优先,缺省 unspecified。
     *
     * @return NameID 格式 URI
     */
    private String nameIdFormat() {
        String configured = config.get(KEY_NAME_ID_FORMAT);
        return (configured == null || configured.isBlank()) ? NameIDType.UNSPECIFIED : configured;
    }

    /**
     * 解析配置里的 IdP 证书:容忍带 BEGIN/END 头与换行的 PEM。
     *
     * @return IdP 的 X.509 证书
     * @throws BusinessException 证书缺失或解析失败时(报配置错误,与凭据错误区分)
     */
    private X509Certificate idpCertificate() {
        String pem = config.require(KEY_IDP_CERTIFICATE);
        String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            // 证书解析失败属于提供方配置错误,报 STATE_CONFLICT,与登录凭据错误区分
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "身份提供方配置无效: " + config.alias() + "." + KEY_IDP_CERTIFICATE);
        }
    }

    /**
     * 属性映射候选键:配置项存在时按逗号拆分使用,全为空时回落内置候选。
     *
     * @param key      配置键
     * @param defaults 内置候选键
     * @return 候选属性名列表
     */
    private List<String> configuredAttributes(String key, List<String> defaults) {
        String configured = config.get(key);
        if (configured == null || configured.isBlank()) {
            return defaults;
        }
        List<String> names = new ArrayList<>();
        for (String name : configured.split(",")) {
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names.isEmpty() ? defaults : names;
    }

    /**
     * 依次尝试候选属性名,取第一个有值的。
     *
     * @param assertion 断言摘要
     * @param names     候选属性名
     * @return 首个非空属性值;全部缺失时为空
     */
    private static Optional<String> firstAttribute(Saml2ResponseAssertionAccessor assertion, List<String> names) {
        for (String name : names) {
            String value = assertion.getFirstAttribute(name);
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * 多值属性拼成一个字符串:单值原样返回,多值逗号分隔(组/角色属性常见多值)。
     *
     * @param values 属性值列表
     * @return 拼接文本;入参为空时返回空串
     */
    private static String join(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                texts.add(String.valueOf(value));
            }
        }
        return String.join(",", texts);
    }

    /**
     * 构造 Issuer 元素。
     *
     * @param entityId 实体 ID
     * @return Issuer 对象
     */
    private static Issuer issuer(String entityId) {
        Issuer issuer = newObject(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(entityId);
        return issuer;
    }

    /**
     * 按元素名构造 OpenSAML 对象。
     *
     * @param name XML 元素限定名
     * @param <T>  目标对象类型
     * @return 构造出的对象
     */
    @SuppressWarnings("unchecked")
    private static <T extends XMLObject> T newObject(QName name) {
        return (T) XMLObjectSupport.buildXMLObject(name);
    }

    /**
     * 编组为 XML 文本。
     * <p>不带 XML 声明:deflate 后的 AuthnRequest 不需要它。</p>
     *
     * @param xmlObject 待编组对象
     * @return XML 文本
     * @throws IllegalStateException 编组失败
     */
    private static String serialize(XMLObject xmlObject) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(XMLObjectSupport.marshall(xmlObject)), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SAML 报文编组失败", e);
        }
    }

    /**
     * deflate + Base64(HTTP-Redirect 绑定要求)。
     * <p>必须用 nowrap 模式的 Deflater:带 zlib 头的压缩结果 IdP 无法解压。</p>
     *
     * @param xml 报文 XML 文本
     * @return 压缩并编码后的字符串
     * @throws IllegalStateException 压缩失败
     */
    private static String deflateAndEncode(String xml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater(Deflater.DEFLATED, true))) {
            deflater.write(xml.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("SAML 报文压缩失败", e);
        }
    }

    /**
     * 解码回调里的 SAMLResponse。
     * <p>HTTP-POST 绑定是纯 Base64;HTTP-Redirect 绑定还做过 deflate。
     * 先按纯 Base64 解码,结果不是 XML 开头时再解压,两种绑定的报文都可接收。</p>
     *
     * @param samlResponse 回调携带的 Base64 报文
     * @return 解码后的 XML 文本
     * @throws BusinessException Base64 非法或解不出 XML 报文
     */
    static String decode(String samlResponse) {
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(samlResponse);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 响应不是合法的 Base64");
        }
        byte[] xml = looksLikeXml(decoded) ? decoded : inflate(decoded);
        if (!looksLikeXml(xml)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 响应不是合法的 XML 报文");
        }
        return new String(xml, StandardCharsets.UTF_8);
    }

    /**
     * 判断字节序列跳过空白后是否以 "&lt;" 开头。
     *
     * @param bytes 待判定字节
     * @return 是否形如 XML 文本
     */
    private static boolean looksLikeXml(byte[] bytes) {
        for (byte b : bytes) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '<';
        }
        return false;
    }

    /**
     * nowrap 模式 inflate 解压。
     *
     * @param compressed 压缩字节
     * @return 解压结果
     * @throws BusinessException 解压失败
     */
    private static byte[] inflate(byte[] compressed) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             InflaterOutputStream inflater = new InflaterOutputStream(out, new Inflater(true))) {
            inflater.write(compressed);
            inflater.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "SAML 响应解压失败");
        }
    }

    /**
     * URL 编码(UTF-8)。
     *
     * @param value 原值
     * @return 编码结果
     */
    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
