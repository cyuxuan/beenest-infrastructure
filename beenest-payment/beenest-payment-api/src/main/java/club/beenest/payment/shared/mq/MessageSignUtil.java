package club.beenest.payment.shared.mq;

import club.beenest.payment.shared.codec.CodecUtils;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * MQ 消息签名工具（HMAC-SHA256）
 * 用于支付中台签发消息、业务系统验签，防止消息伪造和篡改
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>签名覆盖消息中所有关键字段，任何字段被篡改都会导致验签失败</li>
 *   <li>密钥通过环境变量注入，不在代码中存储</li>
 *   <li>不同消息类型有不同的签名字段组合</li>
 *   <li>支持多密钥模式：带 secret 参数的重载方法用于 per-app 签名/验签</li>
 * </ul>
 *
 * @author System
 * @since 2026-04-03
 */
@Slf4j
public final class MessageSignUtil {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DELIMITER = "|";

    /**
     * 全局 HMAC 密钥（向后兼容），通过以下优先级加载：
     * 1. 环境变量 MQ_SIGN_SECRET
     * 2. JVM 系统属性 mq.sign.secret
     * 3. Spring 配置（通过 setSecret 注入）
     */
    private static volatile String hmacSecret;

    private MessageSignUtil() {
    }

    /**
     * 设置全局 MQ 签名密钥（向后兼容）
     *
     * @param secret 全局密钥
     */
    public static void setSecret(String secret) {
        if (secret != null && !secret.isEmpty()) {
            if (hmacSecret == null || hmacSecret.isEmpty()) {
                hmacSecret = secret;
                log.info("MQ 签名密钥加载成功");
            }
        }
    }

    // ==================== 支付订单消息 ====================

