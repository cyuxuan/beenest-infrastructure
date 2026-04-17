package org.apereo.cas.beenest.client.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * 业务系统请求签名工具。
 * <p>
 * 用于 proxy 转发请求到 CAS Server 时自动计算签名。
 */
public final class RequestSignatureUtils {

    private RequestSignatureUtils() {
    }

    /**
     * 生成 HMAC-SHA256 签名。
     *
     * @param timestamp 时间戳
     * @param nonce     随机串
     * @param body      请求体
     * @param secret    签名密钥
     * @return Base64 编码的签名
     */
    public static String sign(String timestamp, String nonce, String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            String signingString = timestamp + "\n" + nonce + "\n" + body;
            byte[] signature = mac.doFinal(signingString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("生成请求签名失败", e);
        }
    }

    /**
     * 生成随机 nonce。
     */
    public static String generateNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
