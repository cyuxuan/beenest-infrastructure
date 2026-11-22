package club.beenest.iam.authz.saml;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * IdP 签发侧的签名材料。
 * <p>私钥用于给断言与登出响应签名,证书随元数据发布,供 SP 验签。
 * 两者必须成对:证书换了而私钥没换,SP 会认为签名无效。</p>
 *
 * @param privateKey  签名私钥(RSA)
 * @param certificate 对应的 X.509 证书
 */
public record SamlSigningMaterial(PrivateKey privateKey, X509Certificate certificate) {

    /**
     * 证书的 Base64 正文(不含 PEM 头尾)。
     * <p>SAML 元数据的 {@code ds:X509Certificate} 要求这一形态。</p>
     *
     * @return Base64 编码的证书 DER
     * @throws IllegalStateException 证书编码失败
     */
    public String certificateBase64() {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("SAML 签名证书编码失败", e);
        }
    }
}
