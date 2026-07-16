package club.beenest.payment.shared.service;

import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.domain.entity.AppCredential;
import club.beenest.payment.shared.mapper.AppCredentialMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用凭证服务
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>应用凭证查询（带内存缓存，定时刷新）</li>
 *   <li>BCrypt 验证 app_secret / sign_secret</li>
 *   <li>AES-256-GCM 解密 mq_secret（支付中台需用明文签发消息）</li>
 *   <li>凭证管理 CRUD + 密钥轮换</li>
 * </ul>
 *
 * @author System
 * @since 2026-07-16
 */
@Slf4j
@Service
public class AppCredentialService {

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final AppCredentialMapper mapper;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * AES 主密钥，用于加解密 mq_secret
     * 通过环境变量 PAYMENT_MQ_MASTER_KEY 注入
     */
    private final String mqMasterKey;

    /**
     * 内存缓存：appId → AppCredential
     */
    private final ConcurrentHashMap<String, AppCredential> credentialCache = new ConcurrentHashMap<>();

    /**
     * 缓存中所有活跃凭证的快照列表（避免每次查询都遍历 ConcurrentHashMap.values()）
     */
    private volatile List<AppCredential> activeCredentialsSnapshot = List.of();

    public AppCredentialService(AppCredentialMapper mapper,
                                @Value("${payment.mq.master-key:CHANGE_ME}") String mqMasterKey) {
        this.mapper = mapper;
        this.mqMasterKey = mqMasterKey;
        this.passwordEncoder = new BCryptPasswordEncoder(12);
        // 启动时加载一次
        refreshCache();
    }

    // ==================== 查询方法 ====================

    /**
     * 根据 app_id 获取凭证（从缓存读取）
     *
     * @param appId 业务系统标识
     * @return 凭证实体，不存在返回 null
     */
    public AppCredential getByAppId(String appId) {
        return credentialCache.get(appId);
    }

    /**
     * 获取所有活跃凭证
     *
     * @return 活跃凭证列表
     */
    public List<AppCredential> getAllActive() {
        return activeCredentialsSnapshot;
    }

    // ==================== 验证方法 ====================

    /**
     * BCrypt 验证 app_secret
     *
     * @param appId      业务系统标识
     * @param rawSecret  原始密钥（请求头中的值）
     * @return 验证是否通过
     */
    public boolean verifyAppSecret(String appId, String rawSecret) {
        AppCredential credential = getByAppId(appId);
        if (credential == null) {
            return false;
        }
        return passwordEncoder.matches(rawSecret, credential.getAppSecret());
    }

    /**
     * BCrypt 验证 sign_secret
     *
     * @param appId      业务系统标识
     * @param rawSecret  原始密钥
     * @return 验证是否通过
     */
    public boolean verifySignSecret(String appId, String rawSecret) {
        AppCredential credential = getByAppId(appId);
        if (credential == null) {
            return false;
        }
        return passwordEncoder.matches(rawSecret, credential.getSignSecret());
    }

    /**
     * 获取 MQ 明文密钥（AES 解密）
     *
     * @param appId 业务系统标识
     * @return MQ 明文密钥，失败返回 null
     */
    public String getMqSecret(String appId) {
        AppCredential credential = getByAppId(appId);
        if (credential == null) {
            return null;
        }
        return decryptMqSecret(credential.getMqSecret());
    }

    /**
     * 通过 bizType 推导 appId，再获取 MQ 明文密钥
     *
     * @param bizType 业务类型
     * @return MQ 明文密钥，失败返回 null
     */
    public String getMqSecretByBizType(String bizType) {
        String appId = BizTypeConstants.deriveAppId(bizType);
        return getMqSecret(appId);
    }

    // ==================== 管理方法 ====================

