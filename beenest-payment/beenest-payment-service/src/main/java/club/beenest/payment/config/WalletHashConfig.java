package club.beenest.payment.config;

import club.beenest.payment.security.BalanceHashCalculator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 钱包哈希密钥配置
 * 从 application.yml 的 payment.wallet 节点加载
 *
 * <p>密钥优先级：</p>
 * <ol>
 *   <li>环境变量 {@code WALLET_HASH_SECRET}</li>
 *   <li>JVM 系统属性 {@code wallet.hash.secret}</li>
 *   <li>Spring 配置 {@code payment.wallet.hash-secret}</li>
 * </ol>
 *
 * @author System
 * @since 2026-04-01
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "payment.wallet")
public class WalletHashConfig {

    /**
     * HMAC-SHA256 密钥
     * 必须通过环境变量或外部配置文件设置，不硬编码在代码中
     */
    private String hashSecret;

    /**
     * Spring 初始化后注入密钥到 BalanceHashCalculator
     * 使用 @PostConstruct 保证在业务逻辑之前执行
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        if (hashSecret != null && !hashSecret.isEmpty()) {
            BalanceHashCalculator.setSecret(hashSecret);
            log.info("钱包哈希密钥配置加载完成");
        } else {
            log.warn("未配置 payment.wallet.hash-secret，请通过环境变量 WALLET_HASH_SECRET 设置");
        }
    }
}
