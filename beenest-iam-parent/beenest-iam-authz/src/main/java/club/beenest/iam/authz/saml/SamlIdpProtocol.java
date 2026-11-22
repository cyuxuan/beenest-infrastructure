package club.beenest.iam.authz.saml;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.Unmarshaller;
import org.opensaml.core.xml.io.UnmarshallerFactory;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AttributeValue;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnContext;
import org.opensaml.saml.saml2.core.AuthnContextClassRef;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.NameIDFormat;
import org.opensaml.saml.saml2.metadata.SingleLogoutService;
import org.opensaml.saml.saml2.metadata.SingleSignOnService;
import org.opensaml.security.credential.UsageType;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.X509Data;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * SAML 2.0 身份提供方(IdP)侧的报文编解码。
 * <p>与 {@code SamlIdentityProvider}(本平台作为 SP)方向相反:此处负责解析下游 SP 发来的
 * AuthnRequest 与 LogoutRequest、签发带签名的 SAMLResponse 与 LogoutResponse、
 * 生成 IdP 元数据。</p>
 * <p>本类只做协议处理,不含任何身份判断:断言里放什么、该不该签发,全部由调用方决定。</p>
 */
public final class SamlIdpProtocol {

    static {
        /*
         * OpenSAML 的解析器、编组器与签名实现注册表必须在构造任何 SAML 对象之前装载。
         * 初始化幂等,静态块保证首次使用前完成装载。
         */
        OpenSamlInitializationService.initialize();
    }

    /** 断言有效期:签发后 5 分钟内可用 */
    private static final Duration ASSERTION_VALIDITY = Duration.ofMinutes(5);

    /** 允许的时钟偏差:NotBefore 往前放宽,避免两侧机器时间轻微不一致导致断言被判无效 */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    /** 入站报文的参数名(协议固定) */
    public static final String PARAM_SAML_REQUEST = "SAMLRequest";
    /** 出站报文的参数名(协议固定) */
    public static final String PARAM_SAML_RESPONSE = "SAMLResponse";
    /** 不透明回传参数名(协议固定) */
    public static final String PARAM_RELAY_STATE = "RelayState";
    /** 签名参数名(HTTP-Redirect 绑定) */
    public static final String PARAM_SIGNATURE = "Signature";
    /** 签名算法参数名(HTTP-Redirect 绑定) */
    public static final String PARAM_SIGNATURE_ALGORITHM = "SigAlg";

    /** HTTP-Redirect 绑定签名算法 URI → JCA 算法名 */
    private static final Map<String, String> SIGNATURE_ALGORITHMS = Map.of(
            "http://www.w3.org/2000/09/xmldsig#rsa-sha1", "SHA1withRSA",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", "SHA256withRSA",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384", "SHA384withRSA",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512", "SHA512withRSA");

    private SamlIdpProtocol() {
    }

    // ------------------------------------------------------------------ 入站报文

    /**
     * 解析 AuthnRequest。
     * <p>兼容 HTTP-Redirect 绑定(deflate + Base64)与 HTTP-POST 绑定(纯 Base64):
     * 解码后按内容形态判定,不依赖调用方声明的绑定方式。</p>
     *
     * @param encoded     SAMLRequest 参数值
     * @param relayState  同请求携带的 RelayState;为空表示 SP 未携带
     * @return 解析结果
     * @throws BusinessException 报文缺失、不是合法 Base64、不是合法 XML 或不是 AuthnRequest
     */
    public static SamlIdpAuthnRequest parseAuthnRequest(String encoded, String relayState) {
        if (encoded == null || encoded.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少 SAMLRequest 参数");
        }
        AuthnRequest request = unmarshall(decode(encoded), AuthnRequest.class);
        String id = request.getID();
        String issuer = request.getIssuer() == null ? null : request.getIssuer().getValue();
        if (id == null || id.isBlank() || issuer == null || issuer.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "AuthnRequest 缺少 ID 或 Issuer");
        }
        String nameIdFormat = request.getNameIDPolicy() == null ? null : request.getNameIDPolicy().getFormat();
        return new SamlIdpAuthnRequest(
                id, issuer, request.getDestination(), request.getAssertionConsumerServiceURL(),
                nameIdFormat, Boolean.TRUE.equals(request.isForceAuthn()), request.getIssueInstant(),
                relayState, request.getSignature() != null);
    }

