package club.beenest.iam.common.security;

import org.springframework.stereotype.Component;

/**
 * 身份域签名私钥的信封加密。
 * <p>使用主密钥对域签名私钥做 AES-256-GCM 信封加密,每条记录独立随机盐,
 * 密文与所属域绑定,跨域搬运无法解密。算法实现委托给 {@link IamSecretCrypto}。</p>
 */
@Component
public class IamRealmKeyCrypto {

    /** 加密用途标识:参与认证标签,不同用途的密文不可互解 */
    private static final String PURPOSE = "iam-realm-key";

    /** 密文前缀:标识存储格式与算法版本 */
    public static final String PREFIX = IamSecretCrypto.PREFIX;

    private final IamSecretCrypto secretCrypto;

    public IamRealmKeyCrypto(IamSecretCrypto secretCrypto) {
        this.secretCrypto = secretCrypto;
    }

    /**
     * 加密私钥。
     *
     * @param realmId   所属身份域(参与认证标签,密文不可跨域搬运)
     * @param plaintext 私钥明文
     * @return 可直接落库的密文串
     */
    public String encrypt(Long realmId, String plaintext) {
        return secretCrypto.encrypt(PURPOSE, String.valueOf(realmId), plaintext);
    }

    /**
     * 解密私钥。
     * <p>历史明文(无 {@link #PREFIX} 前缀)原样返回,便于存量数据平滑过渡:
     * 读取不受影响,下次写入时自动变为密文。</p>
     *
     * @param realmId 所属身份域
     * @param stored  落库值(密文或历史明文)
     * @return 私钥明文
     */
    public String decrypt(Long realmId, String stored) {
        return secretCrypto.decrypt(PURPOSE, String.valueOf(realmId), stored);
    }

    /**
     * 判断落库值是否为密文,用于存量明文的惰性升级。
     *
     * @param stored 落库值
     * @return true 表示已加密
     */
    public boolean isEncrypted(String stored) {
        return secretCrypto.isEncrypted(stored);
    }
}
