package club.beenest.iam.authz.saml;

import club.beenest.iam.common.exception.BusinessException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SAML 身份提供方报文编解码测试。
 * <p>核心用例是往返:本类签发的断言交给 Spring Security 的服务方断言校验器验回来。
 * 断言校验器是独立实现,能通过它才算真的签对了——只比对字符串无法发现
 * 规范化、命名空间或签名覆盖范围上的错误。</p>
 */
class SamlIdpProtocolTest {

    private static final String IDP_ENTITY_ID = "https://iam.example.com/realms/nestid/saml/metadata";
    private static final String SSO_URL = "https://iam.example.com/realms/nestid/saml/sso";
    private static final String SLO_URL = "https://iam.example.com/realms/nestid/saml/slo";
    private static final String SP_ENTITY_ID = "https://sp.example.com/metadata";
    private static final String ACS = "https://sp.example.com/acs";
    private static final String NAME_ID = "zhangsan";
    private static final String REQUEST_ID = "_req-0001";

    private static final TestKeys IDP_KEYS = new TestKeys("CN=beenest-test-idp");

    // ------------------------------------------------------------------ 签发

    @Test
    void issuedAssertionIsAcceptedByServiceProviderValidator() {
        // Saml2AuthenticationToken 收的是解码后的 XML 报文,不是 Base64
        String response = SamlIdpProtocol.buildResponse(assertionSpec(true), material());

        Authentication authentication = validate(response);

        assertThat(authentication).isInstanceOf(Saml2AssertionAuthentication.class);
        Saml2AssertionAuthentication result = (Saml2AssertionAuthentication) authentication;
        assertThat(result.getName()).isEqualTo(NAME_ID);
        // getFirstAttribute 是泛型方法,显式转成 String 才能落到 assertThat(String)
        assertThat((String) result.getCredentials().getFirstAttribute("email")).isEqualTo("zhangsan@corp.example.com");
        assertThat((String) result.getCredentials().getFirstAttribute("displayName")).isEqualTo("张三");
        // 多值属性:roles 下发两个值,SP 侧应按顺序拿到两个
        assertThat(result.getCredentials().getAttributes().get("roles"))
                .containsExactly("ADMIN", "USER");
    }

    @Test
    void unsignedAssertionIsRejectedByServiceProviderValidator() {
        String response = SamlIdpProtocol.buildResponse(assertionSpec(false), material());

        // 断言未签名时,服务方校验器必须拒绝:没有签名就没有任何来源证明
        assertThatThrownBy(() -> validate(response)).isInstanceOf(Exception.class);
    }

    @Test
    void tamperedAssertionFailsSignatureVerification() {
        String xml = SamlIdpProtocol.buildResponse(assertionSpec(true), material());
        // 改掉 NameID:签名覆盖断言全文,任何字段改动都会让验签失败
        String tampered = xml.replace(NAME_ID, "attacker");
        assertThat(tampered).isNotEqualTo(xml);

        assertThatThrownBy(() -> validate(tampered)).isInstanceOf(Exception.class);
    }

