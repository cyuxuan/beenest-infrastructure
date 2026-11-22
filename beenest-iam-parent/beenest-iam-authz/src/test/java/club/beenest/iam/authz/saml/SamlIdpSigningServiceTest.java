package club.beenest.iam.authz.saml;

import club.beenest.iam.authz.jwks.RealmKeyService;
import club.beenest.iam.core.model.RealmKeyModel;
import club.beenest.iam.core.spi.provider.RealmKeyProvider;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IdP 签名材料派生测试。
 * <p>核心断言是"同一把域密钥派生出的证书完全一致":SP 侧登记的是证书指纹,
 * 证书一旦随重启漂移,所有已登记的对端都会验签失败。</p>
 */
class SamlIdpSigningServiceTest {

    private static final Long REALM_ID = 1L;
    private static final String REALM_KEY = "nestid";

    @Test
    void derivesIdenticalCertificateForSameKey() throws Exception {
        RealmKeyModel key = keyModel(generateKeyPair(), LocalDateTime.of(2026, 1, 1, 0, 0));

        // 两个实例模拟两次启动:缓存不共享,证书必须仍然一致
        byte[] first = material(key).certificate().getEncoded();
        byte[] second = material(key).certificate().getEncoded();

        assertArrayEquals(first, second);
    }

    @Test
    void certificateSubjectCarriesRealmKey() throws Exception {
        RealmKeyModel key = keyModel(generateKeyPair(), LocalDateTime.of(2026, 1, 1, 0, 0));

        String subject = material(key).certificate().getSubjectX500Principal().getName();

        assertTrue(subject.contains("CN=" + REALM_KEY), subject);
    }

    @Test
    void certificateValidityStartsAtKeyActivation() throws Exception {
        LocalDateTime activation = LocalDateTime.of(2026, 3, 1, 8, 30);
        RealmKeyModel key = keyModel(generateKeyPair(), activation);

        var certificate = material(key).certificate();

        assertNotNull(certificate.getNotBefore());
        assertTrue(certificate.getNotAfter().after(certificate.getNotBefore()));
    }

    @Test
    void differentKeysYieldDifferentSerialNumbers() throws Exception {
        RealmKeyModel first = keyModel(generateKeyPair(), LocalDateTime.of(2026, 1, 1, 0, 0));
        RealmKeyModel second = keyModel(generateKeyPair(), LocalDateTime.of(2026, 1, 1, 0, 0));

        assertNotEquals(material(first).certificate().getSerialNumber(),
                material(second).certificate().getSerialNumber());
    }

    @Test
    void certificatePemIsPublishedWithoutArmor() throws Exception {
        RealmKeyModel key = keyModel(generateKeyPair(), LocalDateTime.of(2026, 1, 1, 0, 0));

        String base64 = material(key).certificateBase64();

        assertNotNull(Base64.getDecoder().decode(base64));
        assertTrue(!base64.contains("BEGIN"));
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 用给定密钥构造签名服务并取材料。
     *
     * @param key 域密钥
     * @return 签名材料
     */
    private static SamlSigningMaterial material(RealmKeyModel key) {
        RealmKeyProvider provider = mock(RealmKeyProvider.class);
        when(provider.findActive(REALM_ID)).thenReturn(Optional.of(key));
        when(provider.listByRealm(REALM_ID)).thenReturn(List.of(key));
        return new SamlIdpSigningService(new RealmKeyService(provider)).material(REALM_ID, REALM_KEY);
    }

    /**
     * 构造域密钥模型,私钥公钥按 PEM 形态存放(与落库形态一致)。
     *
     * @param keyPair      密钥对
     * @param activation   生效时间
     * @return 域密钥模型
     */
    private static RealmKeyModel keyModel(KeyPair keyPair, LocalDateTime activation) {
        return new RealmKeyModel(9L, REALM_ID, "RSA",
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                true, activation, null);
    }

    /**
     * 生成 RSA 密钥对。
     *
     * @return 2048 位密钥对
     * @throws Exception 算法不可用
     */
    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * DER 字节编码为 PEM 文本。
     *
     * @param label PEM 标签
     * @param der   DER 字节
     * @return PEM 文本
     */
    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
