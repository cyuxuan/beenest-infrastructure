package club.beenest.payment.mq;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * MQ 消息签名工具（HMAC-SHA256）
 * 用于支付中台签发消息、业务系统验签，防止消息伪造和篡改
 *
 * <h3>设计原则：</h3>
 * <ul>
 *   <li>签名覆盖消息中所有关键字段，任何字段被篡改都会导致验签失败</li>
 *   <li>密钥通过环境变量注入，不在代码中存储</li>
 *   <li>不同消息类型有不同的签名字段组合</li>
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
     * HMAC 密钥，通过以下优先级加载：
     * 1. 环境变量 MQ_SIGN_SECRET
     * 2. JVM 系统属性 mq.sign.secret
     * 3. Spring 配置（通过 setSecret 注入）
     */
    private static volatile String hmacSecret;

    private MessageSignUtil() {
    }

    public static void setSecret(String secret) {
        if (secret != null && !secret.isEmpty()) {
            if (hmacSecret == null || hmacSecret.isEmpty()) {
                hmacSecret = secret;
                log.info("MQ 签名密钥加载成功");
            }
        }
    }

    /**
     * 对支付订单完成/取消消息签名
     * 签名字段: messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType
     */
    public static String signOrderMessage(String messageId, String orderNo, String businessOrderNo,
                                           String customerNo, Long amountFen, String platform, String bizType) {
        String data = joinFields(messageId, orderNo, businessOrderNo, customerNo,
                amountFen != null ? amountFen.toString() : "0", platform != null ? platform : "",
                bizType != null ? bizType : "");
        return computeHmac(data);
    }

    /** 兼容旧调用 */
    public static String signOrderMessage(String messageId, String orderNo, String businessOrderNo,
                                           String customerNo, Long amountFen, String platform) {
        return signOrderMessage(messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, null);
    }

    /**
     * 验证支付订单消息签名
     */
    public static boolean verifyOrderMessage(String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform, String bizType) {
        String expected = signOrderMessage(messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType);
        return constantTimeEquals(expected, sign);
    }

    public static boolean verifyOrderMessage(String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform) {
        return verifyOrderMessage(sign, messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, null);
    }

    /**
     * 对退款完成消息签名
     * 签名字段: messageId, refundNo, orderNo, businessOrderNo, status, bizType
     */
    public static String signRefundMessage(String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status, String bizType) {
        String data = joinFields(messageId, refundNo, orderNo, businessOrderNo,
                status != null ? status : "", bizType != null ? bizType : "");
        return computeHmac(data);
    }

    /** 兼容旧调用 */
    public static String signRefundMessage(String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status) {
        return signRefundMessage(messageId, refundNo, orderNo, businessOrderNo, status, null);
    }

    public static boolean verifyRefundMessage(String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status, String bizType) {
        String expected = signRefundMessage(messageId, refundNo, orderNo, businessOrderNo, status, bizType);
        return constantTimeEquals(expected, sign);
    }

    public static boolean verifyRefundMessage(String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status) {
        return verifyRefundMessage(sign, messageId, refundNo, orderNo, businessOrderNo, status, null);
    }

    /**
     * 对提现完成消息签名
     * 签名字段: messageId, requestNo, customerNo, actualAmountFen, status, bizType
     */
    public static String signWithdrawMessage(String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status, String bizType) {
        String data = joinFields(messageId, requestNo, customerNo,
                actualAmountFen != null ? actualAmountFen.toString() : "0",
                status != null ? status : "",
                bizType != null ? bizType : "");
        return computeHmac(data);
    }

    /** 兼容旧调用 */
    public static String signWithdrawMessage(String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status) {
        return signWithdrawMessage(messageId, requestNo, customerNo, actualAmountFen, status, null);
    }

    public static boolean verifyWithdrawMessage(String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen,
                                                  String status, String bizType) {
        String expected = signWithdrawMessage(messageId, requestNo, customerNo, actualAmountFen, status, bizType);
        return constantTimeEquals(expected, sign);
    }

    public static boolean verifyWithdrawMessage(String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen, String status) {
        return verifyWithdrawMessage(sign, messageId, requestNo, customerNo, actualAmountFen, status, null);
    }

    /**
     * 对余额变动消息签名
     * 签名字段: messageId, customerNo, walletNo, beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, bizType
     */
    public static String signBalanceMessage(String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType) {
        return signBalanceMessage(messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, null);
    }

    public static String signBalanceMessage(String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType, String bizType) {
        String data = joinFields(messageId, customerNo, walletNo,
                beforeBalanceFen != null ? beforeBalanceFen.toString() : "0",
                afterBalanceFen != null ? afterBalanceFen.toString() : "0",
                changeAmountFen != null ? changeAmountFen.toString() : "0",
                transactionType != null ? transactionType : "",
                bizType != null ? bizType : "");
        return computeHmac(data);
    }

    public static boolean verifyBalanceMessage(String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType) {
        return verifyBalanceMessage(sign, messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, null);
    }

    public static boolean verifyBalanceMessage(String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType, String bizType) {
        String expected = signBalanceMessage(messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, bizType);
        return constantTimeEquals(expected, sign);
    }

    /**
     * 对飞手结算消息签名
     * 签名字段: messageId, planNo, pilotUserId, pilotIncome, platformFee, orderAmount, bizType
     */
    public static String signPilotSettlementMessage(String messageId, String planNo, String pilotUserId,
                                                      String pilotIncome, String platformFee,
                                                      String orderAmount, String bizType) {
        String data = joinFields(messageId, planNo, pilotUserId,
                pilotIncome != null ? pilotIncome : "0",
                platformFee != null ? platformFee : "0",
                orderAmount != null ? orderAmount : "0",
                bizType != null ? bizType : "");
        return computeHmac(data);
    }

    public static boolean verifyPilotSettlementMessage(String sign, String messageId, String planNo,
                                                         String pilotUserId, String pilotIncome,
                                                         String platformFee, String orderAmount,
                                                         String bizType) {
        String expected = signPilotSettlementMessage(messageId, planNo, pilotUserId,
                pilotIncome, platformFee, orderAmount, bizType);
        return constantTimeEquals(expected, sign);
    }

    // ==================== 内部方法 ====================

    private static String computeHmac(String data) {
        ensureSecretLoaded();
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("MQ 消息签名计算失败", e);
        }
    }

    private static String joinFields(String... fields) {
        return Arrays.stream(fields)
                .map(f -> f != null ? f : "")
                .collect(Collectors.joining(DELIMITER));
    }

    /**
     * 恒定时间比较，防止时序攻击
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
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

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
