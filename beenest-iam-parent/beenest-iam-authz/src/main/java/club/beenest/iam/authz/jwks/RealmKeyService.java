package club.beenest.iam.authz.jwks;

import club.beenest.iam.core.model.RealmKeyModel;
import club.beenest.iam.core.spi.provider.RealmKeyProvider;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 域签名密钥服务。
 * <p>每域一把 RSA 2048 密钥;首次访问时生成并落库,后续直接复用。
 * 私钥以信封加密形态落库(见 {@code IamRealmKeyCrypto}),明文私钥不进入数据库;
 * 本类只负责生成与解析,加解密在持久层完成。</p>
 */
@Component
public class RealmKeyService {

    private final RealmKeyProvider realmKeyProvider;

    public RealmKeyService(RealmKeyProvider realmKeyProvider) {
        this.realmKeyProvider = realmKeyProvider;
    }

    /**
     * 当前生效密钥;不存在则生成并保存。
     *
     * @param realmId 身份域主键
     * @return 该域当前生效的签名密钥
     */
    public RealmKeyModel getOrCreateActiveKey(Long realmId) {
        Optional<RealmKeyModel> active = realmKeyProvider.findActive(realmId);
        if (active.isPresent()) {
            return active.get();
        }
        // 首次访问:生成 RSA 2048 密钥对
        RealmKeyModel generated = generateKey(realmId);
        realmKeyProvider.save(generated);
        return generated;
    }

    /**
     * 域内全部密钥,含轮换期旧密钥(JWKS 同时发布新旧公钥)。
     *
     * @param realmId 身份域主键
     * @return 该域当前生效密钥与退役密钥列表
     */
    public List<RealmKeyModel> listKeys(Long realmId) {
        return realmKeyProvider.listByRealm(realmId);
    }

    /**
     * 轮换域签名密钥:退役旧密钥并生成新密钥。
     * <p>旧密钥行保留(active=FALSE + 退役时间),JWKS 继续发布其公钥,
     * 已签发令牌在自然过期前仍可验签;新密钥立即生效,后续签发全部改用新私钥。
     * 非幂等:每次调用都产生一把新密钥,属一次性运维动作。</p>
     *
     * @param realmId 身份域主键
     * @return 新生效的密钥
     */
    public RealmKeyModel rotateKey(Long realmId) {
        // 1. 退役域内现有生效密钥(旧密钥保留供验签过渡)
        realmKeyProvider.deactivateByRealm(realmId);
        // 2. 生成并落库新密钥
        RealmKeyModel generated = generateKey(realmId);
        realmKeyProvider.save(generated);
        return generated;
    }

    /**
     * 解析公钥 PEM(X509 编码)。
     *
     * @param pem 公钥 PEM 文本
     * @return RSA 公钥
     * @throws IllegalStateException PEM 内容非法或算法不可用
     */
    public RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] encoded = pemDecode(pem);
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            return (RSAPublicKey) key;
        } catch (Exception e) {
            throw new IllegalStateException("公钥解析失败", e);
        }
    }

    /**
     * 解析私钥 PEM(PKCS8 编码)。
     *
     * @param pem 私钥 PEM 文本
     * @return RSA 私钥
     * @throws IllegalStateException PEM 内容非法或算法不可用
     */
    public RSAPrivateKey parsePrivateKey(String pem) {
        try {
            byte[] encoded = pemDecode(pem);
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            return (RSAPrivateKey) key;
        } catch (Exception e) {
            throw new IllegalStateException("私钥解析失败", e);
        }
    }

    /**
     * 生成新的 RSA 2048 密钥对模型(未落库)。
     *
     * @param realmId 身份域主键
     * @return 生效状态的密钥模型,私钥以 PEM 文本存放
     * @throws IllegalStateException 密钥生成失败或算法不可用
     */
    private RealmKeyModel generateKey(Long realmId) {
        try {
            com.nimbusds.jose.jwk.RSAKey rsaKey = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048)
                    .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE)
                    .keyID("rk-" + realmId + "-" + System.currentTimeMillis())
                    .generate();
            RSAPrivateKey privateKey = rsaKey.toRSAPrivateKey();
            RSAPublicKey publicKey = rsaKey.toRSAPublicKey();
            return new RealmKeyModel(
                    null, realmId, "RSA",
                    encodePem("PRIVATE KEY", privateKey.getEncoded()),
                    encodePem("PUBLIC KEY", publicKey.getEncoded()),
                    true, LocalDateTime.now(), null);
        } catch (Exception e) {
            throw new IllegalStateException("RSA 密钥生成失败", e);
        }
    }

    /**
     * PEM 文本解为 DER 字节,去除 armor 行与空白。
     *
     * @param pem PEM 文本
     * @return DER 字节
     */
    private static byte[] pemDecode(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /**
     * DER 字节编码为 PEM 文本。
     *
     * @param label PEM armor 标签(PRIVATE KEY / PUBLIC KEY)
     * @param der   DER 字节
     * @return 带 armor 行的 PEM 文本
     */
    private static String encodePem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }
}
