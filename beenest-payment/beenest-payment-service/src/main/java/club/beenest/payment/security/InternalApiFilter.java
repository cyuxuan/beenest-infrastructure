package club.beenest.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 内部 API 安全过滤器
 * /internal/** 路径接受三层校验：
 * <ol>
 *   <li>内网 IP 白名单</li>
 *   <li>X-Internal-Token 静态令牌</li>
 *   <li>HMAC-SHA256 签名 + 时间戳 + 防重放（可选，配置 sign-secret 后启用）</li>
 * </ol>
 */
@Slf4j
@Component
public class InternalApiFilter extends OncePerRequestFilter {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_TIMESTAMP_DRIFT_MS = 5 * 60 * 1000; // 5 分钟
    private static final String NONCE_KEY_PREFIX = "internal:nonce:";

    private final String internalToken;
    private final List<String> internalNetworks;
    private final String signSecret;
    private final StringRedisTemplate redisTemplate;
    private final boolean trustProxy;

    public InternalApiFilter(
            @Value("${payment.internal.token:}") String internalToken,
            @Value("${payment.internal.allowed-networks:127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}") List<String> internalNetworks,
            @Value("${payment.internal.sign-secret:}") String signSecret,
            @Value("${payment.internal.trust-proxy:false}") boolean trustProxy,
            StringRedisTemplate redisTemplate) {
        this.internalToken = internalToken;
        this.internalNetworks = internalNetworks;
        this.signSecret = signSecret;
        this.trustProxy = trustProxy;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String clientIp = getClientIp(cachedRequest);

        // 1. 检查内网 IP
        if (!isInternalIp(clientIp)) {
            log.warn("内部 API 非法访问: uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
            reject(response, "Access denied");
            return;
        }

        // 2. 静态令牌校验
        if (internalToken != null && !internalToken.isEmpty()) {
            String requestToken = cachedRequest.getHeader("X-Internal-Token");
            if (!internalToken.equals(requestToken)) {
                log.warn("内部 API Token 验证失败: uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
                reject(response, "Invalid internal token");
                return;
            }
        } else {
            log.warn("【安全告警】内部 API 未配置 Token，仅依赖 IP 白名单校验，生产环境请配置 payment.internal.token");
        }

        // 3. HMAC 签名校验（配置 sign-secret 后启用）
        if (signSecret != null && !signSecret.isEmpty()) {
            if (!verifySignature(cachedRequest)) {
                log.warn("内部 API 签名验证失败: uri={}, clientIp={}", cachedRequest.getRequestURI(), clientIp);
                reject(response, "Invalid signature");
                return;
            }
        }

        filterChain.doFilter(cachedRequest, response);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/internal/");
    }

    private boolean verifySignature(HttpServletRequest request) {
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
        String expected = computeHmac(data);

        return constantTimeEquals(expected, signature);
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    signSecret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hashBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 计算失败", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) return false;
        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }
        return result == 0;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\"}");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String getClientIp(HttpServletRequest request) {
        // 只在明确信任代理头时才读取 X-Forwarded-For / X-Real-IP
        // 防止客户端伪造代理头绕过 IP 白名单
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

    private boolean isInternalIp(String clientIp) {
        if ("127.0.0.1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp)) {
            return true;
        }
        for (String network : internalNetworks) {
            if (network.contains("/") && matchCidr(clientIp, network)) {
                return true;
            }
        }
        return false;
    }

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
}