    /**
     * 创建应用凭证
     *
     * @param appId         业务系统标识
     * @param appName       应用名称
     * @param allowedNetworks 允许的 IP/CIDR 列表
     * @param description   描述
     * @param operator      操作人
     * @return 创建的凭证实体（包含明文密钥，仅此一次返回）
     */
    public AppCredential createApp(String appId, String appName, String allowedNetworks,
                                   String description, String operator) {
        // 1. 生成密钥
        String rawAppSecret = generateSecureSecret();
        String rawSignSecret = generateSecureSecret();
        String rawMqSecret = generateSecureSecret();

        // 2. BCrypt 哈希 + AES 加密
        String hashedAppSecret = passwordEncoder.encode(rawAppSecret);
        String hashedSignSecret = passwordEncoder.encode(rawSignSecret);
        String encryptedMqSecret = encryptMqSecret(rawMqSecret);

        // 3. 持久化
        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setAppName(appName);
        credential.setAppSecret(hashedAppSecret);
        credential.setSignSecret(hashedSignSecret);
        credential.setMqSecret(encryptedMqSecret);
        credential.setAllowedNetworks(allowedNetworks);
        credential.setStatus("ACTIVE");
        credential.setDescription(description);
        credential.setCreatedBy(operator);
        mapper.insert(credential);

        // 4. 返回包含明文密钥的副本（仅此一次）
        AppCredential result = new AppCredential();
        result.setAppId(appId);
        result.setAppName(appName);
        result.setAppSecret(rawAppSecret);
        result.setSignSecret(rawSignSecret);
        result.setMqSecret(rawMqSecret);
        result.setAllowedNetworks(allowedNetworks);
        result.setStatus("ACTIVE");
        result.setDescription(description);

        // 5. 刷新缓存
        refreshCache();

        log.info("应用凭证创建成功: appId={}, appName={}, operator={}", appId, appName, operator);
        return result;
    }

    /**
     * 更新应用信息（名称、IP白名单、描述）
     *
     * @param appId         业务系统标识
     * @param appName       应用名称（null 不更新）
     * @param allowedNetworks IP 白名单（null 不更新）
     * @param description   描述（null 不更新）
     * @param operator      操作人
     */
    public void updateApp(String appId, String appName, String allowedNetworks,
                          String description, String operator) {
        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setAppName(appName);
        credential.setAllowedNetworks(allowedNetworks);
        credential.setDescription(description);
        credential.setUpdatedBy(operator);
        mapper.updateByAppId(credential);

        refreshCache();
        log.info("应用凭证更新成功: appId={}, operator={}", appId, operator);
    }

    /**
     * 轮换 app_secret
     *
     * @param appId    业务系统标识
     * @param operator 操作人
     * @return 新的明文密钥（仅此一次返回）
     */
    public String rotateAppSecret(String appId, String operator) {
        String rawSecret = generateSecureSecret();
        String hashedSecret = passwordEncoder.encode(rawSecret);

        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setAppSecret(hashedSecret);
        credential.setUpdatedBy(operator);
        mapper.updateByAppId(credential);

        refreshCache();
        log.info("app_secret 轮换成功: appId={}, operator={}", appId, operator);
        return rawSecret;
    }

    /**
     * 轮换 sign_secret
     *
     * @param appId    业务系统标识
     * @param operator 操作人
     * @return 新的明文密钥（仅此一次返回）
     */
    public String rotateSignSecret(String appId, String operator) {
        String rawSecret = generateSecureSecret();
        String hashedSecret = passwordEncoder.encode(rawSecret);

        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setSignSecret(hashedSecret);
        credential.setUpdatedBy(operator);
        mapper.updateByAppId(credential);

        refreshCache();
        log.info("sign_secret 轮换成功: appId={}, operator={}", appId, operator);
        return rawSecret;
    }

