package club.beenest.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 第三方回调 IP 白名单过滤器
 * 仅对 /api/wallet/payment/callback/** 路径生效
 * 白名单配置在 application.yml: payment.callback.allowed-ips
 *
 * <p>安全设计：</p>
 * <ul>
 *   <li>默认不信任代理头（X-Forwarded-For / X-Real-IP），防止伪造绕过白名单</li>
 *   <li>仅在 payment.callback.trust-proxy=true 时才读取代理头</li>
 *   <li>白名单未配置时拒绝所有回调请求</li>
 * </ul>
 */
@Slf4j
@Component
public class CallbackIpWhitelistFilter extends OncePerRequestFilter {

    private final List<String> allowedIps;
    private final boolean trustProxy;

    public CallbackIpWhitelistFilter(
            @Value("${payment.callback.allowed-ips:}") List<String> allowedIps,
            @Value("${payment.callback.trust-proxy:false}") boolean trustProxy) {
        this.allowedIps = allowedIps;
        this.trustProxy = trustProxy;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String clientIp = getClientIp(request);

        if (!isIpAllowed(clientIp)) {
            log.warn("回调 IP 白名单拦截: uri={}, clientIp={}", request.getRequestURI(), clientIp);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"IP not whitelisted\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/wallet/payment/callback/");
    }

    private boolean isIpAllowed(String clientIp) {
        if (allowedIps == null || allowedIps.isEmpty()) {
            // 未配置白名单时拒绝所有回调，防止伪造支付回调攻击
            log.error("【安全告警】回调 IP 白名单未配置，拒绝回调请求。请配置 payment.callback.allowed-ips");
            return false;
        }

        for (String allowed : allowedIps) {
            if (matchIp(clientIp, allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchIp(String clientIp, String pattern) {
        if (pattern.contains("/")) {
            return matchCidr(clientIp, pattern);
        }
        if ("localhost".equals(pattern) || "127.0.0.1".equals(pattern)) {
            return "127.0.0.1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp);
        }
        return pattern.equals(clientIp);
    }

    private boolean matchCidr(String clientIp, String cidr) {
        try {
            String[] parts = cidr.split("/");
            String networkAddress = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            byte[] clientBytes = InetAddress.getByName(clientIp).getAddress();
            byte[] networkBytes = InetAddress.getByName(networkAddress).getAddress();

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
        } catch (UnknownHostException e) {
            log.warn("CIDR 解析失败: cidr={}, clientIp={}", cidr, clientIp, e);
            return false;
        }
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
}
