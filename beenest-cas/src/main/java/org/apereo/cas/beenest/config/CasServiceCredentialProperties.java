package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * CAS 服务凭证配置。
 * <p>
 * 用于业务系统 secret 的加密存储与后续签名校验。
 * 默认值仅用于本地开发，生产环境应通过环境变量覆盖。
 */
@Data
@ConfigurationProperties(prefix = "beenest.service-credential")
public class CasServiceCredentialProperties {

    /**
     * 服务凭证加密密钥。
     * <p>
     * 需要是 Base64 编码的 32 字节 AES 密钥。
     */
    private String encryptionKey = defaultEncryptionKey();

    /**
     * 请求允许的时间偏移窗口，单位秒。
     */
    private long allowedClockSkewSeconds = 300;

    /**
     * nonce 缓存 TTL，单位秒。
     */
    private long nonceTtlSeconds = 300;

    private static String defaultEncryptionKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest("beenest-service-credential-encryption-key".getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成默认服务凭证加密密钥", e);
        }
    }
}
