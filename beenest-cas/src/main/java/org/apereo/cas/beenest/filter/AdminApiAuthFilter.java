package org.apereo.cas.beenest.filter;

import org.apereo.cas.beenest.common.constant.CasConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Admin API 认证过滤器
 * <p>
 * 校验 {@code /cas/admin/*} 路径的请求头中是否携带有效的 Admin Token。
 * Token 由 CAS Server 启动时通过环境变量 {@code CAS_ADMIN_TOKEN} 配置，
 * 也可存储在 Redis 中用于动态管理。
 * <p>
 * 认证方式：请求头 {@code X-CAS-Admin-Token} 携带 token，
 * 与配置值或 Redis 中 {@code cas:admin:token:<token>} 对比验证。
 */
@Slf4j
public class AdminApiAuthFilter extends OncePerRequestFilter {

    private final String adminToken;
    private final StringRedisTemplate redisTemplate;

    public AdminApiAuthFilter(String adminToken, StringRedisTemplate redisTemplate) {
        this.adminToken = adminToken;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String requestToken = request.getHeader(CasConstant.ADMIN_TOKEN_HEADER);

        if (!isValidToken(requestToken)) {
            LOGGER.warn("Admin API 未授权访问: path={}, remoteAddr={}",
                    request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"管理接口未授权\",\"data\":null}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 验证 Token 有效性
     * <p>
     * 优先校验配置的静态 Token，其次校验 Redis 中的动态 Token。
     */
    private boolean isValidToken(String requestToken) {
        if (StringUtils.isBlank(requestToken)) {
            return false;
        }
        // 1. 与配置的 Admin Token 比对
        if (StringUtils.isNotBlank(adminToken) && adminToken.equals(requestToken)) {
            return true;
        }
        // 2. 与 Redis 中的动态 Token 比对
        if (redisTemplate != null) {
            String redisKey = CasConstant.REDIS_ADMIN_TOKEN_PREFIX + requestToken;
            return Boolean.TRUE.equals(redisTemplate.hasKey(redisKey));
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        String path = request.getServletPath();
        return !path.startsWith("/admin/");
    }
}
