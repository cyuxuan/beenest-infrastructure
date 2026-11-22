package club.beenest.iam.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 机密值的信封加密。
 * <p>主密钥经 PBKDF2-HMAC-SHA256 派生 AES-256-GCM 密钥,每条记录独立随机盐,
 * 认证标签绑定"用途 + 作用域"。存储格式为 {@code enc:v1:<base64url(salt|iv|ciphertext)>};
 * 无前缀的值视为历史明文,读取时原样返回,写入时一律加密。</p>
 */
@Component
public class IamSecretCrypto {

    /** 密文前缀:标识存储格式与算法版本 */
    public static final String PREFIX = "enc:v1:";

    /** PBKDF2 迭代次数:兼顾派生强度与启动时延 */
    private static final int PBKDF2_ITERATIONS = 210_000;
    /** PBKDF2 派生密钥长度(位) */
    private static final int KEY_BITS = 256;
    /** 盐长度(字节) */
    private static final int SALT_LENGTH = 16;
    /** GCM 初始向量长度(字节) */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度(位) */
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    /** 主密钥:由部署侧通过环境变量注入,不落在任何配置文件与数据库中 */
    private final String masterSecret;

    /**
     * 创建信封加密组件。
     *
     * @param masterSecret 主密钥,由部署侧通过环境变量注入
     * @throws IllegalStateException 未配置主密钥
     */
    public IamSecretCrypto(@Value("${iam.realm-key.master-secret:}") String masterSecret) {
        if (masterSecret == null || masterSecret.isBlank()) {
            // 主密钥缺失时机密只能明文落库,与本类的存在意义相悖;
            // 直接让进程启动失败,好过带着"看似加密实则明文"的假象上线
            throw new IllegalStateException(
                    "未配置机密加密主密钥(iam.realm-key.master-secret / 环境变量 IAM_REALM_KEY_SECRET)。"
                            + "签名私钥与身份提供方密钥必须加密落库,请先配置该变量再启动;可用 `openssl rand -base64 32` 生成。");
        }
        this.masterSecret = masterSecret;
    }

    /**
     * 加密机密值。
     *
     * @param purpose   用途标识(如 iam-realm-key / iam-idp-config),参与认证标签
     * @param scope     作用域标识(如域主键),参与认证标签
     * @param plaintext 明文
     * @return 可直接落库的密文串
     */
    public String encrypt(String purpose, String scope, String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] salt = new byte[SALT_LENGTH];
            secureRandom.nextBytes(salt);
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            SecretKeySpec key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aadOf(purpose, scope));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[salt.length + iv.length + ciphertext.length];
            System.arraycopy(salt, 0, payload, 0, salt.length);
            System.arraycopy(iv, 0, payload, salt.length, iv.length);
            System.arraycopy(ciphertext, 0, payload, salt.length + iv.length, ciphertext.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("机密加密失败: purpose=" + purpose + ", scope=" + scope, e);
        }
    }

    /**
     * 解密机密值。
     * <p>历史明文(无 {@link #PREFIX} 前缀)原样返回,便于存量数据平滑过渡:
     * 读取不受影响,下次写入时自动变为密文。</p>
     *
     * @param purpose 用途标识
     * @param scope   作用域标识
     * @param stored  落库值(密文或历史明文)
     * @return 明文
     */
    public String decrypt(String purpose, String scope, String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length < SALT_LENGTH + IV_LENGTH + 1) {
                throw new IllegalStateException("机密密文长度非法: purpose=" + purpose);
            }
            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, salt, 0, salt.length);
            System.arraycopy(payload, salt.length, iv, 0, iv.length);
            byte[] ciphertext = new byte[payload.length - salt.length - iv.length];
            System.arraycopy(payload, salt.length + iv.length, ciphertext, 0, ciphertext.length);
            SecretKeySpec key = deriveKey(salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aadOf(purpose, scope));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 解不开只有两种可能:主密钥被换过,或密文被篡改。
            // 两种都属于"必须让运维立刻知道"的状态,不能降级为明文返回
            throw new IllegalStateException(
                    "机密解密失败: purpose=" + purpose + ", scope=" + scope
                            + "。请确认主密钥(IAM_REALM_KEY_SECRET)与机密落库时一致,且密文未被篡改。", e);
        }
    }

    /**
     * 判断落库值是否为密文,用于存量明文的惰性升级。
     *
     * @param stored 落库值
     * @return true 表示已加密
     */
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * 由主密钥与随机盐派生 AES 密钥。
     *
     * @param salt 随机盐
     * @return AES 密钥
     * @throws GeneralSecurityException 密钥派生失败
     */
    private SecretKeySpec deriveKey(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(masterSecret.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 构造认证标签数据,绑定用途与作用域。
     *
     * @param purpose 用途标识
     * @param scope   作用域标识
     * @return AAD 字节
     */
    private static byte[] aadOf(String purpose, String scope) {
        return (purpose + ":" + scope).getBytes(StandardCharsets.UTF_8);
    }
}
