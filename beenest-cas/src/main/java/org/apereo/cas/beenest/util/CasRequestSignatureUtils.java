package org.apereo.cas.beenest.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 业务系统登录请求签名工具。
 * <p>
 * 采用 HMAC-SHA256 计算请求签名，签名内容为：
 * {@code timestamp + "\n" + nonce + "\n" + body}
 */
public final class CasRequestSignatureUtils {

    private CasRequestSignatureUtils() {
    }

    /**
     * 生成请求签名。
     *
     * @param timestamp 时间戳
     * @param nonce     随机串
     * @param body      请求体
     * @param secret    业务系统 secret
     * @return Base64 编码的 HMAC-SHA256 签名
     */
    public static String sign(String timestamp, String nonce, String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signature = mac.doFinal(buildSigningString(timestamp, nonce, body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("生成请求签名失败", e);
        }
    }

    /**
     * 常量时间比较两个签名字符串。
     *
     * @param expected 期望值
     * @param actual   实际值
     * @return 是否相等
     */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 构建签名串。
     */
    public static String buildSigningString(String timestamp, String nonce, String body) {
        return String.valueOf(timestamp) + "\n" + String.valueOf(nonce) + "\n" + String.valueOf(body);
    }
}
