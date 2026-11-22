package club.beenest.iam.authz.saml;

import club.beenest.iam.authz.jwks.RealmKeyService;
import club.beenest.iam.core.model.RealmKeyModel;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IdP 签名材料来源:从域签名密钥派生断言签名用的私钥与自签证书。
 * <p>域密钥只存密钥对,而 SAML 元数据必须发布 X.509 证书,因此这里按域密钥
 * 现造一张自签证书。构造参数全部取自密钥本身(序列号由公钥摘要得出、有效期从密钥
 * 生效时间起算),RSA PKCS#1 v1.5 签名又是确定性的,所以同一把密钥每次都得到
 * 完全相同的证书 DER:SP 侧登记一次即可长期有效,重启与多实例部署都不会漂移。
 * 密钥轮换后自然得到新证书,旧证书随之失效。</p>
 */
@Component
public class SamlIdpSigningService {

    /** 自签证书有效期:与域密钥同生命周期,轮换密钥即换证书 */
    private static final Duration CERTIFICATE_VALIDITY = Duration.ofDays(3650);

    /** 密钥行没有生效时间时的兜底起算点(存量数据) */
    private static final LocalDateTime FALLBACK_ACTIVATION = LocalDateTime.of(2020, 1, 1, 0, 0);

    /** 证书主题中的组织名 */
    private static final String ORGANIZATION = "O=Beenest IAM";

    private final RealmKeyService realmKeyService;

    /** 按密钥主键缓存:密钥轮换后主键变化,旧条目自然不再命中 */
    private final Map<Long, SamlSigningMaterial> cache = new ConcurrentHashMap<>();

    public SamlIdpSigningService(RealmKeyService realmKeyService) {
        this.realmKeyService = realmKeyService;
    }

    /**
     * 取该域当前生效密钥对应的签名材料。
     *
     * @param realmId  身份域主键
     * @param realmKey 身份域标识,写入证书主题
     * @return 私钥与自签证书
     * @throws IllegalStateException 密钥缺失或证书生成失败
     */
    public SamlSigningMaterial material(Long realmId, String realmKey) {
        RealmKeyModel key = realmKeyService.getOrCreateActiveKey(realmId);
        return cache.computeIfAbsent(key.id(), id -> derive(key, realmKey));
    }

    /**
     * 由域密钥派生签名材料。
     *
     * @param key      域密钥
     * @param realmKey 身份域标识
     * @return 签名材料
     * @throws IllegalStateException 密钥解析或证书生成失败
     */
    private SamlSigningMaterial derive(RealmKeyModel key, String realmKey) {
        RSAPrivateKey privateKey = realmKeyService.parsePrivateKey(key.privateKey());
        RSAPublicKey publicKey = realmKeyService.parsePublicKey(key.publicKey());
        LocalDateTime activation = key.activatedTime() == null ? FALLBACK_ACTIVATION : key.activatedTime();
        return new SamlSigningMaterial(privateKey, selfSign(realmKey, publicKey, privateKey, activation));
    }

    /**
     * 生成自签证书。
     *
     * @param realmKey   身份域标识,用于主题 CN
     * @param publicKey  公钥
     * @param privateKey 私钥
     * @param activation 生效时间
     * @return 自签证书
     * @throws IllegalStateException 证书构造失败
     */
    private static X509Certificate selfSign(String realmKey, PublicKey publicKey, PrivateKey privateKey,
                                            LocalDateTime activation) {
        try {
            X500Name subject = new X500Name("CN=" + commonName(realmKey) + ", " + ORGANIZATION);
            ZoneId zone = ZoneId.systemDefault();
            Date notBefore = Date.from(activation.atZone(zone).toInstant());
            Date notAfter = Date.from(activation.plus(CERTIFICATE_VALIDITY).atZone(zone).toInstant());
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject, serialOf(publicKey), notBefore, notAfter, subject, publicKey);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(privateKey);
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("SAML 签名证书生成失败", e);
        }
    }

    /**
     * 序列号取公钥摘要前 8 字节:同一把密钥恒定,换密钥必变。
     *
     * @param publicKey 公钥
     * @return 正整数序列号
     * @throws IllegalStateException 摘要算法不可用
     */
    private static BigInteger serialOf(PublicKey publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            BigInteger serial = new BigInteger(1, Arrays.copyOf(digest, 8));
            return serial.signum() == 0 ? BigInteger.ONE : serial;
        } catch (Exception e) {
            throw new IllegalStateException("SAML 证书序列号计算失败", e);
        }
    }

    /**
     * 主题 CN:域标识只保留安全字符,避免 DN 中出现需要转义的字符。
     * <p>CN 仅用于人读,证书的可信性由元数据发布保证,不做转义不影响安全。</p>
     *
     * @param realmKey 身份域标识
     * @return 规整后的 CN 值
     */
    private static String commonName(String realmKey) {
        String sanitized = realmKey == null ? "" : realmKey.replaceAll("[^A-Za-z0-9._-]", "-");
        return sanitized.isBlank() ? "iam" : sanitized;
    }
}
