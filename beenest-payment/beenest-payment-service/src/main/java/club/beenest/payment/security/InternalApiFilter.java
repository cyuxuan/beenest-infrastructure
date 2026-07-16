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
 * <p>/internal/** 路径接受校验，支持两种模式：</p>
 *
 * <h4>模式一：App 凭证模式（推荐，X-App-Id 头存在时启用）</h4>
 * <ol>
 *   <li>per-app IP 白名单校验（allowed_networks 非空时生效，支持 CIDR 网段；为空时不做 IP 限制）</li>
 *   <li>BCrypt 验证 X-Internal-Token（使用 app 独立的 app_secret）</li>
 *   <li>HMAC-SHA256 签名校验（使用 app 独立的 sign_secret，含时间戳 + Nonce 防重放）</li>
 * </ol>
 *
 * <h4>模式二：全局密钥模式（向后兼容，X-App-Id 头不存在时回退）</h4>
 * <ol>
 *   <li>全局 IP 白名单校验</li>
 *   <li>静态令牌校验（payment.internal.token）</li>
 *   <li>HMAC-SHA256 签名校验（payment.internal.sign-secret）</li>
 * </ol>
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

    // 全局配置（向后兼容，无 X-App-Id 头时使用）
    private final String globalInternalToken;
    private final List<String> globalInternalNetworks;
    private final String globalSignSecret;
    private final String defaultAppId;
    private final boolean trustProxy;

    public InternalApiFilter(
            AppCredentialService appCredentialService,
            @Value("${payment.internal.token:}") String globalInternalToken,
            @Value("${payment.internal.allowed-networks:127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}") List<String> globalInternalNetworks,
            @Value("${payment.internal.sign-secret:}") String globalSignSecret,
            @Value("${payment.internal.default-app-id:DRONE}") String defaultAppId,
            @Value("${payment.internal.trust-proxy:false}") boolean trustProxy,
            StringRedisTemplate redisTemplate) {
        this.appCredentialService = appCredentialService;
        this.globalInternalToken = globalInternalToken;
        this.globalInternalNetworks = globalInternalNetworks;
        this.globalSignSecret = globalSignSecret;
        this.defaultAppId = defaultAppId;
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

        if (appIdHeader != null && !appIdHeader.isBlank()) {
            // ==================== 模式一：App 凭证模式 ====================
            AppCredential credential = appCredentialService.getByAppId(appIdHeader.trim());

            // 1. 检查 app 是否存在且活跃
            if (credential == null) {
                log.warn("内部 API 非法访问: app_id={} 不存在, uri={}, clientIp={}", appIdHeader, cachedRequest.getRequestURI(), clientIp);
                reject(response, "Unknown app");
                return;
            }
            if (!"ACTIVE".equals(credential.getStatus())) {
                log.warn("内部 API 非法访问: app_id={} 已禁用, uri={}, clientIp={}", appIdHeader, cachedRequest.getRequestURI(), clientIp);
                reject(response, "App disabled");
                return;
            }

            // 2. per-app IP 白名单校验（非空时生效，为空时不做 IP 限制）
            String allowedNetworks = credential.getAllowedNetworks();
            if (allowedNetworks != null && !allowedNetworks.isBlank()) {
                List<String> networks = parseNetworks(allowedNetworks);
                if (!isIpInNetworks(clientIp, networks)) {
                    log.warn("内部 API 非法访问: app_id={} IP 不在白名单, uri={}, clientIp={}", appIdHeader, cachedRequest.getRequestURI(), clientIp);
                    reject(response, "Access denied");
                    return;
                }
            }
            // allowed_networks 为空时不做 IP 限制，所有 IP 可访问

            // 3. BCrypt 验证 X-Internal-Token
            String requestToken = cachedRequest.getHeader("X-Internal-Token");
            if (!appCredentialService.verifyAppSecret(appIdHeader.trim(), requestToken)) {
                log.warn("内部 API Token 验证失败: app_id={}, uri={}, clientIp={}", appIdHeader, cachedRequest.getRequestURI(), clientIp);
                reject(response, "Invalid internal token");
                return;
            }

            // 4. HMAC 签名校验（需要明文 sign_secret 验证）
            //    BCrypt 无法用于 HMAC 验证，因为 HMAC 需要用明文密钥重新计算签名
            //    策略：先 BCrypt 验证 token 通过，再使用请求头中的签名进行验证
            //    此处仍然使用全局签名密钥做兼容，后续可考虑将 sign_secret 也改为 AES 加密存储
            // TODO: Phase 2 — 将 sign_secret 也改为 AES 加密存储，支持 per-app HMAC 签名
            if (globalSignSecret != null && !globalSignSecret.isEmpty()) {
                if (!verifySignature(cachedRequest, globalSignSecret)) {
                    log.warn("内部 API 签名验证失败: app_id={}, uri={}, clientIp={}", appIdHeader, cachedRequest.getRequestURI(), clientIp);
                    reject(response, "Invalid signature");
                    return;
                }
            }

            // 5. 设置 AppContext
            AppContext.setAppId(appIdHeader.trim());

        } else {
            // ==================== 模式二：全局密钥模式（向后兼容） ====================

            // 1. 检查内网 IP
            if (!isIpInNetworks(clientIp, globalInternalNetworks)) {
                log.warn("内部 API 非法访问: uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
                reject(response, "Access denied");
                return;
            }

            // 2. 静态令牌校验（使用常量时间比较防止时序攻击）
            if (globalInternalToken != null && !globalInternalToken.isEmpty()) {
                String requestToken = cachedRequest.getHeader("X-Internal-Token");
                if (requestToken == null || !MessageDigest.isEqual(
                        globalInternalToken.getBytes(StandardCharsets.UTF_8),
                        requestToken.getBytes(StandardCharsets.UTF_8))) {
                    log.warn("内部 API Token 验证失败: uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
                    reject(response, "Invalid internal token");
                    return;
                }
            } else {
                log.warn("【安全告警】内部 API 未配置 Token，仅依赖 IP 白名单校验，生产环境请配置 payment.internal.token");
            }

            // 3. HMAC 签名校验
            if (globalSignSecret != null && !globalSignSecret.isEmpty()) {
                if (!verifySignature(cachedRequest, globalSignSecret)) {
                    log.warn("内部 API 签名验证失败: uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
                    reject(response, "Invalid signature");
                    return;
                }
            }

            // 4. 设置默认 AppContext
            AppContext.setAppId(defaultAppId);
        }

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
     * @param signSecret 签名密钥（明文）
     * @return 验证是否通过
     */
    private boolean verifySignature(HttpServletRequest request, String signSecret) {
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
        String expected = computeHmac(data, signSecret);

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
