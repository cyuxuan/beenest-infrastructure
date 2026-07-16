package club.beenest.payment.security;

import club.beenest.payment.shared.codec.CodecUtils;
import club.beenest.payment.shared.domain.entity.AppCredential;
import club.beenest.payment.shared.service.AppCredentialService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 内部 API 安全过滤器
 *
 * <p>拦截 /internal/** 路径，强制要求请求携带 {@code X-App-Id} 头，
 * 基于应用凭证表进行 per-app 身份验证，实现业务系统级密钥隔离。</p>
 *
 * <h4>验证流程：</h4>
 * <ol>
 *   <li>检查 X-App-Id 头是否存在，不存在直接 403</li>
 *   <li>查询应用凭证，校验 app 状态（ACTIVE/DISABLED）</li>
 *   <li>per-app IP 白名单校验（allowed_networks 非空时生效，支持 CIDR 网段；为空时不做 IP 限制）</li>
 *   <li>app_secret 验证：常量时间比对 X-Internal-Token + HMAC-SHA256 签名校验（含时间戳 + Nonce 防重放）</li>
 *   <li>设置 {@link AppContext} 传播 appId</li>
 * </ol>
 *
 * <p>密钥体系：app_secret 同时用于令牌认证和 HMAC 签名，无需独立的 sign_secret。</p>
 *
 * @author System
 * @since 2026-02-11
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalApiFilter extends OncePerRequestFilter {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_TIMESTAMP_DRIFT_MS = 5 * 60 * 1000; // 5 分钟
    private static final String NONCE_KEY_PREFIX = "internal:nonce:";

    private final AppCredentialService appCredentialService;
    private final StringRedisTemplate redisTemplate;
    private final boolean trustProxy;

    public InternalApiFilter(
            AppCredentialService appCredentialService,
            @Value("${payment.internal.trust-proxy:false}") boolean trustProxy,
            StringRedisTemplate redisTemplate) {
        this.appCredentialService = appCredentialService;
        this.trustProxy = trustProxy;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String clientIp = getClientIp(cachedRequest);
        String appIdHeader = cachedRequest.getHeader("X-App-Id");

        // 1. 强制要求 X-App-Id 头
        if (appIdHeader == null || appIdHeader.isBlank()) {
            log.warn("内部 API 非法访问: 缺少 X-App-Id 头, uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
            reject(response, "Missing X-App-Id header");
            return;
        }

        String appId = appIdHeader.trim();

        // 2. 查询应用凭证
        AppCredential credential = appCredentialService.getByAppId(appId);
        if (credential == null) {
            log.warn("内部 API 非法访问: app_id={} 不存在, uri={}, clientIp={}", appId, cachedRequest.getRequestURI(), clientIp);
            reject(response, "Unknown app");
            return;
        }
        if (!"ACTIVE".equals(credential.getStatus())) {
            log.warn("内部 API 非法访问: app_id={} 已禁用, uri={}, clientIp={}", appId, cachedRequest.getRequestURI(), clientIp);
            reject(response, "App disabled");
            return;
        }

        // 3. per-app IP 白名单校验（非空时生效，为空时不做 IP 限制）
        String allowedNetworks = credential.getAllowedNetworks();
        if (allowedNetworks != null && !allowedNetworks.isBlank()) {
            List<String> networks = parseNetworks(allowedNetworks);
            if (!isIpInNetworks(clientIp, networks)) {
                log.warn("内部 API 非法访问: app_id={} IP 不在白名单, uri={}, clientIp={}", appId, cachedRequest.getRequestURI(), clientIp);
                reject(response, "Access denied");
                return;
            }
        }
        // allowed_networks 为空时不做 IP 限制，所有 IP 可访问

        // 4. app_secret 验证：常量时间比对令牌
        String requestToken = cachedRequest.getHeader("X-Internal-Token");
        if (!appCredentialService.verifyAppSecret(appId, requestToken)) {
            log.warn("内部 API Token 验证失败: app_id={}, uri={}, clientIp={}", appId, cachedRequest.getRequestURI(), clientIp);
            reject(response, "Invalid internal token");
            return;
        }

        // 5. app_secret HMAC 签名校验（令牌认证和签名共用同一个密钥）
        String appSecret = appCredentialService.getAppSecret(appId);
        if (appSecret != null && !appSecret.isEmpty()) {
            if (!verifySignature(cachedRequest, appSecret)) {
                log.warn("内部 API 签名验证失败: app_id={}, uri={}, clientIp={}", appId, cachedRequest.getRequestURI(), clientIp);
                reject(response, "Invalid signature");
                return;
            }
        }

        // 6. 设置 AppContext
        AppContext.setAppId(appId);

        filterChain.doFilter(cachedRequest, response);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/internal/");
    }

    // ==================== 签名验证 ====================

    /**
     * HMAC-SHA256 签名验证
     *
     * @param request   HTTP 请求
     * @param appSecret 签名密钥（即 app_secret，令牌认证和签名共用）
     * @return 验证是否通过
     */
    private boolean verifySignature(HttpServletRequest request, String appSecret) {
        String timestampStr = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String signature = request.getHeader("X-Signature");

        if (timestampStr == null || nonce == null || signature == null) {
            log.warn("签名头缺失: timestamp={}, nonce={}, signature={}",
                    timestampStr != null, nonce != null, signature != null);
            return false;
        }

        // 时间戳校验
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            return false;
        }
        long drift = Math.abs(System.currentTimeMillis() - timestamp);
        if (drift > MAX_TIMESTAMP_DRIFT_MS) {
            log.warn("请求时间戳超出允许范围: drift={}ms", drift);
            return false;
        }

        // Nonce 防重放
        String nonceKey = NONCE_KEY_PREFIX + nonce;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(nonceKey, "1", 5, TimeUnit.MINUTES);
        if (isNew == null || !isNew) {
            log.warn("Nonce 已存在（疑似重放攻击）: nonce={}", nonce);
            return false;
        }

        // 读取请求体
        String body = "";
        try (BufferedReader reader = request.getReader()) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            body = sb.toString();
        } catch (IOException e) {
            log.warn("读取请求体失败: {}", e.getMessage());
        }

        // 计算签名
        String method = request.getMethod();
        String path = request.getRequestURI();
        String data = method + "|" + path + "|" + timestamp + "|" + nonce + "|" + body;
        String expected = computeHmac(data, appSecret);

        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算 HMAC-SHA256
     *
     * @param data 待签名数据
     * @param secret 签名密钥
     * @return 十六进制签名字符串
     */
    private String computeHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return CodecUtils.bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 计算失败", e);
        }
    }

    // ==================== IP 校验 ====================

    /**
     * 解析逗号分隔的网络列表
     */
    private List<String> parseNetworks(String networksStr) {
        if (networksStr == null || networksStr.isBlank()) {
            return List.of();
        }
        return List.of(networksStr.split(","));
    }

    /**
     * 检查 IP 是否在指定网络列表中
     */
    private boolean isIpInNetworks(String clientIp, List<String> networks) {
        if ("127.0.0.1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp)) {
            return true;
        }
        for (String network : networks) {
            String trimmed = network.trim();
            if (trimmed.contains("/") && matchCidr(clientIp, trimmed)) {
                return true;
            }
            if (trimmed.equals(clientIp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CIDR 匹配
     */
    private boolean matchCidr(String clientIp, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] clientBytes = java.net.InetAddress.getByName(clientIp).getAddress();
            byte[] networkBytes = java.net.InetAddress.getByName(networkAddress).getAddress();

            if (clientBytes.length != networkBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (clientBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < clientBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((clientBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取客户端 IP
     */
    private String getClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                int index = ip.indexOf(',');
                return index > 0 ? ip.substring(0, index).trim() : ip.trim();
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * 拒绝请求
     */
    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\"}");
    }

    // ==================== 可重复读取请求体 ====================

    /**
     * 可重复读取请求体的 HttpServletRequest 包装器。
     * <p>内部 API 过滤器需要先读取请求体计算签名，然后还要把同一个请求交给 Controller
     * 继续做 {@code @RequestBody} 反序列化，因此必须把 body 先缓存到内存中。</p>
     */
    private static final class CachedBodyHttpServletRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final byte[] cachedBody;

        /**
         * 构造可重复读取的请求包装器。
         *
         * @param request 原始请求
         * @throws IOException 读取请求体失败时抛出
         */
        private CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return inputStream.read();
                }

                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {
                    throw new UnsupportedOperationException("Async read listener is not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            java.nio.charset.Charset charset = encoding != null
                    ? java.nio.charset.Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            return new BufferedReader(new java.io.InputStreamReader(getInputStream(), charset));
        }
    }
}
