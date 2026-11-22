package club.beenest.iam.provider.saml;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.AttributeValue;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.security.credential.BasicCredential;
import org.opensaml.xmlsec.SignatureSigningParameters;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureSupport;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SAML 2.0 服务方测试。
 *
 * <p>夹具会真的造一份签过名的 SAMLResponse:签名校验、受众、收件人、有效期、InResponseTo
 * 这些规则只有在"完整的报文"上才会被触发,用 mock 替代等于把要验证的东西全绕开了。
 * 唯一替代的是 IdP 本身——请求由测试直接构造,证书由 BouncyCastle 现场生成。</p>
 */
class SamlIdentityProviderTest {

    static {
        OpenSamlInitializationService.initialize();
    }

    private static final String IDP_ENTITY_ID = "https://idp.example.com/metadata";
    private static final String SSO_URL = "https://idp.example.com/adfs/ls/";
    private static final String ACS = "https://iam.example.com/broker/nestid/corp-saml/callback";
    private static final String SP_ENTITY_ID = "https://iam.example.com/broker/nestid/corp-saml";
    private static final String STATE = "b7Xk2-state_value";
    private static final String NAME_ID = "zhangsan@corp.example.com";

    private static final TestIdp IDP = new TestIdp();

    // ------------------------------------------------------------------ 发起授权