    /**
     * 对支付订单完成/取消消息签名（使用显式密钥）
     * 签名字段: messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType
     *
     * @param secret 签名密钥
     */
    public static String signOrderMessage(String secret, String messageId, String orderNo, String businessOrderNo,
                                          String customerNo, Long amountFen, String platform, String bizType) {
        String data = joinFields(messageId, orderNo, businessOrderNo, customerNo,
                amountFen != null ? amountFen.toString() : "0", platform != null ? platform : "",
                bizType != null ? bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 对支付订单完成/取消消息签名（使用全局密钥，向后兼容）
     */
    public static String signOrderMessage(String messageId, String orderNo, String businessOrderNo,
                                           String customerNo, Long amountFen, String platform, String bizType) {
        ensureSecretLoaded();
        return signOrderMessage(hmacSecret, messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType);
    }

    /** 兼容旧调用 */
    public static String signOrderMessage(String messageId, String orderNo, String businessOrderNo,
                                           String customerNo, Long amountFen, String platform) {
        return signOrderMessage(messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, null);
    }

    /**
     * 验证支付订单消息签名（使用显式密钥）
     */
    public static boolean verifyOrderMessage(String secret, String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform, String bizType) {
        String expected = signOrderMessage(secret, messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证支付订单消息签名（使用全局密钥，向后兼容）
     */
    public static boolean verifyOrderMessage(String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform, String bizType) {
        ensureSecretLoaded();
        return verifyOrderMessage(hmacSecret, sign, messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType);
    }

    public static boolean verifyOrderMessage(String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform) {
        return verifyOrderMessage(sign, messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, null);
    }

    // ==================== 退款消息 ====================

    /**
     * 对退款完成消息签名（使用显式密钥）
     * 签名字段: messageId, refundNo, orderNo, businessOrderNo, status, bizType
     */
    public static String signRefundMessage(String secret, String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status, String bizType) {
        String data = joinFields(messageId, refundNo, orderNo, businessOrderNo,
                status != null ? status : "", bizType != null ? bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 对退款完成消息签名（使用全局密钥，向后兼容）
     */
    public static String signRefundMessage(String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status, String bizType) {
        ensureSecretLoaded();
        return signRefundMessage(hmacSecret, messageId, refundNo, orderNo, businessOrderNo, status, bizType);
    }

    /** 兼容旧调用 */
    public static String signRefundMessage(String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status) {
        return signRefundMessage(messageId, refundNo, orderNo, businessOrderNo, status, null);
    }

    /**
     * 验证退款完成消息签名（使用显式密钥）
     */
    public static boolean verifyRefundMessage(String secret, String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status, String bizType) {
        String expected = signRefundMessage(secret, messageId, refundNo, orderNo, businessOrderNo, status, bizType);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证退款完成消息签名（使用全局密钥，向后兼容）
     */
    public static boolean verifyRefundMessage(String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status, String bizType) {
        ensureSecretLoaded();
        return verifyRefundMessage(hmacSecret, sign, messageId, refundNo, orderNo, businessOrderNo, status, bizType);
    }

    public static boolean verifyRefundMessage(String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status) {
        return verifyRefundMessage(sign, messageId, refundNo, orderNo, businessOrderNo, status, null);
    }

    // ==================== 提现消息 ====================

    /**
     * 对提现完成消息签名（使用显式密钥）
     * 签名字段: messageId, requestNo, customerNo, actualAmountFen, status, bizType
     */
    public static String signWithdrawMessage(String secret, String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status, String bizType) {
        String data = joinFields(messageId, requestNo, customerNo,
                actualAmountFen != null ? actualAmountFen.toString() : "0",
                status != null ? status : "",
                bizType != null ? bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 对提现完成消息签名（使用全局密钥，向后兼容）
     */
    public static String signWithdrawMessage(String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status, String bizType) {
        ensureSecretLoaded();
        return signWithdrawMessage(hmacSecret, messageId, requestNo, customerNo, actualAmountFen, status, bizType);
    }

    /** 兼容旧调用 */
    public static String signWithdrawMessage(String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status) {
        return signWithdrawMessage(messageId, requestNo, customerNo, actualAmountFen, status, null);
    }

    /**
     * 验证提现完成消息签名（使用显式密钥）
     */
    public static boolean verifyWithdrawMessage(String secret, String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen,
                                                  String status, String bizType) {
        String expected = signWithdrawMessage(secret, messageId, requestNo, customerNo, actualAmountFen, status, bizType);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证提现完成消息签名（使用全局密钥，向后兼容）
     */
    public static boolean verifyWithdrawMessage(String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen, String status, String bizType) {
        ensureSecretLoaded();
        return verifyWithdrawMessage(hmacSecret, sign, messageId, requestNo, customerNo, actualAmountFen, status, bizType);
    }

    public static boolean verifyWithdrawMessage(String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen, String status) {
        return verifyWithdrawMessage(sign, messageId, requestNo, customerNo, actualAmountFen, status, null);
    }

    // ==================== 余额变动消息 ====================

    /**
     * 对余额变动消息签名（使用显式密钥）
     * 签名字段: messageId, customerNo, walletNo, beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, bizType
     */
    public static String signBalanceMessage(String secret, String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType, String bizType) {
        String data = joinFields(messageId, customerNo, walletNo,
                beforeBalanceFen != null ? beforeBalanceFen.toString() : "0",
                afterBalanceFen != null ? afterBalanceFen.toString() : "0",
                changeAmountFen != null ? changeAmountFen.toString() : "0",
                transactionType != null ? transactionType : "",
                bizType != null ? bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 对余额变动消息签名（使用全局密钥，向后兼容）
     */
    public static String signBalanceMessage(String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType, String bizType) {
        ensureSecretLoaded();
        return signBalanceMessage(hmacSecret, messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, bizType);
    }

    public static String signBalanceMessage(String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType) {
        return signBalanceMessage(messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, null);
    }

    /**
     * 验证余额变动消息签名（使用显式密钥）
     */
    public static boolean verifyBalanceMessage(String secret, String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType, String bizType) {
        String expected = signBalanceMessage(secret, messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, bizType);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证余额变动消息签名（使用全局密钥，向后兼容）
     */
    public static boolean verifyBalanceMessage(String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType, String bizType) {
        ensureSecretLoaded();
        return verifyBalanceMessage(hmacSecret, sign, messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, bizType);
    }

    public static boolean verifyBalanceMessage(String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType) {
        return verifyBalanceMessage(sign, messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, null);
    }

    // ==================== 钱包入账消息 ====================

    /**
     * 对钱包入账消息签名（使用显式密钥）
     * 签名字段: messageId, customerNo, bizType, amountFen, transactionType, referenceNo
     */
    public static String signWalletCreditMessage(String secret, String messageId, String customerNo, String bizType,
                                                   Long amountFen, String transactionType, String referenceNo) {
        String data = joinFields(messageId, customerNo,
                bizType != null ? bizType : "",
                amountFen != null ? amountFen.toString() : "0",
                transactionType != null ? transactionType : "",
                referenceNo != null ? referenceNo : "");
        return computeHmac(secret, data);
    }

    /**
     * 对钱包入账消息签名（使用全局密钥，向后兼容）
     */
    public static String signWalletCreditMessage(String messageId, String customerNo, String bizType,
                                                   Long amountFen, String transactionType, String referenceNo) {
        ensureSecretLoaded();
        return signWalletCreditMessage(hmacSecret, messageId, customerNo, bizType, amountFen, transactionType, referenceNo);
    }

    /**
     * 验证钱包入账消息签名（使用显式密钥）
     */
    public static boolean verifyWalletCreditMessage(String secret, String sign, String messageId, String customerNo,
                                                      String bizType, Long amountFen,
                                                      String transactionType, String referenceNo) {
        String expected = signWalletCreditMessage(secret, messageId, customerNo, bizType, amountFen, transactionType, referenceNo);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证钱包入账消息签名（使用全局密钥，向后兼容）
     */
    public static boolean verifyWalletCreditMessage(String sign, String messageId, String customerNo,
                                                      String bizType, Long amountFen,
                                                      String transactionType, String referenceNo) {
        ensureSecretLoaded();
        return verifyWalletCreditMessage(hmacSecret, sign, messageId, customerNo, bizType, amountFen, transactionType, referenceNo);
    }

    // ==================== 内部方法 ====================

    /**
     * 使用显式密钥计算 HMAC
     */
    private static String computeHmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return CodecUtils.bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("MQ 消息签名计算失败", e);
        }
    }

    private static String joinFields(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(DELIMITER);
            sb.append(fields[i] != null ? fields[i] : "");
        }
        return sb.toString();
    }

    private static void ensureSecretLoaded() {
        if (hmacSecret == null || hmacSecret.isEmpty()) {
            String envSecret = System.getenv("MQ_SIGN_SECRET");
            if (envSecret != null && !envSecret.isEmpty()) {
                hmacSecret = envSecret;
                return;
            }
            String propSecret = System.getProperty("mq.sign.secret");
            if (propSecret != null && !propSecret.isEmpty()) {
                hmacSecret = propSecret;
                return;
            }
            throw new IllegalStateException(
                    "MQ 签名密钥未配置！请设置环境变量 MQ_SIGN_SECRET "
                    + "或在配置文件中设置 payment.mq.sign-secret");
        }
    }
}
