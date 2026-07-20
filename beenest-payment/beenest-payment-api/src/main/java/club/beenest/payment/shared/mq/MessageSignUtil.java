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
 *   <li>密钥通过 per-app 凭证表管理（AES 加密存储），不在代码中存储</li>
 *   <li>不同消息类型有不同的签名字段组合</li>
 *   <li>所有签名/验签方法均需显式传入 secret 参数</li>
 * </ul>
 *
 * @author System
 * @since 2026-04-03
 */
@Slf4j
public final class MessageSignUtil {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String DELIMITER = "|";

    private MessageSignUtil() {
    }

    // ==================== 支付订单消息 ====================

    /**
     * 对支付订单完成/取消消息签名
     * 签名字段: messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType
     *
     * @param secret 签名密钥（per-app 明文密钥）
     */
    public static String signOrderMessage(String secret, String messageId, String orderNo, String businessOrderNo,
                                          String customerNo, Long amountFen, String platform, String bizType) {
        String data = joinFields(messageId, orderNo, businessOrderNo, customerNo,
                amountFen != null ? amountFen.toString() : "0", platform != null ? platform : "",
                bizType != null ? bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 验证支付订单消息签名
     */
    public static boolean verifyOrderMessage(String secret, String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform, String bizType) {
        String expected = signOrderMessage(secret, messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 退款消息 ====================

    /**
     * 对退款完成消息签名
     * 签名字段: messageId, refundNo, orderNo, businessOrderNo, status, bizType
     *
     * @param secret 签名密钥（per-app 明文密钥）
     */
    public static String signRefundMessage(String secret, String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status, String bizType) {
        String data = joinFields(messageId, refundNo, orderNo, businessOrderNo,
                status != null ? status : "", bizType != null ? bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 验证退款完成消息签名
     */
    public static boolean verifyRefundMessage(String secret, String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status, String bizType) {
        String expected = signRefundMessage(secret, messageId, refundNo, orderNo, businessOrderNo, status, bizType);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 提现消息 ====================

    /**
     * 对提现完成消息签名
     * 签名字段: messageId, requestNo, customerNo, actualAmountFen, status, appId
     *
     * @param secret 签名密钥（per-app 明文密钥）
     */
    public static String signWithdrawMessage(String secret, String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status, String appId) {
        String data = joinFields(messageId, requestNo, customerNo,
                actualAmountFen != null ? actualAmountFen.toString() : "0",
                status != null ? status : "",
                appId != null ? appId : "");
        return computeHmac(secret, data);
    }

    /**
     * 验证提现完成消息签名
     */
    public static boolean verifyWithdrawMessage(String secret, String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen,
                                                  String status, String appId) {
        String expected = signWithdrawMessage(secret, messageId, requestNo, customerNo, actualAmountFen, status, appId);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 余额变动消息 ====================

    /**
     * 对余额变动消息签名
     * 签名字段: messageId, customerNo, walletNo, beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, appId
     *
     * @param secret 签名密钥（per-app 明文密钥）
     */
    public static String signBalanceMessage(String secret, String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType, String appId) {
        String data = joinFields(messageId, customerNo, walletNo,
                beforeBalanceFen != null ? beforeBalanceFen.toString() : "0",
                afterBalanceFen != null ? afterBalanceFen.toString() : "0",
                changeAmountFen != null ? changeAmountFen.toString() : "0",
                transactionType != null ? transactionType : "",
                appId != null ? appId : "");
        return computeHmac(secret, data);
    }

    /**
     * 验证余额变动消息签名
     */
    public static boolean verifyBalanceMessage(String secret, String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType, String appId) {
        String expected = signBalanceMessage(secret, messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, appId);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 钱包入账消息 ====================

    /**
     * 对钱包入账消息签名
     * 签名字段: messageId, customerNo, appId, amountFen, transactionType, referenceNo
     *
     * @param secret 签名密钥（per-app 明文密钥）
     */
    public static String signWalletCreditMessage(String secret, String messageId, String customerNo, String appId,
                                                   Long amountFen, String transactionType, String referenceNo) {
        String data = joinFields(messageId, customerNo,
                appId != null ? appId : "",
                amountFen != null ? amountFen.toString() : "0",
                transactionType != null ? transactionType : "",
                referenceNo != null ? referenceNo : "");
        return computeHmac(secret, data);
    }

    /**
     * 验证钱包入账消息签名
     */
    public static boolean verifyWalletCreditMessage(String secret, String sign, String messageId, String customerNo,
                                                      String appId, Long amountFen,
                                                      String transactionType, String referenceNo) {
        String expected = signWalletCreditMessage(secret, messageId, customerNo, appId, amountFen, transactionType, referenceNo);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
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
}
