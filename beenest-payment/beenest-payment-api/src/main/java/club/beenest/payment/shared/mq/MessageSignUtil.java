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
@SuppressWarnings("squid:S107")
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
     * @param params 签名参数封装
     */
    public static String signOrderMessage(String secret, OrderSignParams params) {
        String data = joinFields(params.messageId, params.orderNo, params.businessOrderNo, params.customerNo,
                params.amountFen != null ? params.amountFen.toString() : "0",
                params.platform != null ? params.platform : "",
                params.bizType != null ? params.bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 对支付订单完成/取消消息签名（兼容旧调用）
     */
    public static String signOrderMessage(String secret, String messageId, String orderNo, String businessOrderNo,
                                          String customerNo, Long amountFen, String platform, String bizType) {
        return signOrderMessage(secret, new OrderSignParams(messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType));
    }

    /**
     * 验证支付订单消息签名
     */
    public static boolean verifyOrderMessage(String secret, String sign, OrderSignParams params) {
        String expected = signOrderMessage(secret, params);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证支付订单消息签名（兼容旧调用）
     */
    public static boolean verifyOrderMessage(String secret, String sign, String messageId, String orderNo,
                                              String businessOrderNo, String customerNo,
                                              Long amountFen, String platform, String bizType) {
        return verifyOrderMessage(secret, sign, new OrderSignParams(messageId, orderNo, businessOrderNo, customerNo, amountFen, platform, bizType));
    }

    // ==================== 退款消息 ====================

    /**
     * 对退款完成消息签名
     * 签名字段: messageId, refundNo, orderNo, businessOrderNo, status, bizType
     *
     * @param secret 签名密钥（per-app 明文密钥）
     * @param params 签名参数封装
     */
    public static String signRefundMessage(String secret, RefundSignParams params) {
        String data = joinFields(params.messageId, params.refundNo, params.orderNo, params.businessOrderNo,
                params.status != null ? params.status : "",
                params.bizType != null ? params.bizType : "");
        return computeHmac(secret, data);
    }

    /**
     * 对退款完成消息签名（兼容旧调用）
     */
    public static String signRefundMessage(String secret, String messageId, String refundNo, String orderNo,
                                            String businessOrderNo, String status, String bizType) {
        return signRefundMessage(secret, new RefundSignParams(messageId, refundNo, orderNo, businessOrderNo, status, bizType));
    }

    /**
     * 验证退款完成消息签名
     */
    public static boolean verifyRefundMessage(String secret, String sign, RefundSignParams params) {
        String expected = signRefundMessage(secret, params);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证退款完成消息签名（兼容旧调用）
     */
    public static boolean verifyRefundMessage(String secret, String sign, String messageId, String refundNo,
                                                String orderNo, String businessOrderNo, String status, String bizType) {
        return verifyRefundMessage(secret, sign, new RefundSignParams(messageId, refundNo, orderNo, businessOrderNo, status, bizType));
    }

    // ==================== 提现消息 ====================

    /**
     * 对提现完成消息签名
     * 签名字段: messageId, requestNo, customerNo, actualAmountFen, status, appId
     *
     * @param secret 签名密钥（per-app 明文密钥）
     * @param params 签名参数封装
     */
    public static String signWithdrawMessage(String secret, WithdrawSignParams params) {
        String data = joinFields(params.messageId, params.requestNo, params.customerNo,
                params.actualAmountFen != null ? params.actualAmountFen.toString() : "0",
                params.status != null ? params.status : "",
                params.appId != null ? params.appId : "");
        return computeHmac(secret, data);
    }

    /**
     * 对提现完成消息签名（兼容旧调用）
     */
    public static String signWithdrawMessage(String secret, String messageId, String requestNo, String customerNo,
                                               Long actualAmountFen, String status, String appId) {
        return signWithdrawMessage(secret, new WithdrawSignParams(messageId, requestNo, customerNo, actualAmountFen, status, appId));
    }

    /**
     * 验证提现完成消息签名
     */
    public static boolean verifyWithdrawMessage(String secret, String sign, WithdrawSignParams params) {
        String expected = signWithdrawMessage(secret, params);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证提现完成消息签名（兼容旧调用）
     */
    public static boolean verifyWithdrawMessage(String secret, String sign, String messageId, String requestNo,
                                                  String customerNo, Long actualAmountFen,
                                                  String status, String appId) {
        return verifyWithdrawMessage(secret, sign, new WithdrawSignParams(messageId, requestNo, customerNo, actualAmountFen, status, appId));
    }

    // ==================== 余额变动消息 ====================

    /**
     * 对余额变动消息签名
     * 签名字段: messageId, customerNo, walletNo, beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, appId
     *
     * @param secret 签名密钥（per-app 明文密钥）
     * @param params 签名参数封装
     */
    public static String signBalanceMessage(String secret, BalanceSignParams params) {
        String data = joinFields(params.messageId, params.customerNo, params.walletNo,
                params.beforeBalanceFen != null ? params.beforeBalanceFen.toString() : "0",
                params.afterBalanceFen != null ? params.afterBalanceFen.toString() : "0",
                params.changeAmountFen != null ? params.changeAmountFen.toString() : "0",
                params.transactionType != null ? params.transactionType : "",
                params.appId != null ? params.appId : "");
        return computeHmac(secret, data);
    }

    /**
     * 对余额变动消息签名（兼容旧调用）
     */
    public static String signBalanceMessage(String secret, String messageId, String customerNo, String walletNo,
                                              Long beforeBalanceFen, Long afterBalanceFen,
                                              Long changeAmountFen, String transactionType, String appId) {
        return signBalanceMessage(secret, new BalanceSignParams(messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, appId));
    }

    /**
     * 验证余额变动消息签名
     */
    public static boolean verifyBalanceMessage(String secret, String sign, BalanceSignParams params) {
        String expected = signBalanceMessage(secret, params);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证余额变动消息签名（兼容旧调用）
     */
    public static boolean verifyBalanceMessage(String secret, String sign, String messageId, String customerNo,
                                                 String walletNo, Long beforeBalanceFen,
                                                 Long afterBalanceFen, Long changeAmountFen,
                                                 String transactionType, String appId) {
        return verifyBalanceMessage(secret, sign, new BalanceSignParams(messageId, customerNo, walletNo,
                beforeBalanceFen, afterBalanceFen, changeAmountFen, transactionType, appId));
    }

    // ==================== 钱包入账消息 ====================

    /**
     * 对钱包入账消息签名
     * 签名字段: messageId, customerNo, appId, amountFen, transactionType, referenceNo
     *
     * @param secret 签名密钥（per-app 明文密钥）
     * @param params 签名参数封装
     */
    public static String signWalletCreditMessage(String secret, WalletCreditSignParams params) {
        String data = joinFields(params.messageId, params.customerNo,
                params.appId != null ? params.appId : "",
                params.amountFen != null ? params.amountFen.toString() : "0",
                params.transactionType != null ? params.transactionType : "",
                params.referenceNo != null ? params.referenceNo : "");
        return computeHmac(secret, data);
    }

    /**
     * 对钱包入账消息签名（兼容旧调用）
     */
    public static String signWalletCreditMessage(String secret, String messageId, String customerNo, String appId,
                                                   Long amountFen, String transactionType, String referenceNo) {
        return signWalletCreditMessage(secret, new WalletCreditSignParams(messageId, customerNo, appId, amountFen, transactionType, referenceNo));
    }

    /**
     * 验证钱包入账消息签名
     */
    public static boolean verifyWalletCreditMessage(String secret, String sign, WalletCreditSignParams params) {
        String expected = signWalletCreditMessage(secret, params);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sign.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证钱包入账消息签名（兼容旧调用）
     */
    public static boolean verifyWalletCreditMessage(String secret, String sign, String messageId, String customerNo,
                                                      String appId, Long amountFen,
                                                      String transactionType, String referenceNo) {
        return verifyWalletCreditMessage(secret, sign, new WalletCreditSignParams(messageId, customerNo, appId, amountFen, transactionType, referenceNo));
    }

    // ==================== 签名参数封装 ====================

    /** 支付订单签名参数 */
    public record OrderSignParams(String messageId, String orderNo, String businessOrderNo,
                                   String customerNo, Long amountFen, String platform, String bizType) {}

    /** 退款签名参数 */
    public record RefundSignParams(String messageId, String refundNo, String orderNo,
                                    String businessOrderNo, String status, String bizType) {}

    /** 提现签名参数 */
    public record WithdrawSignParams(String messageId, String requestNo, String customerNo,
                                      Long actualAmountFen, String status, String appId) {}

    /** 余额变动签名参数 */
    public record BalanceSignParams(String messageId, String customerNo, String walletNo,
                                     Long beforeBalanceFen, Long afterBalanceFen,
                                     Long changeAmountFen, String transactionType, String appId) {}

    /** 钱包入账签名参数 */
    public record WalletCreditSignParams(String messageId, String customerNo, String appId,
                                          Long amountFen, String transactionType, String referenceNo) {}

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
