package club.beenest.payment.feign;

import club.beenest.payment.shared.codec.CodecUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 支付 Feign 请求拦截器
 *
 * <p>自动为下游服务调用支付中台的请求添加：</p>
 * <ul>
 *   <li>{@code X-App-Id} — 业务系统标识（如 DRONE、SHOP）</li>
 *   <li>{@code X-Internal-Token} — 应用密钥（令牌认证 + HMAC 签名共用）</li>
 *   <li>{@code X-Timestamp} / {@code X-Nonce} / {@code X-Signature} — HMAC-SHA256 签名</li>
 * </ul>
 *
 * <p>下游服务只需在配置中指定 {@code payment.client.app-id} 和
 * {@code payment.client.app-secret} 即可（app_secret 同时用于令牌认证和签名）。</p>
 *
 * @author System
 * @since 2026-07-16
 */
@Slf4j
@Component
public class PaymentFeignAppInterceptor implements RequestInterceptor {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${payment.client.app-id:DRONE}")
    private String appId;

    @Value("${payment.client.app-secret:}")
    private String appSecret;

    @Override
    public void apply(RequestTemplate template) {
        // 1. 添加 X-App-Id 头
        template.header("X-App-Id", appId);

        // 2. 添加 X-Internal-Token 头 + HMAC 签名（app_secret 同时用于令牌认证和签名）
        if (StringUtils.isNotBlank(appSecret)) {
            template.header("X-Internal-Token", appSecret);

            // 3. HMAC 签名
            long timestamp = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString().replace("-", "");

            // 读取请求体
            String body = "";
            if (template.body() != null) {
                body = new String(template.body(), StandardCharsets.UTF_8);
            }

            // 构建签名数据：METHOD|PATH|TIMESTAMP|NONCE|BODY
            String method = template.method();
            String path = template.path() != null ? template.path() : "";
            String data = method + "|" + path + "|" + timestamp + "|" + nonce + "|" + body;
            String signature = computeHmac(appSecret, data);

            template.header("X-Timestamp", String.valueOf(timestamp));
            template.header("X-Nonce", nonce);
            template.header("X-Signature", signature);
        }
    }

    /**
     * 计算 HMAC-SHA256 签名
     *
     * @param secret 签名密钥
     * @param data   待签名数据
     * @return 十六进制签名字符串
     */
    private String computeHmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return CodecUtils.bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 计算失败", e);
        }
    }
}
