package club.beenest.payment.wallet.security;

import club.beenest.payment.wallet.domain.entity.Wallet;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 钱包余额哈希计算器（HMAC-SHA256）
 * 用于检测余额是否被直接篡改
 *
 * <h3>安全设计：</h3>
 * <ul>
 *   <li>使用 HMAC-SHA256 替代普通 SHA-256，密钥不入代码仓库</li>
 *   <li>密钥通过环境变量 {@code WALLET_HASH_SECRET} 或 Spring 配置注入</li>
 *   <li>密钥 + 钱包编号参与运算，每个钱包哈希不同</li>
 *   <li>即使攻击者知道算法和输入格式，没有密钥也无法生成有效哈希</li>
 * </ul>
 *
 * <h3>威胁模型覆盖：</h3>
 * <ul>
 *   <li>内部开发人员知道算法 → 无法生成有效 HMAC（缺少密钥）</li>
 *   <li>DBA 有数据库权限 → 无法同时改余额和哈希（缺少密钥）</li>
 *   <li>源码泄露 → 密钥不在代码中，仍安全</li>
 *   <li>离线暴力破解 → HMAC-SHA256 密钥空间 2^256，不可行</li>
 * </ul>
 *
 * @author System
 * @since 2026-04-01
 */
@Slf4j
public final class BalanceHashCalculator {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DELIMITER = "|";

    /**
     * HMAC 密钥，通过以下优先级加载：
     * 1. 环境变量 WALLET_HASH_SECRET
     * 2. JVM 系统属性 wallet.hash.secret
     * 3. Spring 配置 payment.wallet.hash-secret（通过 setSecret 注入）
     */
    private static String hmacSecret;

    static {
        // 尝试从环境变量加载密钥
        loadSecret();
    }

    private BalanceHashCalculator() {
    }

    /**
     * 由 Spring 配置类调用，从 application.yml 注入密钥
     * 优先级低于环境变量，仅作为备选
     */
    public static void setSecret(String secret) {
        if (secret != null && !secret.isEmpty()) {
            if (hmacSecret == null || hmacSecret.isEmpty()) {
                hmacSecret = secret;
                log.info("从 Spring 配置加载钱包哈希密钥成功");
            }
            // 如果环境变量已设置，Spring 配置不覆盖
        }
    }

    /**
     * 计算钱包余额哈希（HMAC-SHA256）
     *
     * @param wallet 钱包对象
     * @return HMAC-SHA256 哈希值（十六进制字符串）
     */
    public static String calculate(Wallet wallet) {
        if (wallet == null) {
            return null;
        }
        return calculate(
                wallet.getBalance(),
                wallet.getFrozenBalance(),
                wallet.getVersion(),
                wallet.getWalletNo()
        );
    }

    /**
     * 根据具体数值计算哈希（用于余额变更后计算新 hash）
     *
     * <p>输入格式：balance + "|" + frozenBalance + "|" + version + "|" + walletNo</p>
     * <p>使用 HMAC-SHA256 签名，密钥为外部注入的 secret</p>
     *
     * @param balance       可用余额（分）
     * @param frozenBalance 冻结余额（分）
     * @param version       版本号（变更后的版本号，即当前 version + 1）
     * @param walletNo      钱包编号
     * @return HMAC-SHA256 哈希值（十六进制字符串）
     */
    public static String calculate(Long balance, Long frozenBalance, Integer version, String walletNo) {
        ensureSecretLoaded();

        // 拼接数据：balance|frozenBalance|version|walletNo
        String raw = (balance == null ? "0" : balance) + DELIMITER
                + (frozenBalance == null ? "0" : frozenBalance) + DELIMITER
                + (version == null ? "0" : version) + DELIMITER
                + (walletNo == null ? "" : walletNo);

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (java.security.InvalidKeyException e) {
            log.error("HMAC 密钥无效", e);
            throw new RuntimeException("Invalid HMAC key for balance hash", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            // HmacSHA256 是 JDK 标准算法，理论上不会出现此异常
            log.error("HmacSHA256 算法不可用", e);
            throw new RuntimeException("HmacSHA256 algorithm not available", e);
        }
    }

    /**
     * 验证钱包余额哈希是否匹配
     *
     * @param wallet 钱包对象（必须包含 balanceHash 字段）
     * @return true 表示哈希匹配，false 表示余额可能被篡改
     */
    public static boolean verify(Wallet wallet) {
        if (wallet == null) {
            return false;
        }
        String storedHash = wallet.getBalanceHash();
        if (storedHash == null || storedHash.isEmpty()) {
            log.warn("钱包缺少余额哈希，无法验证 - walletNo: {}", wallet.getWalletNo());
            return true; // 不阻断业务，但对账时会标记
        }

        String expectedHash = calculate(wallet);
        boolean valid = storedHash.equals(expectedHash);
        if (!valid) {
            log.error("【安全告警】钱包余额哈希校验失败，疑似数据被篡改！walletNo={}, storedHash={}, expectedHash={}",
                    wallet.getWalletNo(), storedHash, expectedHash);
        }
        return valid;
    }

    private static void loadSecret() {
        String secret = System.getenv("WALLET_HASH_SECRET");
        if (secret == null || secret.isEmpty()) {
            secret = System.getProperty("wallet.hash.secret");
        }
        if (secret != null && !secret.isEmpty()) {
            hmacSecret = secret;
            log.info("从环境变量/系统属性加载钱包哈希密钥成功");
        }
    }

    private static void ensureSecretLoaded() {
        if (hmacSecret == null || hmacSecret.isEmpty()) {
            throw new IllegalStateException(
                    "钱包哈希密钥未配置！请设置环境变量 WALLET_HASH_SECRET "
                    + "或在配置文件中设置 payment.wallet.hash-secret");
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