    @Test
    void authorizationUrlCarriesDeflatedAuthnRequestAndRelayState() {
        String url = provider(Map.of()).authorizationUrl(new AuthorizationRequest(ACS, STATE, Map.of()));

        assertThat(url).startsWith(SSO_URL);
        Map<String, String> params = queryParams(url);
        assertThat(params).containsKey("SAMLRequest");
        assertThat(params.get("RelayState")).isEqualTo(STATE);

        String xml = inflate(params.get("SAMLRequest"));
        assertThat(xml)
                .contains("Destination=\"" + SSO_URL + "\"")
                .contains("AssertionConsumerServiceURL=\"" + ACS + "\"")
                .contains("<saml2:Issuer") // 命名空间前缀由编组器决定,只断言元素存在
                .contains(SP_ENTITY_ID)
                .contains("ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"")
                .contains("AllowCreate=\"true\"");
    }

    @Test
    void authorizationUrlDerivesSpEntityIdWhenNotConfigured() {
        SamlIdentityProvider provider = provider(Map.of(
                SamlIdentityProvider.KEY_SP_ENTITY_ID, "",
                SamlIdentityProvider.KEY_USERNAME_ATTRIBUTE, ""));

        String xml = inflate(queryParams(provider.authorizationUrl(
                new AuthorizationRequest(ACS, STATE, Map.of()))).get("SAMLRequest"));

        // 留空时按回调地址去掉 /callback 推导
        assertThat(xml).contains(SP_ENTITY_ID);
    }

    // ------------------------------------------------------------------ 处理回调

    @Test
    void callbackBuildsIdentityFromSignedAssertion() {
        String response = IDP.signedResponse(ACS, SP_ENTITY_ID, requestId(), Instant.now().plusSeconds(300), true);

        BrokeredIdentityContext identity = provider(Map.of()).buildIdentityFromCallback(callback(response));

        assertThat(identity.getExternalId()).isEqualTo(NAME_ID);
        assertThat(identity.getUsername()).isEqualTo("zhangsan");
        assertThat(identity.getEmail()).isEqualTo("zhangsan@corp.example.com");
        assertThat(identity.getNickname()).isEqualTo("张三");
        assertThat(identity.getProviderId()).isEqualTo("saml");
        assertThat(identity.getAlias()).isEqualTo("corp-saml");
        // 多值属性按逗号拼接,组/角色映射靠它
        assertThat(identity.getAttributes()).containsEntry("groups", "iam-admins,iam-users");
    }

    @Test
    void callbackRejectsUnsignedAssertion() {
        String response = IDP.signedResponse(ACS, SP_ENTITY_ID, requestId(), Instant.now().plusSeconds(300), false);

        assertRejected(callback(response), "unsigned");
    }

    @Test
    void callbackRejectsAssertionDeliveredToAnotherAcs() {
        String response = IDP.signedResponse("https://evil.example.com/acs", SP_ENTITY_ID, requestId(),
                Instant.now().plusSeconds(300), true);

        assertRejected(callback(response), "Invalid destination");
    }

    @Test
    void callbackRejectsAssertionForAnotherAudience() {
        String response = IDP.signedResponse(ACS, "https://another-sp.example.com", requestId(),
                Instant.now().plusSeconds(300), true);

        assertRejected(callback(response), "audiences");
    }

    @Test
    void callbackRejectsResponseFromAnotherLogin() {
        // 攻击者拿自己那次登录的断言,配上受害者的 state 提交:InResponseTo 对不上
        String response = IDP.signedResponse(ACS, SP_ENTITY_ID, "_another-request", Instant.now().plusSeconds(300), true);

        assertRejected(callback(response), "InResponseTo");
    }

    @Test
    void callbackRejectsExpiredAssertion() {
        String response = IDP.signedResponse(ACS, SP_ENTITY_ID, requestId(), Instant.now().minusSeconds(600), true);

        assertRejected(callback(response), "NotOnOrAfter");
    }

    @Test
    void callbackRejectsResponseSignedByAnotherKey() {
        TestIdp attacker = new TestIdp();
        String response = attacker.signedResponse(ACS, SP_ENTITY_ID, requestId(), Instant.now().plusSeconds(300), true);

        assertRejected(callback(response), "Invalid signature");
    }

    @Test
    void callbackRequiresSamlResponse() {
        CallbackContext ctx = new CallbackContext(null, STATE, "nestid", ACS, Map.of());

        assertThatThrownBy(() -> provider(Map.of()).buildIdentityFromCallback(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SAMLResponse");
    }

    // ------------------------------------------------------------------ 元数据与配置

    @Test
    void metadataDescribesAssertionConsumerService() {
        String metadata = provider(Map.of()).serviceProviderMetadata(ACS).orElseThrow();

        assertThat(metadata)
                .contains(SP_ENTITY_ID)
                .contains("AssertionConsumerService")
                .contains(ACS)
                .contains("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");
    }

    @Test
    void missingRequiredConfigurationIsReported() {
        // 少了 SSO 地址:属于"管理员没配全",要报配置缺失而不是登录失败
        SamlIdentityProvider provider = new SamlIdentityProvider(config(Map.of(
                SamlIdentityProvider.KEY_SSO_URL, "")));

        assertThatThrownBy(() -> provider.authorizationUrl(new AuthorizationRequest(ACS, STATE, Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("身份提供方配置缺失");
    }

    @Test
    void invalidCertificateIsReportedAsConfigurationProblem() {
        SamlIdentityProvider provider = new SamlIdentityProvider(config(Map.of(
                SamlIdentityProvider.KEY_IDP_CERTIFICATE, "not-a-certificate")));

        assertThatThrownBy(() -> provider.serviceProviderMetadata(ACS))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT.getCode()));
    }

    @Test
    void decodeAcceptsDeflatedAndPlainMessages() {
        String xml = "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\"/>";

        assertThat(SamlIdentityProvider.decode(Base64.getEncoder()
                .encodeToString(xml.getBytes(StandardCharsets.UTF_8)))).isEqualTo(xml);
        assertThat(SamlIdentityProvider.decode(Base64.getEncoder()
                .encodeToString(deflate(xml)))).isEqualTo(xml);
    }

    // ------------------------------------------------------------------ 夹具

    private SamlIdentityProvider provider(Map<String, String> overrides) {
        return new SamlIdentityProvider(config(overrides));
    }

    private IdentityProviderConfig config(Map<String, String> overrides) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(SamlIdentityProvider.KEY_IDP_ENTITY_ID, IDP_ENTITY_ID);
        config.put(SamlIdentityProvider.KEY_SSO_URL, SSO_URL);
        config.put(SamlIdentityProvider.KEY_SP_ENTITY_ID, SP_ENTITY_ID);
        config.put(SamlIdentityProvider.KEY_IDP_CERTIFICATE, IDP.certificatePem());
        config.putAll(overrides);
        return new IdentityProviderConfig("corp-saml", "saml", "企业 AD", true, config);
    }

    private CallbackContext callback(String samlResponse) {
        return new CallbackContext(null, STATE, "nestid", ACS, Map.of("SAMLResponse", samlResponse));
    }

    /**
     * 断言被拒。
     * <p>光看错误码不够:报文解析失败同样会得到 UNAUTHORIZED,那样"签名为空"的用例
     * 即使压根没走到签名校验也会绿。这里连拒绝原因一起核对。</p>
     */
    private void assertRejected(CallbackContext ctx, String expectedReason) {
        assertThatThrownBy(() -> provider(Map.of()).buildIdentityFromCallback(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(expectedReason)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED.getCode()));
    }

    /** 发起时的请求 ID:从真实的 AuthnRequest 里解出来,顺带验证两侧推导一致。 */
    private String requestId() {
        String url = provider(Map.of()).authorizationUrl(new AuthorizationRequest(ACS, STATE, Map.of()));
        String xml = inflate(queryParams(url).get("SAMLRequest"));
        int start = xml.indexOf("ID=\"") + 4;
        return xml.substring(start, xml.indexOf('"', start));
    }

    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        int question = url.indexOf('?');
        for (String pair : url.substring(question + 1).split("&")) {
            int split = pair.indexOf('=');
            params.put(pair.substring(0, split), URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String inflate(String encoded) {
        byte[] raw = Base64.getDecoder().decode(encoded);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             InflaterOutputStream inflater = new InflaterOutputStream(out, new Inflater(true))) {
            inflater.write(raw);
            inflater.finish();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] deflate(String xml) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DeflaterOutputStream deflater = new DeflaterOutputStream(out, new Deflater(Deflater.DEFLATED, true))) {
            deflater.write(xml.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 一个最小可用的 IdP:自签证书 + 能签发带签名的 Response。 */
    private static final class TestIdp {

        private final X509Certificate certificate;
        private final PrivateKey privateKey;

        TestIdp() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                X500Name name = new X500Name("CN=beenest-test-idp");
                Instant now = Instant.now();
                JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                        BigInteger.valueOf(now.toEpochMilli()),
                        Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(86_400)), name,
                        keyPair.getPublic());
                ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
                this.certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
                this.privateKey = keyPair.getPrivate();
            } catch (Exception e) {
                throw new IllegalStateException("测试证书生成失败", e);
            }
        }

        String certificatePem() {
            try {
                return "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(certificate.getEncoded())
                        + "\n-----END CERTIFICATE-----";
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * 签发一份 SAMLResponse。
         *
         * @param acsUrl      断言投递的收件地址(Destination / Recipient)
         * @param spEntityId  受众(SP 实体 ID)
         * @param inResponseTo 对应 AuthnRequest 的 ID
         * @param notOnOrAfter 断言失效时刻
         * @param signed      是否对断言签名(用来验证"未签名一律拒绝")
         */
        String signedResponse(String acsUrl, String spEntityId, String inResponseTo,
                              Instant notOnOrAfter, boolean signed) {
            Instant now = Instant.now();
            Response response = build(Response.DEFAULT_ELEMENT_NAME);
            response.setID("_response-" + now.toEpochMilli());
            response.setVersion(SAMLVersion.VERSION_20);
            response.setIssueInstant(now);
            response.setDestination(acsUrl);
            response.setInResponseTo(inResponseTo);
            response.setIssuer(issuer(IDP_ENTITY_ID));
            response.setStatus(successStatus());

            Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
            assertion.setID("_assertion-" + now.toEpochMilli());
            assertion.setVersion(SAMLVersion.VERSION_20);
            assertion.setIssueInstant(now);
            assertion.setIssuer(issuer(IDP_ENTITY_ID));
            assertion.setSubject(subject(acsUrl, inResponseTo, notOnOrAfter));
            assertion.setConditions(conditions(spEntityId, notOnOrAfter));
            assertion.getAttributeStatements().add(attributeStatement());
            response.getAssertions().add(assertion);

            if (signed) {
                sign(assertion);
            }
            return Base64.getEncoder().encodeToString(serialize(response).getBytes(StandardCharsets.UTF_8));
        }

        private Subject subject(String acsUrl, String inResponseTo, Instant notOnOrAfter) {
            Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
            NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
            nameId.setValue(NAME_ID);
            nameId.setFormat("urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified");
            subject.setNameID(nameId);

            SubjectConfirmation confirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
            confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
            SubjectConfirmationData data = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
            data.setRecipient(acsUrl);
            data.setNotOnOrAfter(notOnOrAfter);
            data.setInResponseTo(inResponseTo);
            confirmation.setSubjectConfirmationData(data);
            subject.getSubjectConfirmations().add(confirmation);
            return subject;
        }

        private Conditions conditions(String spEntityId, Instant notOnOrAfter) {
            Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
            conditions.setNotBefore(Instant.now().minusSeconds(60));
            conditions.setNotOnOrAfter(notOnOrAfter);
            AudienceRestriction restriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
            Audience audience = build(Audience.DEFAULT_ELEMENT_NAME);
            audience.setURI(spEntityId);
            restriction.getAudiences().add(audience);
            conditions.getAudienceRestrictions().add(restriction);
            return conditions;
        }

        private AttributeStatement attributeStatement() {
            AttributeStatement statement = build(AttributeStatement.DEFAULT_ELEMENT_NAME);
            statement.getAttributes().add(attribute("username", "zhangsan"));
            statement.getAttributes().add(attribute("email", "zhangsan@corp.example.com"));
            statement.getAttributes().add(attribute("displayName", "张三"));
            statement.getAttributes().add(attribute("groups", "iam-admins", "iam-users"));
            return statement;
        }

        private Attribute attribute(String name, String... values) {
            Attribute result = build(Attribute.DEFAULT_ELEMENT_NAME);
            result.setName(name);
            for (String value : values) {
                AttributeValue element = build(AttributeValue.DEFAULT_ELEMENT_NAME);
                element.setTextContent(value);
                result.getAttributeValues().add(element);
            }
            return result;
        }

        private void sign(Assertion assertion) {
            SignatureSigningParameters parameters = new SignatureSigningParameters();
            parameters.setSigningCredential(new BasicCredential(certificate.getPublicKey(), privateKey));
            parameters.setSignatureAlgorithm(SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);
            parameters.setSignatureCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
            try {
                // 先编组再签:签名引用的是 DOM 里的 ID,没有 DOM 就取不到引用
                XMLObjectSupport.marshall(assertion);
                SignatureSupport.signObject(assertion, parameters);
            } catch (Exception e) {
                throw new IllegalStateException("测试断言签名失败", e);
            }
        }

        private static Status successStatus() {
            Status status = build(Status.DEFAULT_ELEMENT_NAME);
            StatusCode code = build(StatusCode.DEFAULT_ELEMENT_NAME);
            code.setValue(StatusCode.SUCCESS);
            status.setStatusCode(code);
            return status;
        }

        private static Issuer issuer(String entityId) {
            Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
            issuer.setValue(entityId);
            return issuer;
        }

        @SuppressWarnings("unchecked")
        private static <T extends XMLObject> T build(QName name) {
            return (T) XMLObjectSupport.buildXMLObject(name);
        }

        private static String serialize(XMLObject xmlObject) {
            try {
                Transformer transformer = TransformerFactory.newInstance().newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                Element element = XMLObjectSupport.marshall(xmlObject);
                StringWriter writer = new StringWriter();
                transformer.transform(new DOMSource(element), new StreamResult(writer));
                return writer.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
