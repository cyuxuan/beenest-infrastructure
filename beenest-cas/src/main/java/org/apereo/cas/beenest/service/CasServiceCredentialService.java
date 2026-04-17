package org.apereo.cas.beenest.service;

import org.apereo.cas.beenest.config.CasServiceCredentialProperties;
import org.apereo.cas.beenest.util.AesEncryptionUtils;
import org.apereo.cas.beenest.entity.CasServiceCredentialDO;
import org.apereo.cas.beenest.mapper.CasServiceCredentialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * CAS 服务凭证管理服务。
 * <p>
 * 负责生成服务 secret、保存哈希值与盐值，并提供按服务查询与状态更新能力。
 */
@Slf4j
@RequiredArgsConstructor
public class CasServiceCredentialService {

    private static final String DEFAULT_STATE = "ACTIVE";
    private static final HexFormat HEX = HexFormat.of();

    private final CasServiceCredentialMapper credentialMapper;
    private final CasServiceCredentialProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建并持久化一个新的服务凭证。
     *
     * @param serviceId CAS registered service ID
     * @return 包含一次性明文 secret 的发行结果
     */
    public IssuedCredential issueCredential(Long serviceId) {
        Objects.requireNonNull(serviceId, "serviceId 不能为空");

        String plainSecret = generatePlainSecret();
        String secretSalt = generateSalt();
        String encryptedSecret = encryptSecret(plainSecret);

        CasServiceCredentialDO credential = new CasServiceCredentialDO();
        credential.setServiceId(serviceId);
        credential.setSecretSalt(secretSalt);
        credential.setSecretHash(encryptedSecret);
        credential.setSecretVersion(1L);
        credential.setState(DEFAULT_STATE);
        credentialMapper.insert(credential);

        return new IssuedCredential(plainSecret, credential.getSecretVersion());
    }

    /**
     * 按服务 ID 查询已持久化的凭证。
     *
     * @param serviceId CAS registered service ID
     * @return 服务凭证记录
     */
    public CasServiceCredentialDO getCredential(Long serviceId) {
        Objects.requireNonNull(serviceId, "serviceId 不能为空");
        return credentialMapper.selectByServiceId(serviceId);
    }

    /**
     * 更新服务凭证状态。
     *
     * @param serviceId CAS registered service ID
     * @param state 目标状态
     */
    public void updateState(Long serviceId, String state) {
        Objects.requireNonNull(serviceId, "serviceId 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        credentialMapper.updateStateByServiceId(serviceId, state);
    }

    /**
     * 按服务 ID 解密出当前使用的 secret。
     *
     * @param serviceId CAS registered service ID
     * @return 解密后的 secret
     */
    public String resolvePlainSecret(Long serviceId) {
        CasServiceCredentialDO credential = getCredential(serviceId);
        if (credential == null) {
            return null;
        }
        return decryptSecret(credential.getSecretHash());
    }

    /**
     * 生成一次性明文 secret。
     *
     * @return 随机 secret
     */
    protected String generatePlainSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private String generateSalt() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private String encryptSecret(String plainSecret) {
        String encryptionKey = properties != null ? properties.getEncryptionKey() : null;
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("未配置服务凭证加密密钥");
        }
        return AesEncryptionUtils.encrypt(plainSecret, encryptionKey);
    }

    private String decryptSecret(String encryptedSecret) {
        String encryptionKey = properties != null ? properties.getEncryptionKey() : null;
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("未配置服务凭证加密密钥");
        }
        return AesEncryptionUtils.decrypt(encryptedSecret, encryptionKey);
    }

    /**
     * 服务凭证发行结果。
     *
     * @param plainSecret 一次性返回的明文 secret
     * @param secretVersion 凭证版本号
     */
    public record IssuedCredential(String plainSecret, Long secretVersion) {
    }
}
