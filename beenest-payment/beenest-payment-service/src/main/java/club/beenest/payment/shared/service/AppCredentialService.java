package club.beenest.payment.shared.service;

import club.beenest.payment.shared.constant.BizTypeConstants;
import club.beenest.payment.shared.domain.entity.AppCredential;
import club.beenest.payment.shared.mapper.AppCredentialMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 应用凭证服务
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>应用凭证查询（带内存缓存，定时刷新）</li>
 *   <li>app_secret 验证（令牌认证 + HMAC 签名共用）</li>
 *   <li>mq_secret 获取（MQ 消息签名）</li>
 *   <li>凭证管理 CRUD + 密钥轮换</li>
 * </ul>
 *
 * <p>密钥体系（2 secret 模型）：</p>
 * <ul>
 *   <li>app_secret — 令牌认证 + HMAC 签名共用（明文存储，DB 访问控制保护）</li>
 *   <li>mq_secret — MQ 消息签名（明文存储，DB 访问控制保护）</li>
 * </ul>
 *
 * <p>安全说明：app_secret / mq_secret 运行时必须以明文参与 HMAC 计算，
 * AES 加密存储只是"安全幻觉"（主密钥与密钥在同一进程内存中）。
 * 明文存储 + DB 访问控制是更诚实、更简洁的方案。</p>
 *
 * @author System
 * @since 2026-07-16
 */
@Slf4j
@Service
public class AppCredentialService {

    /** 凭证活跃状态 */
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AppCredentialMapper mapper;

    /**
     * 内存缓存：appId → AppCredential
     */
    private final ConcurrentHashMap<String, AppCredential> credentialCache = new ConcurrentHashMap<>();

    /**
     * 缓存中所有活跃凭证的快照列表。
     * 使用 CopyOnWriteArrayList 保证线程安全的并发读取。
     */
    private final CopyOnWriteArrayList<AppCredential> activeCredentialsSnapshot = new CopyOnWriteArrayList<>();

    public AppCredentialService(AppCredentialMapper mapper) {
        this.mapper = mapper;
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
     * 验证 app_secret（令牌认证 + HMAC 签名共用）
     *
     * <p>使用常量时间比较防止时序攻击。</p>
     *
     * @param appId      业务系统标识
     * @param rawSecret  原始密钥（请求头中的值）
     * @return 验证是否通过
     */
    public boolean verifyAppSecret(String appId, String rawSecret) {
        AppCredential credential = getByAppId(appId);
        if (credential == null || rawSecret == null) {
            return false;
        }
        // 常量时间比较，防止时序攻击
        return java.security.MessageDigest.isEqual(
                credential.getAppSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                rawSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 获取 app_secret 明文密钥（用于 HMAC 签名验证）
     *
     * @param appId 业务系统标识
     * @return app_secret 明文密钥，不存在返回 null
     */
    public String getAppSecret(String appId) {
        AppCredential credential = getByAppId(appId);
        return credential != null ? credential.getAppSecret() : null;
    }

    /**
     * 获取 MQ 明文密钥
     *
     * @param appId 业务系统标识
     * @return MQ 明文密钥，不存在返回 null
     */
    public String getMqSecret(String appId) {
        AppCredential credential = getByAppId(appId);
        return credential != null ? credential.getMqSecret() : null;
    }

    /**
     * 通过 bizType 推导 appId，再获取 MQ 明文密钥
     *
     * @param bizType 业务类型
     * @return MQ 明文密钥，不存在返回 null
     */
    public String getMqSecretByBizType(String bizType) {
        String appId = BizTypeConstants.deriveAppId(bizType);
        return getMqSecret(appId);
    }

    // ==================== 管理方法 ====================

    /**
     * 创建应用凭证
     *
     * @param appId           业务系统标识
     * @param appName         应用名称
     * @param allowedNetworks 允许的 IP/CIDR 列表
     * @param description     描述
     * @param operator        操作人
     * @return 创建的凭证实体（包含明文密钥，仅此一次返回）
     */
    public AppCredential createApp(String appId, String appName, String allowedNetworks,
                                   String description, String operator) {
        // 1. 生成密钥
        String rawAppSecret = generateSecureSecret();
        String rawMqSecret = generateSecureSecret();

        // 2. 持久化（明文存储）
        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setAppName(appName);
        credential.setAppSecret(rawAppSecret);
        credential.setMqSecret(rawMqSecret);
        credential.setAllowedNetworks(allowedNetworks);
        credential.setStatus(STATUS_ACTIVE);
        credential.setDescription(description);
        credential.setCreatedBy(operator);
        mapper.insert(credential);

        // 3. 刷新缓存
        refreshCache();

        log.info("应用凭证创建成功: appId={}, appName={}, operator={}", appId, appName, operator);

        // 4. 返回包含明文密钥的副本（仅此一次）
        AppCredential result = new AppCredential();
        result.setAppId(appId);
        result.setAppName(appName);
        result.setAppSecret(rawAppSecret);
        result.setMqSecret(rawMqSecret);
        result.setAllowedNetworks(allowedNetworks);
        result.setStatus(STATUS_ACTIVE);
        result.setDescription(description);
        return result;
    }

    /**
     * 更新应用信息（名称、IP白名单、描述）
     *
     * @param appId           业务系统标识
     * @param appName         应用名称（null 不更新）
     * @param allowedNetworks IP 白名单（null 不更新）
     * @param description     描述（null 不更新）
     * @param operator        操作人
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
     * 轮换 app_secret（令牌认证 + HMAC 签名共用）
     *
     * @param appId    业务系统标识
     * @param operator 操作人
     * @return 新的明文密钥（仅此一次返回）
     */
    public String rotateAppSecret(String appId, String operator) {
        String rawSecret = generateSecureSecret();

        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setAppSecret(rawSecret);
        credential.setUpdatedBy(operator);
        mapper.updateByAppId(credential);

        refreshCache();
        log.info("app_secret 轮换成功: appId={}, operator={}", appId, operator);
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

        AppCredential credential = new AppCredential();
        credential.setAppId(appId);
        credential.setMqSecret(rawSecret);
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
        mapper.updateStatus(appId, STATUS_ACTIVE);
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
                .filter(c -> STATUS_ACTIVE.equals(c.getStatus()))
                .toList();
        activeCredentialsSnapshot.clear();
        activeCredentialsSnapshot.addAll(active);
    }

    // ==================== 工具方法 ====================

    /**
     * 生成安全的随机密钥（32 字节 hex 字符串 = 256 位）
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateSecureSecret() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
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