    /**
     * 解析 LogoutRequest。
     *
     * @param encoded SAMLRequest 参数值
     * @return 解析结果
     * @throws BusinessException 报文缺失、格式非法或不是 LogoutRequest
     */
    public static SamlIdpLogoutRequest parseLogoutRequest(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少 SAMLRequest 参数");
        }
        LogoutRequest request = unmarshall(decode(encoded), LogoutRequest.class);
        String id = request.getID();
        String issuer = request.getIssuer() == null ? null : request.getIssuer().getValue();
        if (id == null || id.isBlank() || issuer == null || issuer.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "LogoutRequest 缺少 ID 或 Issuer");
        }
        NameID nameId = request.getNameID();
        return new SamlIdpLogoutRequest(id, issuer, nameId == null ? null : nameId.getValue(),
                request.getSessionIndexes().isEmpty() ? null : request.getSessionIndexes().get(0).getValue(),
                request.getSignature() != null);
    }

    // ------------------------------------------------------------------ 出站报文

    /**
     * 签发 SAMLResponse(HTTP-POST 绑定)。
     * <p>断言总是内联在响应里;是否对断言签名由 {@code spec.signAssertion()} 决定。
     * 响应本身不签名:签名断言已足以证明来源,响应级签名会让部分 SP 重复验签。</p>
     *
     * @param spec     断言内容
     * @param material 签名材料
     * @return SAMLResponse 的 XML 文本
     * @throws IllegalStateException 编组或签名失败
     */
    public static String buildResponse(SamlIdpAssertionSpec spec, SamlSigningMaterial material) {
        Instant now = Instant.now();
        Response response = newObject(Response.DEFAULT_ELEMENT_NAME);
        response.setID(newId());
        response.setVersion(SAMLVersion.VERSION_20);
        response.setIssueInstant(now);
        response.setDestination(spec.acsUrl());
        response.setIssuer(issuer(spec.issuer()));
        if (isPresent(spec.inResponseTo())) {
            response.setInResponseTo(spec.inResponseTo());
        }
        response.setStatus(successStatus());
        Assertion assertion = buildAssertion(spec, material, now);
        response.getAssertions().add(assertion);
        return marshalAndSign(response, assertion.getSignature());
    }

    /**
     * 签发 LogoutResponse。
     *
     * @param issuer       IdP 实体 ID
     * @param destination  SP 的登出地址
     * @param inResponseTo 对应 LogoutRequest 的 ID;为空表示 IdP 主动发起
     * @param material     签名材料;为空表示不签名
     * @return LogoutResponse 的 XML 文本
     * @throws IllegalStateException 编组或签名失败
     */
    public static String buildLogoutResponse(String issuer, String destination, String inResponseTo,
                                             SamlSigningMaterial material) {
        LogoutResponse response = newObject(LogoutResponse.DEFAULT_ELEMENT_NAME);
        response.setID(newId());
        response.setVersion(SAMLVersion.VERSION_20);
        response.setIssueInstant(Instant.now());
        response.setDestination(destination);
        response.setIssuer(issuer(issuer));
        if (isPresent(inResponseTo)) {
            response.setInResponseTo(inResponseTo);
        }
        response.setStatus(successStatus());
        Signature signature = material == null ? null : signature(material, issuer);
        if (signature != null) {
            response.setSignature(signature);
        }
        return marshalAndSign(response, signature);
    }

    /**
     * 生成 IdP 元数据。
     * <p>同时公布 HTTP-Redirect 与 HTTP-POST 两种单点登录绑定:前者是 SP 发起登录的常用形态,
     * 后者用于报文较长或需要 XML 级签名的场景。</p>
     *
     * @param entityId    IdP 实体 ID
     * @param ssoUrl      单点登录端点地址
     * @param sloUrl      单点登出端点地址;为空表示不公布 SLO
     * @param certificate 签名证书
     * @return 元数据 XML
     * @throws IllegalStateException 编组失败
     */
    public static String buildMetadata(String entityId, String ssoUrl, String sloUrl, X509Certificate certificate) {
        EntityDescriptor descriptor = newObject(EntityDescriptor.DEFAULT_ELEMENT_NAME);
        descriptor.setEntityID(entityId);

        IDPSSODescriptor role = newObject(IDPSSODescriptor.DEFAULT_ELEMENT_NAME);
        role.addSupportedProtocol(SAMLConstants.SAML20P_NS);

        KeyDescriptor keyDescriptor = newObject(KeyDescriptor.DEFAULT_ELEMENT_NAME);
        keyDescriptor.setUse(UsageType.SIGNING);
        keyDescriptor.setKeyInfo(keyInfo(certificate));
        role.getKeyDescriptors().add(keyDescriptor);

        // 支持的 NameID 格式:未指定 / 持久标识 / 邮箱,覆盖常见 SP 的偏好
        role.getNameIDFormats().add(nameIdFormat(NameIDType.UNSPECIFIED));
        role.getNameIDFormats().add(nameIdFormat(NameIDType.PERSISTENT));
        role.getNameIDFormats().add(nameIdFormat(NameIDType.EMAIL));

        role.getSingleSignOnServices().add(singleSignOnService(
                SAMLConstants.SAML2_REDIRECT_BINDING_URI, ssoUrl));
        role.getSingleSignOnServices().add(singleSignOnService(
                SAMLConstants.SAML2_POST_BINDING_URI, ssoUrl));

        if (isPresent(sloUrl)) {
            role.getSingleLogoutServices().add(singleLogoutService(
                    SAMLConstants.SAML2_REDIRECT_BINDING_URI, sloUrl));
            role.getSingleLogoutServices().add(singleLogoutService(
                    SAMLConstants.SAML2_POST_BINDING_URI, sloUrl));
        }

        descriptor.getRoleDescriptors().add(role);
        return marshalAndSign(descriptor, null);
    }

    // ------------------------------------------------------------------ 验签

    /**
     * 校验报文内的 XML 签名。
     *
     * @param signedObject 带签名的 SAML 对象
     * @param certificate  SP 证书
     * @return 签名有效时为 true;无签名、证书为空或校验不通过时为 false
     */
    public static boolean verifyXmlSignature(SignableSAMLObject signedObject, X509Certificate certificate) {
        if (signedObject == null || certificate == null) {
            return false;
        }
        Signature signature = signedObject.getSignature();
        if (signature == null) {
            return false;
        }
        try {
            SignatureValidator.validate(signature, new BasicX509Credential(certificate));
            return true;
        } catch (SignatureException e) {
            return false;
        }
    }

    /**
     * 校验 AuthnRequest 报文内的 XML 签名(HTTP-POST 绑定)。
     * <p>HTTP-Redirect 绑定的签名在查询串上,不走这里。</p>
     *
     * @param encoded     SAMLRequest 参数值
     * @param certificate SP 证书
     * @return 签名有效时为 true;报文解析失败、无签名或校验不通过时为 false
     */
    public static boolean verifyAuthnRequestSignature(String encoded, X509Certificate certificate) {
        return verifyMessageSignature(encoded, AuthnRequest.class, certificate);
    }

    /**
     * 校验 LogoutRequest 报文内的 XML 签名(HTTP-POST 绑定)。
     *
     * @param encoded     SAMLRequest 参数值
     * @param certificate SP 证书
     * @return 签名有效时为 true;报文解析失败、无签名或校验不通过时为 false
     */
    public static boolean verifyLogoutRequestSignature(String encoded, X509Certificate certificate) {
        return verifyMessageSignature(encoded, LogoutRequest.class, certificate);
    }

    /**
     * 解码、反序列化并校验报文内的 XML 签名。
     *
     * @param encoded     报文参数值
     * @param type        报文类型
     * @param certificate SP 证书
     * @param <T>         报文类型
     * @return 签名有效时为 true
     */
    private static <T extends XMLObject & SignableSAMLObject> boolean verifyMessageSignature(
            String encoded, Class<T> type, X509Certificate certificate) {
        if (encoded == null || encoded.isBlank() || certificate == null) {
            return false;
        }
        try {
            return verifyXmlSignature(unmarshall(decode(encoded), type), certificate);
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * 校验 HTTP-Redirect 绑定的查询串签名。
     * <p>签名对象是 {@code SAMLRequest=...&RelayState=...&SigAlg=...} 这段原始查询串,
     * 因此不能对参数解码后重新拼接:重新编码的顺序与转义都可能与原串不同。</p>
     *
     * @param rawQueryString 原始查询串(不含问号)
     * @param certificate    SP 证书
     * @return 签名有效时为 true;缺少签名参数、算法不认识或校验不通过时为 false
     */
    public static boolean verifyRedirectSignature(String rawQueryString, X509Certificate certificate) {
        if (rawQueryString == null || rawQueryString.isBlank() || certificate == null) {
            return false;
        }
        int index = rawQueryString.indexOf("&" + PARAM_SIGNATURE + "=");
        if (index < 0) {
            return false;
        }
        String signed = rawQueryString.substring(0, index);
        String signatureValue = rawQueryString.substring(index + PARAM_SIGNATURE.length() + 2);
        String algorithmUri = queryParameter(rawQueryString, PARAM_SIGNATURE_ALGORITHM);
        if (algorithmUri == null) {
            return false;
        }
        String jcaAlgorithm = SIGNATURE_ALGORITHMS.get(algorithmUri);
        if (jcaAlgorithm == null) {
            return false;
        }
        try {
            java.security.Signature verifier = java.security.Signature.getInstance(jcaAlgorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(signed.getBytes(StandardCharsets.UTF_8));
            // 签名值在查询串里是 URL 编码过的,Base64 的 + / = 都会被转义
            return verifier.verify(Base64.getDecoder()
                    .decode(URLDecoder.decode(signatureValue, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 编解码

    /**
     * 解码入站报文。
     * <p>HTTP-Redirect 绑定是 deflate + Base64,HTTP-POST 绑定是纯 Base64。
     * 先按纯 Base64 解,结果不是 XML 时再解压,两种绑定都能接收。</p>
     *
     * @param encoded 报文参数值
     * @return 解码后的 XML 文本
     * @throws BusinessException Base64 非法或解不出 XML 报文
     */
    public static String decode(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SAML 报文不是合法的 Base64");
        }
        byte[] xml = looksLikeXml(decoded) ? decoded : inflate(decoded);
        if (!looksLikeXml(xml)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SAML 报文不是合法的 XML");
        }
        return new String(xml, StandardCharsets.UTF_8);
    }

    /**
     * deflate + Base64 编码(HTTP-Redirect 绑定要求)。
     *
     * @param xml 报文 XML 文本
     * @return 压缩并编码后的字符串
     * @throws IllegalStateException 压缩失败
     */
    public static String deflateAndBase64(String xml) {
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
     * 纯 Base64 编码(HTTP-POST 绑定要求)。
     *
     * @param xml 报文 XML 文本
     * @return Base64 字符串
     */
    public static String base64(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * URL 编码(UTF-8)。
     *
     * @param value 原值
     * @return 编码结果
     */
    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 解析 PEM 形态的证书。
     *
     * @param pem 证书文本,可带或不带 BEGIN/END 头
     * @return X.509 证书
     * @throws BusinessException 证书为空或内容非法
     */
    public static X509Certificate certificateFromPem(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "SAML 证书为空");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) CERTIFICATE-----", "").replaceAll("\\s", "");
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "SAML 证书解析失败");
        }
    }

    // ------------------------------------------------------------------ 内部:断言构造

    /**
     * 构造断言。
     *
     * @param spec     断言内容
     * @param material 签名材料
     * @param now      签发时刻
     * @return 断言对象;signAssertion 为真时已挂上待签的 Signature 占位
     */
    private static Assertion buildAssertion(SamlIdpAssertionSpec spec, SamlSigningMaterial material, Instant now) {
        Instant notBefore = now.minus(CLOCK_SKEW);
        Instant notOnOrAfter = now.plus(ASSERTION_VALIDITY);

        Assertion assertion = newObject(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID(newId());
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssueInstant(now);
        assertion.setIssuer(issuer(spec.issuer()));
        assertion.setSubject(subject(spec, notOnOrAfter));
        assertion.setConditions(conditions(spec.audience(), notBefore, notOnOrAfter));
        assertion.getAuthnStatements().add(authnStatement(spec, now));
        if (spec.attributes() != null && !spec.attributes().isEmpty()) {
            assertion.getAttributeStatements().add(attributeStatement(spec.attributes()));
        }
        if (spec.signAssertion()) {
            assertion.setSignature(signature(material, spec.issuer()));
        }
        return assertion;
    }

    /**
     * 构造 Subject:NameID + bearer 确认,确认数据绑定接收方与请求 ID。
     *
     * @param spec         断言内容
     * @param notOnOrAfter 断言失效时间
     * @return Subject 对象
     */
    private static Subject subject(SamlIdpAssertionSpec spec, Instant notOnOrAfter) {
        Subject subject = newObject(Subject.DEFAULT_ELEMENT_NAME);
        NameID nameId = newObject(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setFormat(isPresent(spec.nameIdFormat()) ? spec.nameIdFormat() : NameIDType.UNSPECIFIED);
        nameId.setValue(spec.nameId());
        subject.setNameID(nameId);

        SubjectConfirmation confirmation = newObject(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        SubjectConfirmationData data = newObject(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        data.setRecipient(spec.acsUrl());
        data.setNotOnOrAfter(notOnOrAfter);
        if (isPresent(spec.inResponseTo())) {
            data.setInResponseTo(spec.inResponseTo());
        }
        confirmation.setSubjectConfirmationData(data);
        subject.getSubjectConfirmations().add(confirmation);
        return subject;
    }

    /**
     * 构造 Conditions:有效期 + 受众限制。
     *
     * @param audience     SP 实体 ID
     * @param notBefore    生效时间
     * @param notOnOrAfter 失效时间
     * @return Conditions 对象
     */
    private static Conditions conditions(String audience, Instant notBefore, Instant notOnOrAfter) {
        Conditions conditions = newObject(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(notBefore);
        conditions.setNotOnOrAfter(notOnOrAfter);
        AudienceRestriction restriction = newObject(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        Audience value = newObject(Audience.DEFAULT_ELEMENT_NAME);
        value.setURI(audience);
        restriction.getAudiences().add(value);
        conditions.getAudienceRestrictions().add(restriction);
        return conditions;
    }

    /**
     * 构造认证语句:认证方式记为口令保护传输,并带上会话索引供登出定位。
     *
     * @param spec 断言内容
     * @param now  签发时刻
     * @return AuthnStatement 对象
     */
    private static AuthnStatement authnStatement(SamlIdpAssertionSpec spec, Instant now) {
        AuthnStatement statement = newObject(AuthnStatement.DEFAULT_ELEMENT_NAME);
        statement.setAuthnInstant(now);
        if (isPresent(spec.sessionIndex())) {
            statement.setSessionIndex(spec.sessionIndex());
        }
        if (spec.sessionNotOnOrAfter() != null) {
            statement.setSessionNotOnOrAfter(spec.sessionNotOnOrAfter());
        }
        AuthnContext context = newObject(AuthnContext.DEFAULT_ELEMENT_NAME);
        AuthnContextClassRef classRef = newObject(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
        context.setAuthnContextClassRef(classRef);
        statement.setAuthnContext(context);
        return statement;
    }

    /**
     * 构造属性语句。
     *
     * @param attributes 属性名 → 值列表
     * @return AttributeStatement 对象
     */
    private static AttributeStatement attributeStatement(Map<String, List<String>> attributes) {
        AttributeStatement statement = newObject(AttributeStatement.DEFAULT_ELEMENT_NAME);
        attributes.forEach((name, values) -> {
            if (name == null || name.isBlank() || values == null || values.isEmpty()) {
                return;
            }
            Attribute attribute = newObject(Attribute.DEFAULT_ELEMENT_NAME);
            attribute.setName(name);
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                AttributeValue attributeValue = newObject(AttributeValue.DEFAULT_ELEMENT_NAME);
                attributeValue.setTextContent(value);
                attribute.getAttributeValues().add(attributeValue);
            }
            statement.getAttributes().add(attribute);
        });
        return statement;
    }

    /**
     * 构造成功状态。
     *
     * @return Status 对象
     */
    private static Status successStatus() {
        Status status = newObject(Status.DEFAULT_ELEMENT_NAME);
        StatusCode code = newObject(StatusCode.DEFAULT_ELEMENT_NAME);
        code.setValue(StatusCode.SUCCESS);
        status.setStatusCode(code);
        return status;
    }

    /**
     * 构造待签的 Signature 占位。
     *
     * @param material 签名材料
     * @param entityId 签名者实体 ID
     * @return Signature 对象
     * @throws IllegalStateException 签名材料缺失
     */
    private static Signature signature(SamlSigningMaterial material, String entityId) {
        if (material == null || material.privateKey() == null || material.certificate() == null) {
            throw new IllegalStateException("SAML 签名材料缺失");
        }
        BasicX509Credential credential = new BasicX509Credential(material.certificate(), material.privateKey());
        credential.setEntityId(entityId);
        credential.setUsageType(UsageType.SIGNING);
        Signature signature = newObject(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(credential);
        signature.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        return signature;
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
     * 构造元数据里的 KeyInfo:只放证书,不放公钥值。
     *
     * @param certificate 签名证书
     * @return KeyInfo 对象
     */
    private static KeyInfo keyInfo(X509Certificate certificate) {
        KeyInfo keyInfo = newObject(KeyInfo.DEFAULT_ELEMENT_NAME);
        X509Data data = newObject(X509Data.DEFAULT_ELEMENT_NAME);
        org.opensaml.xmlsec.signature.X509Certificate value =
                newObject(org.opensaml.xmlsec.signature.X509Certificate.DEFAULT_ELEMENT_NAME);
        try {
            value.setValue(Base64.getEncoder().encodeToString(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("SAML 证书编码失败", e);
        }
        data.getX509Certificates().add(value);
        keyInfo.getX509Datas().add(data);
        return keyInfo;
    }

    /**
     * 构造元数据里的 NameID 格式声明。
     *
     * @param uri 格式 URI
     * @return NameIDFormat 对象
     */
    private static NameIDFormat nameIdFormat(String uri) {
        NameIDFormat format = newObject(NameIDFormat.DEFAULT_ELEMENT_NAME);
        format.setURI(uri);
        return format;
    }

    /**
     * 构造元数据里的单点登录端点声明。
     *
     * @param binding  绑定 URI
     * @param location 端点地址
     * @return SingleSignOnService 对象
     */
    private static SingleSignOnService singleSignOnService(String binding, String location) {
        SingleSignOnService service = newObject(SingleSignOnService.DEFAULT_ELEMENT_NAME);
        service.setBinding(binding);
        service.setLocation(location);
        return service;
    }

    /**
     * 构造元数据里的单点登出端点声明。
     *
     * @param binding  绑定 URI
     * @param location 端点地址
     * @return SingleLogoutService 对象
     */
    private static SingleLogoutService singleLogoutService(String binding, String location) {
        SingleLogoutService service = newObject(SingleLogoutService.DEFAULT_ELEMENT_NAME);
        service.setBinding(binding);
        service.setLocation(location);
        return service;
    }

    // ------------------------------------------------------------------ 内部:编组与解析

    /**
     * 先编组为 DOM 再签名,最后序列化。
     * <p>OpenSAML 的签名实现在签名时必须能拿到已编组的 DOM,顺序不能颠倒。</p>
     *
     * @param object    待输出对象
     * @param signature 签名对象;为空表示不签名
     * @return XML 文本(带 XML 声明)
     * @throws IllegalStateException 编组或签名失败
     */
    private static String marshalAndSign(XMLObject object, Signature signature) {
        Element dom;
        try {
            dom = XMLObjectSupport.marshall(object);
        } catch (Exception e) {
            throw new IllegalStateException("SAML 报文编组失败", e);
        }
        if (signature != null) {
            try {
                Signer.signObject(signature);
            } catch (SignatureException e) {
                throw new IllegalStateException("SAML 报文签名失败", e);
            }
        }
        return serialize(dom);
    }

    /**
     * DOM 序列化为 XML 文本。
     *
     * @param element 根元素
     * @return XML 文本(带 XML 声明)
     * @throws IllegalStateException 序列化失败
     */
    private static String serialize(Element element) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SAML 报文序列化失败", e);
        }
    }

    /**
     * XML 文本解析为 DOM 根元素。
     * <p>禁用 DOCTYPE 与外部实体:SAML 报文不需要 DTD,开启即等于给 XXE 留门。</p>
     *
     * @param xml XML 文本
     * @return 根元素
     * @throws BusinessException 不是合法 XML
     */
    private static Element parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return document.getDocumentElement();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SAML 报文不是合法的 XML");
        }
    }

    /**
     * DOM 反序列化为指定类型的 SAML 对象。
     *
     * @param xml  XML 文本
     * @param type 期望类型
     * @param <T>  SAML 对象类型
     * @return 反序列化结果
     * @throws BusinessException 报文类型不符或结构非法
     */
    private static <T extends XMLObject> T unmarshall(String xml, Class<T> type) {
        Element element = parse(xml);
        UnmarshallerFactory factory = XMLObjectProviderRegistrySupport.getUnmarshallerFactory();
        Unmarshaller unmarshaller = factory.getUnmarshaller(element);
        if (unmarshaller == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "无法识别的 SAML 报文");
        }
        XMLObject object;
        try {
            object = unmarshaller.unmarshall(element);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SAML 报文解析失败");
        }
        if (!type.isInstance(object)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SAML 报文类型不符");
        }
        return type.cast(object);
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
            throw new BusinessException(ErrorCode.PARAM_INVALID, "SAML 报文解压失败");
        }
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
     * 取查询串中指定参数的原值(已解码)。
     *
     * @param query 原始查询串
     * @param name  参数名
     * @return 参数值;不存在时返回 null
     */
    private static String queryParameter(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 生成报文 ID。
     * <p>必须是合法的 XML ID(NCName):以 "_" 开头、只含字母数字。</p>
     *
     * @return 形如 _a1b2c3... 的标识
     */
    private static String newId() {
        return "_" + UUID.randomUUID().toString().replace("-", "");
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
     * 判断字符串非空。
     *
     * @param value 待判定字符串
     * @return 非 null 且含非空白字符时为 true
     */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