    /**
     * 轮换 mq_secret
     *
     * @param appId    业务系统标识
     * @param operator 操作人
     * @return 新的明文密钥（仅此一次返回）
     */
    public String rotateMqSecret(String appId, String operator) {
        String rawSecret = generateSecureSecret();
        String encryptedSecret = encryptMqSecret(rawSecret);

        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setMqSecret(encryptedSecret);
        credential.setUpdatedBy(operator);
        mapper.updateByAppId(credential);

        refreshCache();
        log.info("mq_secret 轮换成功: appId={}, operator={}", appId, operator);
        return rawSecret;
    }

    /**
     * 禁用应用
     *
     * @param appId    业务系统标识
     * @param operator 操作人
     */
    public void disableApp(String appId, String operator) {
        mapper.updateStatus(appId, "DISABLED");
        refreshCache();
        log.info("应用凭证已禁用: appId={}, operator={}", appId, operator);
    }

    /**
     * 启用应用
     *
     * @param appId    业务系统标识
     * @param operator 操作人
     */
    public void enableApp(String appId, String operator) {
        mapper.updateStatus(appId, "ACTIVE");
        refreshCache();
        log.info("应用凭证已启用: appId={}, operator={}", appId, operator);
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除指定 app 的缓存
     *
     * @param appId 业务系统标识
     */
    public void evictCache(String appId) {
        credentialCache.remove(appId);
        rebuildActiveSnapshot();
    }

    /**
     * 定时刷新缓存（每 60 秒）
     */
    @Scheduled(fixedDelayString = "${payment.credential.cache-refresh-ms:60000}")
    public void refreshCache() {
        try {
            List<AppCredential> all = mapper.selectAll();
            ConcurrentHashMap<String, AppCredential> newCache = new ConcurrentHashMap<>();
            for (AppCredential credential : all) {
                newCache.put(credential.getAppId(), credential);
            }
            credentialCache.clear();
            credentialCache.putAll(newCache);
            rebuildActiveSnapshot();
            log.debug("应用凭证缓存刷新完成: 共 {} 条记录", all.size());
        } catch (Exception e) {
            log.error("应用凭证缓存刷新失败", e);
        }
    }

    /**
     * 重建活跃凭证快照
     */
    private void rebuildActiveSnapshot() {
        List<AppCredential> active = credentialCache.values().stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .toList();
        activeCredentialsSnapshot = new CopyOnWriteArrayList<>(active);
    }

    // ==================== AES 加解密 ====================

    /**
     * AES-256-GCM 加密 MQ 密钥
     *
     * @param plainText 明文密钥
     * @return Base64 编码的加密结果（格式：Base64(IV + ciphertext + tag)）
     */
    public String encryptMqSecret(String plainText) {
        try {
            byte[] keyBytes = normalizeAesKey(mqMasterKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + encrypted (含 tag)
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES 加密 MQ 密钥失败", e);
        }
    }

    /**
     * AES-256-GCM 解密 MQ 密钥
     *
     * @param encrypted Base64 编码的加密数据
     * @return 明文密钥
     */
    public String decryptMqSecret(String encrypted) {
        try {
            byte[] keyBytes = normalizeAesKey(mqMasterKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");

            byte[] combined = Base64.getDecoder().decode(encrypted);

            // 提取 IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            // 提取 ciphertext + tag
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES 解密 MQ 密钥失败", e);
        }
    }

    /**
     * 将主密钥规范化为 256 位（32 字节）
     * 如果密钥不足 32 字节，使用 SHA-256 哈希扩展
     */
    private byte[] normalizeAesKey(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == 32) {
            return keyBytes;
        }
        // 不够 32 字节时用 SHA-256 哈希
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            return sha256.digest(keyBytes);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 哈希主密钥失败", e);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 生成安全的随机密钥（32 字节 hex 字符串 = 256 位）
     */
    private String generateSecureSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 脱敏密钥显示（仅显示最后 4 位）
     *
     * @param secret 完整密钥
     * @return 脱敏后的密钥（如 ****abcd）
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.length() <= 4) {
            return "****";
        }
        return "****" + secret.substring(secret.length() - 4);
    }
}
