package org.apereo.cas.beenest.client.authentication;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apereo.cas.beenest.client.details.CasUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Bearer Token 认证过滤器
 * <p>
 * 从 Authorization 头提取 Bearer Token，从 X-Refresh-Token 头提取刷新令牌，
 * 通过 AuthenticationManager 验证。位于 Spring Security Filter Chain 中
 * CasAuthenticationFilter 之前。
 * <p>
 * 无感刷新机制：
 * - 当 accessToken (TGT) 过期时，如果请求携带了 X-Refresh-Token 头，
 * Provider 会自动调用 CAS Server 刷新端点换取新 token
 * - 刷新成功后，通过响应头返回新 token 供客户端保存：
 *   <ul>
 *     <li>{@code X-New-Access-Token}: 新的 accessToken</li>
 *     <li>{@code X-New-Refresh-Token}: 新的 refreshToken</li>
 *     <li>{@code X-Token-Refreshed}: "true" 标识本次发生了刷新</li>
 *   </ul>
 * <p>
 * 错误处理策略：在内部 try/catch 中捕获 AuthenticationException，
 * 直接写入 401 JSON 响应并返回（不继续 Filter Chain），
 * 确保异常不会传播到 ExceptionTranslationFilter，避免触发 CAS 重定向。
 */
@Slf4j
public class CasBearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REFRESH_TOKEN_HEADER = "X-Refresh-Token";

    /**
     * 刷新成功后写入的响应头
     */
    private static final String NEW_ACCESS_TOKEN_HEADER = "X-New-Access-Token";
    private static final String NEW_REFRESH_TOKEN_HEADER = "X-New-Refresh-Token";
    private static final String TOKEN_REFRESHED_HEADER = "X-Token-Refreshed";

    private final Supplier<CasBearerTokenAuthenticationProvider> authenticationProviderSupplier;
    private final Supplier<AuthenticationManager> authenticationManagerSupplier;

    public CasBearerTokenAuthenticationFilter(Supplier<CasBearerTokenAuthenticationProvider> authenticationProviderSupplier,
                                              Supplier<AuthenticationManager> authenticationManagerSupplier) {
        this.authenticationProviderSupplier = authenticationProviderSupplier;
        this.authenticationManagerSupplier = authenticationManagerSupplier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 仅处理携带 Bearer Token 的请求
        String bearerToken = extractBearerToken(request);
        if (bearerToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // 已有认证则跳过
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 提取 refreshToken（可选）
        String refreshToken = request.getHeader(REFRESH_TOKEN_HEADER);

        // 验证 Token
        try {
            CasBearerTokenAuthenticationProvider authenticationProvider = resolveAuthenticationProvider();
            if (authenticationProvider != null) {
                Authentication result = authenticationProvider.authenticate(
                    new CasBearerTokenAuthenticationToken(bearerToken, refreshToken));
                SecurityContextHolder.getContext().setAuthentication(result);
                writeRefreshHeadersIfNeeded(response, result);
                log.debug("Bearer Token 认证成功: userId={}",
                    result.getPrincipal() instanceof CasUserDetails d ? d.getUserId() : "unknown");
                filterChain.doFilter(request, response);
                return;
            }

            AuthenticationManager authenticationManager = resolveAuthenticationManager();
            if (authenticationManager == null) {
                log.warn("CAS Bearer Token 过滤器无法获取 AuthenticationManager");
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":503,\"message\":\"Bearer 认证组件未初始化\",\"data\":null}");
                return;
            }

            Authentication result = authenticationManager.authenticate(
                new CasBearerTokenAuthenticationToken(bearerToken, refreshToken));
            SecurityContextHolder.getContext().setAuthentication(result);
            writeRefreshHeadersIfNeeded(response, result);
            log.debug("Bearer Token 认证成功: userId={}",
                result.getPrincipal() instanceof CasUserDetails d ? d.getUserId() : "unknown");
        } catch (AuthenticationException e) {
            // 关键：捕获异常，写入 401 响应，不传播到 ExceptionTranslationFilter
            log.debug("Bearer Token 认证失败: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            String message = e.getMessage() != null ? e.getMessage() : "Token 已过期或无效";
            String jsonResponse = String.format(
                "{\"code\":401,\"message\":\"%s\",\"data\":null}", message);
            response.getWriter().write(jsonResponse);
            return; // 不继续 Filter Chain
        } catch (RuntimeException e) {
            // 某些 Spring Security 代理/适配层可能把认证异常包装成运行时异常，
            // 这里统一收敛为 503，避免把内部实现细节泄露给客户端。
            log.error("Bearer Token 认证组件异常", e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":503,\"message\":\"Bearer 认证组件异常，请稍后重试\",\"data\":null}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 写入自动刷新后的响应头。
     *
     * @param response HTTP 响应
     * @param result   认证结果
     */
    private void writeRefreshHeadersIfNeeded(HttpServletResponse response, Authentication result) {
        if (result instanceof CasBearerTokenAuthenticationToken casToken
            && casToken.getRefreshToken() != null) {
            response.setHeader(NEW_ACCESS_TOKEN_HEADER, casToken.getAccessToken());
            response.setHeader(NEW_REFRESH_TOKEN_HEADER, casToken.getRefreshToken());
            response.setHeader(TOKEN_REFRESHED_HEADER, "true");
            log.debug("Token 已自动刷新并写入响应头: userId={}",
                result.getPrincipal() instanceof CasUserDetails d ? d.getUserId() : "unknown");
        }
    }

    /**
     * 解析 bearer 认证提供者。
     *
     * @return provider，不存在则返回 null
     */
    private CasBearerTokenAuthenticationProvider resolveAuthenticationProvider() {
        return authenticationProviderSupplier != null ? authenticationProviderSupplier.get() : null;
    }

    /**
     * 解析 AuthenticationManager。
     *
     * @return AuthenticationManager，若当前上下文尚未初始化则返回 null
     */
    private AuthenticationManager resolveAuthenticationManager() {
        return authenticationManagerSupplier != null ? authenticationManagerSupplier.get() : null;
    }

    /**
     * 从 Authorization 头提取 Bearer Token
     */
    private String extractBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