    @Test
    void responseCarriesDestinationAudienceAndInResponseTo() {
        String xml = SamlIdpProtocol.buildResponse(assertionSpec(true), material());

        assertThat(xml)
                .contains("Destination=\"" + ACS + "\"")
                .contains("InResponseTo=\"" + REQUEST_ID + "\"")
                .contains("<saml2:Audience>" + SP_ENTITY_ID + "</saml2:Audience>")
                .contains("Recipient=\"" + ACS + "\"")
                .contains("SessionIndex=\"" + "session-abc" + "\"")
                .contains("StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"");
    }

    @Test
    void assertionOmitsSignatureWhenSigningDisabled() {
        String xml = SamlIdpProtocol.buildResponse(assertionSpec(false), material());

        assertThat(xml).doesNotContain("<ds:Signature");
    }

    // ------------------------------------------------------------------ 元数据

    @Test
    void metadataPublishesCertificateAndBothSsoBindings() {
        String metadata = SamlIdpProtocol.buildMetadata(IDP_ENTITY_ID, SSO_URL, SLO_URL, IDP_KEYS.certificate);

        assertThat(metadata)
                .contains("entityID=\"" + IDP_ENTITY_ID + "\"")
                .contains("Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\"")
                .contains("Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"")
                .contains("Location=\"" + SSO_URL + "\"")
                .contains("Location=\"" + SLO_URL + "\"")
                .contains("<ds:X509Certificate>");
        assertThat(metadata).contains(Base64.getEncoder().encodeToString(certificateBytes()));
    }

    @Test
    void metadataOmitsLogoutServiceWhenNotConfigured() {
        String metadata = SamlIdpProtocol.buildMetadata(IDP_ENTITY_ID, SSO_URL, null, IDP_KEYS.certificate);

        assertThat(metadata).doesNotContain("SingleLogoutService");
    }

    // ------------------------------------------------------------------ 解析

    @Test
    void parsesDeflatedAuthnRequest() {
        SamlIdpAuthnRequest request = SamlIdpProtocol.parseAuthnRequest(
                SamlIdpProtocol.deflateAndBase64(authnRequestXml()), "relay-token");

        assertThat(request.id()).isEqualTo(REQUEST_ID);
        assertThat(request.issuer()).isEqualTo(SP_ENTITY_ID);
        assertThat(request.destination()).isEqualTo(SSO_URL);
        assertThat(request.assertionConsumerServiceUrl()).isEqualTo(ACS);
        assertThat(request.nameIdFormat()).isEqualTo("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress");
        assertThat(request.forceAuthn()).isTrue();
        assertThat(request.relayState()).isEqualTo("relay-token");
        assertThat(request.signed()).isFalse();
    }

    @Test
    void parsesPlainBase64AuthnRequest() {
        // HTTP-POST 绑定是纯 Base64,不带 deflate;两种绑定都要能接
        SamlIdpAuthnRequest request = SamlIdpProtocol.parseAuthnRequest(
                SamlIdpProtocol.base64(authnRequestXml()), null);

        assertThat(request.id()).isEqualTo(REQUEST_ID);
        assertThat(request.relayState()).isNull();
    }

    @Test
    void rejectsMessageThatIsNotXml() {
        String garbage = Base64.getEncoder().encodeToString("not-xml".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> SamlIdpProtocol.parseAuthnRequest(garbage, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMessageOfWrongType() {
        String logoutRequest = """
                <samlp:LogoutRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
                    ID="_logout-1" Version="2.0" IssueInstant="2026-01-01T00:00:00Z">
                  <saml:Issuer>https://sp.example.com/metadata</saml:Issuer>
                  <saml:NameID>zhangsan</saml:NameID>
                </samlp:LogoutRequest>
                """;

        assertThatThrownBy(() -> SamlIdpProtocol.parseAuthnRequest(SamlIdpProtocol.base64(logoutRequest), null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void parsesLogoutRequest() {
        String logoutRequest = """
                <samlp:LogoutRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
                    ID="_logout-1" Version="2.0" IssueInstant="2026-01-01T00:00:00Z">
                  <saml:Issuer>https://sp.example.com/metadata</saml:Issuer>
                  <saml:NameID>zhangsan</saml:NameID>
                  <samlp:SessionIndex>session-abc</samlp:SessionIndex>
                </samlp:LogoutRequest>
                """;

        SamlIdpLogoutRequest request = SamlIdpProtocol.parseLogoutRequest(SamlIdpProtocol.deflateAndBase64(logoutRequest));

        assertThat(request.id()).isEqualTo("_logout-1");
        assertThat(request.issuer()).isEqualTo(SP_ENTITY_ID);
        assertThat(request.nameId()).isEqualTo(NAME_ID);
        assertThat(request.sessionIndex()).isEqualTo("session-abc");
    }

    // ------------------------------------------------------------------ 登出响应

    @Test
    void logoutResponseCarriesSuccessStatusAndInResponseTo() {
        String xml = SamlIdpProtocol.buildLogoutResponse(IDP_ENTITY_ID, SLO_URL, "_logout-1", material());

        assertThat(xml)
                .contains("Destination=\"" + SLO_URL + "\"")
                .contains("InResponseTo=\"_logout-1\"")
                .contains("StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"")
                .contains("<ds:Signature");
    }

    // ------------------------------------------------------------------ 查询串验签

    @Test
    void verifiesRedirectBindingSignature() throws Exception {
        String query = signedQuery();

        assertThat(SamlIdpProtocol.verifyRedirectSignature(query, IDP_KEYS.certificate)).isTrue();
    }

    @Test
    void rejectsRedirectSignatureOverDifferentPayload() throws Exception {
        String query = signedQuery();

        // 改掉 RelayState:签名覆盖整段查询串,改一个字符即失效
        String tampered = query.replace("RelayState=relay-token", "RelayState=other-token");
        assertThat(SamlIdpProtocol.verifyRedirectSignature(tampered, IDP_KEYS.certificate)).isFalse();
    }

    @Test
    void rejectsRedirectSignatureWhenAlgorithmUnknown() {
        String query = "SAMLRequest=abc&RelayState=r&SigAlg="
                + URLEncoder.encode("urn:example:unknown-alg", StandardCharsets.UTF_8)
                + "&Signature=" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        assertThat(SamlIdpProtocol.verifyRedirectSignature(query, IDP_KEYS.certificate)).isFalse();
    }

    @Test
    void rejectsRedirectSignatureWhenSignatureMissing() {
        assertThat(SamlIdpProtocol.verifyRedirectSignature("SAMLRequest=abc&RelayState=r",
                IDP_KEYS.certificate)).isFalse();
    }

    @Test
    void parsesCertificateFromPemWithOrWithoutArmor() {
        String armored = IDP_KEYS.certificatePem();
        String bare = armored.replaceAll("-----(BEGIN|END) CERTIFICATE-----", "");

        assertThat(SamlIdpProtocol.certificateFromPem(armored).getSerialNumber())
                .isEqualTo(SamlIdpProtocol.certificateFromPem(bare).getSerialNumber());
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 交给 Spring Security 的服务方断言校验器验一遍。
     *
     * @param samlResponse Base64 编码的 SAMLResponse
     * @return 校验通过后的认证结果
     */
    private static Authentication validate(String samlResponse) {
        RelyingPartyRegistration registration = RelyingPartyRegistration
                .withRegistrationId("corp-saml")
                .entityId(SP_ENTITY_ID)
                .assertionConsumerServiceLocation(ACS)
                .assertionConsumerServiceBinding(Saml2MessageBinding.POST)
                .nameIdFormat("urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified")
                .assertingPartyMetadata(details -> details
                        .entityId(IDP_ENTITY_ID)
                        .singleSignOnServiceLocation(SSO_URL)
                        .singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)
                        .verificationX509Credentials(credentials ->
                                credentials.add(Saml2X509Credential.verification(IDP_KEYS.certificate))))
                .build();
        // 还原发起时的请求:InResponseTo 校验以它的 ID 为准
        Saml2RedirectAuthenticationRequest storedRequest = Saml2RedirectAuthenticationRequest
                .withRelyingPartyRegistration(registration)
                .id(REQUEST_ID)
                .samlRequest("-")
                .relayState("relay-token")
                .authenticationRequestUri(SSO_URL)
                .build();
        return new OpenSaml5AuthenticationProvider()
                .authenticate(new Saml2AuthenticationToken(registration, samlResponse, storedRequest));
    }

    private static SamlIdpAssertionSpec assertionSpec(boolean signAssertion) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        attributes.put("email", List.of("zhangsan@corp.example.com"));
        attributes.put("displayName", List.of("张三"));
        attributes.put("roles", List.of("ADMIN", "USER"));
        return new SamlIdpAssertionSpec(IDP_ENTITY_ID, SP_ENTITY_ID, ACS, REQUEST_ID, NAME_ID,
                "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified", "session-abc",
                Instant.now().plusSeconds(3600), attributes, signAssertion);
    }

    private static SamlSigningMaterial material() {
        return new SamlSigningMaterial(IDP_KEYS.privateKey, IDP_KEYS.certificate);
    }

    private static byte[] certificateBytes() {
        try {
            return IDP_KEYS.certificate.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 造一段带签名的 HTTP-Redirect 查询串。
     *
     * @return 形如 SAMLRequest=...&RelayState=...&SigAlg=...&Signature=... 的查询串
     * @throws Exception 签名失败
     */
    private static String signedQuery() throws Exception {
        String signed = "SAMLRequest=" + URLEncoder.encode("abc", StandardCharsets.UTF_8)
                + "&RelayState=" + URLEncoder.encode("relay-token", StandardCharsets.UTF_8)
                + "&SigAlg=" + URLEncoder.encode("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256",
                StandardCharsets.UTF_8);
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(IDP_KEYS.privateKey);
        signer.update(signed.getBytes(StandardCharsets.UTF_8));
        return signed + "&Signature=" + URLEncoder.encode(
                Base64.getEncoder().encodeToString(signer.sign()), StandardCharsets.UTF_8);
    }

    private static String authnRequestXml() {
        return """
                <samlp:AuthnRequest xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
                    ID="_req-0001" Version="2.0" IssueInstant="2026-01-01T00:00:00Z"
                    Destination="https://iam.example.com/realms/nestid/saml/sso"
                    AssertionConsumerServiceURL="https://sp.example.com/acs"
                    ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
                    ForceAuthn="true">
                  <saml:Issuer>https://sp.example.com/metadata</saml:Issuer>
                  <samlp:NameIDPolicy
                      Format="urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"
                      AllowCreate="true"/>
                </samlp:AuthnRequest>
                """;
    }

    /**
     * 测试用密钥对与自签证书。
     */
    private static final class TestKeys {

        private final X509Certificate certificate;
        private final PrivateKey privateKey;

        TestKeys(String subject) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                X500Name name = new X500Name(subject);
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
    }
}
