package club.beenest.payment.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 支付回调 IP 白名单拦截器
 * 仅对回调端点生效，防止攻击者伪造支付回调请求
 *
 * <p>
 * 配置项：payment.callback.allowed-ips（逗号分隔的 IP 或 CIDR）
 * </p>
 * <p>
 * 如果未配置白名单（空字符串），则不做拦截（兼容开发环境）
 * </p>
 * <p>
 * 默认不信任代理头（X-Forwarded-For / X-Real-IP），防止客户端伪造代理头绕过白名单。
 * 仅在确认前方有受信任的反向代理（如 Nginx）且正确设置了代理头时，才应启用 trust-proxy。
 * </p>
 *
 * @author System
 * @since 2026-04-08
 */
@Slf4j
@Component
public class CallbackIpWhitelistInterceptor implements HandlerInterceptor {

    /**
     * 白名单配置快照，使用 AtomicReference 保证线程安全的原子更新。
     * 三个字段（allowedIps、enabled、trustProxy）封装在不可变 record 中，
     * 确保读取时不会看到不一致的中间状态。
     */
    private final AtomicReference<WhitelistConfig> config = new AtomicReference<>(WhitelistConfig.DEFAULT);

    /**
     * 白名单配置不可变快照
     */
    private record WhitelistConfig(List<String> allowedIps, boolean enabled, boolean trustProxy) {
        static final WhitelistConfig DEFAULT = new WhitelistConfig(List.of(), false, false);
    }

    @Value("${payment.callback.allowed-ips:}")
    public void setAllowedIps(String allowedIpsConfig) {
        if (allowedIpsConfig != null && !allowedIpsConfig.isBlank()) {
            List<String> parsed = Arrays.stream(allowedIpsConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            this.config.set(new WhitelistConfig(parsed, !parsed.isEmpty(), this.config.get().trustProxy()));
            log.info("支付回调IP白名单已加载 - 规则数: {}, 白名单: {}", parsed.size(), parsed);
        } else {
            this.config.set(new WhitelistConfig(List.of(), false, this.config.get().trustProxy()));
            log.info("支付回调IP白名单未配置，不做拦截");
        }
    }

    @Value("${payment.callback.trust-proxy:false}")
    public void setTrustProxy(boolean trustProxy) {
        this.config.set(new WhitelistConfig(this.config.get().allowedIps(), this.config.get().enabled(), trustProxy));
        log.info("支付回调IP白名单 trust-proxy: {}", trustProxy);
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {
        WhitelistConfig current = this.config.get();
        if (!current.enabled()) {
            return true;
        }

        String clientIp = resolveClientIp(request, current.trustProxy());

        if (clientIp == null || clientIp.isEmpty()) {
            log.warn("回调请求无法获取客户端IP - URI: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("Forbidden: unable to resolve client IP");
            return false;
        }

        if (isIpAllowed(clientIp, current.allowedIps())) {
            return true;
        }

        log.warn("【安全告警】回调请求IP不在白名单中 - IP: {}, URI: {}", clientIp, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.getWriter().write("Forbidden: IP not in whitelist");
        return false;
    }

    private String resolveClientIp(HttpServletRequest request, boolean trustProxy) {
        // 只在明确信任代理头时才读取，防止客户端伪造代理头绕过 IP 白名单
        if (trustProxy) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private boolean isIpAllowed(String clientIp, List<String> allowedIps) {
        for (String allowed : allowedIps) {
            if (matchIp(allowed, clientIp)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 支持精确 IP 匹配和 CIDR 网段匹配
     */
    private boolean matchIp(String pattern, String clientIp) {
        // 精确匹配
        if (!pattern.contains("/")) {
            return pattern.equals(clientIp);
        }

        // CIDR 匹配
        try {
            String[] parts = pattern.split("/");
            String networkIp = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] networkBytes = java.net.InetAddress.getByName(networkIp).getAddress();
            byte[] clientBytes = java.net.InetAddress.getByName(clientIp).getAddress();

            if (networkBytes.length != clientBytes.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != clientBytes[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < networkBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((networkBytes[fullBytes] & mask) != (clientBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.warn("CIDR匹配异常 - pattern: {}, clientIp: {}", pattern, clientIp, e);
            return false;
        }
    }
}
