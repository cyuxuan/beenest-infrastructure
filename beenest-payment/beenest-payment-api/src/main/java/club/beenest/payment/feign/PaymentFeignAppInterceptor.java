package club.beenest.payment.feign;

import club.beenest.payment.shared.codec.CodecUtils;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Target;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
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
 * <h4>签名路径说明</h4>
 * <p>Feign 的 {@code RequestTemplate.path()} 仅返回方法级路径（如 {@code /payment/orders}），
 * 不包含 {@code @FeignClient(path=...)} 的基础路径前缀（如 {@code /internal/payment}）。
 * 但服务端 {@code InternalApiFilter} 使用 {@code request.getRequestURI()} 获取完整路径，
 * 因此签名时必须拼接完整路径。本拦截器通过 {@code template.feignTarget().url()}
 * 提取基础路径前缀，确保客户端与服务端签名数据一致。</p>
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
            // 关键：template.path() 仅返回方法级路径（如 /payment/orders），
            // 不包含 @FeignClient(path="/internal/payment") 的基础路径前缀。
            // 但服务端 InternalApiFilter 使用 request.getRequestURI()（含完整路径），
            // 因此必须拼接 feignTarget 的 URL path 前缀，确保签名一致。
            String method = template.method();
            String methodPath = template.path() != null ? template.path() : "";
            // 去掉 query string（服务端 getRequestURI() 不含 query）
            if (methodPath.contains("?")) {
                methodPath = methodPath.substring(0, methodPath.indexOf("?"));
            }
            // 从 feignTarget.url() 提取基础路径前缀
            // feignTarget.url() 格式如 "http://beenest-payment/internal/payment"
            String basePath = extractBasePath(template);
            String fullPath = basePath + methodPath;
            String data = method + "|" + fullPath + "|" + timestamp + "|" + nonce + "|" + body;
            String signature = computeHmac(appSecret, data);

            template.header("X-Timestamp", String.valueOf(timestamp));
            template.header("X-Nonce", nonce);
            template.header("X-Signature", signature);
        }
    }

    /**
     * 从 Feign Target 的 URL 中提取基础路径前缀
     *
     * <p>Spring Cloud OpenFeign 的 Target URL 格式为 {@code http://service-name/base-path}，
     * 其中 base-path 即 {@code @FeignClient(path=...)} 的值。
     * 本方法提取该路径部分（如 {@code /internal/payment}）。</p>
     *
     * @param template Feign 请求模板
     * @return 基础路径前缀（如 "/internal/payment"），无路径时返回空字符串
     */
    private String extractBasePath(RequestTemplate template) {
        Target<?> feignTarget = template.feignTarget();
        if (feignTarget == null) {
            return "";
        }
        String targetUrl = feignTarget.url();
        if (targetUrl == null || targetUrl.isEmpty()) {
            return "";
        }
        try {
            URI uri = URI.create(targetUrl);
            String path = uri.getPath();
            // URI.getPath() 对 "http://host" 返回 null 或 ""
            return path != null ? path : "";
        } catch (Exception e) {
            log.warn("无法解析 Feign Target URL 的路径: url={}", targetUrl, e);
            return "";
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
