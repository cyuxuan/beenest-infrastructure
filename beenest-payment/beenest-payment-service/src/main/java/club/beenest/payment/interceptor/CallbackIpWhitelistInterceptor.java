package club.beenest.payment.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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
 *
 * @author System
 * @since 2026-04-08
 */
@Slf4j
@Component
public class CallbackIpWhitelistInterceptor implements HandlerInterceptor {

    private volatile List<String> allowedIps = Collections.emptyList();
    private volatile boolean enabled = false;

    @Value("${payment.callback.allowed-ips:}")
    public void setAllowedIps(String allowedIpsConfig) {
        if (allowedIpsConfig != null && !allowedIpsConfig.isBlank()) {
            this.allowedIps = Arrays.stream(allowedIpsConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            this.enabled = !this.allowedIps.isEmpty();
            log.info("支付回调IP白名单已加载 - 规则数: {}, 白名单: {}", this.allowedIps.size(), this.allowedIps);
        } else {
            log.info("支付回调IP白名单未配置，不做拦截");
        }
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {
        if (!enabled) {
            return true;
        }

        String clientIp = resolveClientIp(request);

        if (clientIp == null || clientIp.isEmpty()) {
            log.warn("回调请求无法获取客户端IP - URI: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write("Forbidden: unable to resolve client IP");
            return false;
        }

        if (isIpAllowed(clientIp)) {
            return true;
        }

        log.warn("【安全告警】回调请求IP不在白名单中 - IP: {}, URI: {}", clientIp, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.getWriter().write("Forbidden: IP not in whitelist");
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        // 按优先级读取代理转发的真实IP
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能有多个值，取第一个
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isIpAllowed(String clientIp) {
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
