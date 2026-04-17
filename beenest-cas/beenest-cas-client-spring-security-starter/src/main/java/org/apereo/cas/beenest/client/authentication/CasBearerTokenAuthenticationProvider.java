package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerTokenRevocationService;
import org.apereo.cas.beenest.client.config.CasSecurityProperties;
import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.apereo.cas.beenest.client.session.CasUserSession;
import lombok.extern.slf4j.Slf4j;
import org.apereo.cas.client.authentication.AttributePrincipal;
import org.apereo.cas.client.authentication.AttributePrincipalImpl;
import org.apereo.cas.client.validation.Assertion;
import org.apereo.cas.client.validation.AssertionImpl;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bearer Token 认证提供者
 * <p>
 * 处理 {@link CasBearerTokenAuthenticationToken}，通过 CasTgtValidator 验证 TGT，
 * 委托 CasUserDetailsService 加载权限。
 * <p>
 * 无感刷新：当 accessToken (TGT) 过期时，如果 Token 中携带了 refreshToken，
 * 自动调用 {@link CasTokenRefresher} 刷新 token 并返回新的认证结果。
 * 刷新成功后，新的 accessToken 会通过响应头返回给客户端保存。
 * <p>
 * 错误处理策略：验证失败时抛出 BadCredentialsException，
 * 由 CasBearerTokenAuthenticationFilter 捕获并写入 401 JSON 响应。
 */
@Slf4j
public class CasBearerTokenAuthenticationProvider implements AuthenticationProvider {

    private final CasTgtValidator tgtValidator;
    private final CasSecurityProperties properties;
    private final BearerTokenCache tokenCache;
    private final BearerTokenRevocationService revocationService;
    private final CasUserDetailsService userDetailsService;
    private final CasTokenRefresher tokenRefresher;

    public CasBearerTokenAuthenticationProvider(CasTgtValidator tgtValidator,
                                                 CasSecurityProperties properties,
                                                 BearerTokenCache tokenCache,
                                                 BearerTokenRevocationService revocationService,
                                                 CasUserDetailsService userDetailsService,
                                                 CasTokenRefresher tokenRefresher) {
        this.tgtValidator = tgtValidator;
        this.properties = properties;
        this.tokenCache = tokenCache;
        this.revocationService = revocationService;
        this.userDetailsService = userDetailsService;
        this.tokenRefresher = tokenRefresher;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        CasBearerTokenAuthenticationToken token = (CasBearerTokenAuthenticationToken) authentication;
        String accessToken = token.getAccessToken();
        String refreshToken = token.getRefreshToken();

        // 0. 先检查撤销态，避免已注销 token 命中本地缓存或触发远程刷新
        if (revocationService != null && revocationService.isAccessTokenRevoked(accessToken)) {
            throw new BadCredentialsException("CAS accessToken 已注销，请重新登录");
        }
        if (refreshToken != null && !refreshToken.isBlank()
                && revocationService != null
                && revocationService.isRefreshTokenRevoked(refreshToken)) {
            throw new BadCredentialsException("CAS refreshToken 已注销，请重新登录");
        }

        // 1. 查缓存
        if (tokenCache != null) {
            CasUserSession cached = tokenCache.get(accessToken);
            if (cached != null) {
                LOGGER.debug("Bearer Token 缓存命中");
                return buildAuthenticatedToken(accessToken, null, cached);
            }
        }

        // 2. 远程验证 TGT
        CasUserSession session = tgtValidator.validate(accessToken);
        if (session != null) {
            // TGT 有效，缓存并返回
            if (tokenCache != null) {
                session.setAuthTime(System.currentTimeMillis());
                tokenCache.put(accessToken, session);
            }
            return buildAuthenticatedToken(accessToken, null, session);
        }

        // 3. TGT 过期 — 尝试无感刷新
        if (properties.getTokenAuth().isAutoRefreshEnabled() && refreshToken != null && !refreshToken.isBlank()) {
            LOGGER.debug("accessToken 过期，尝试使用 refreshToken 自动刷新");
            Authentication refreshed = tryRefreshToken(refreshToken);
            if (refreshed != null) {
                return refreshed;
            }
        }

        // 4. 刷新失败或无 refreshToken，返回认证失败
        throw new BadCredentialsException("CAS TGT 验证失败: token 无效或已过期");
    }

    /**
     * 使用 refreshToken 尝试刷新
     * <p>
     * 调用 CAS Server refresh 端点换取新的 accessToken + refreshToken，
     * 成功后缓存新 token 并构建已认证 Token（携带新的 refreshToken）。
     *
     * @param refreshToken 旧的刷新令牌
     * @return 刷新成功返回已认证 Token，失败返回 null
     */
    private Authentication tryRefreshToken(String refreshToken) {
        CasTokenRefresher.TokenRefreshResult result = tokenRefresher.refreshToken(refreshToken);
        if (result == null) {
            LOGGER.warn("Token 自动刷新失败");
            return null;
        }

        LOGGER.info("Token 自动刷新成功: userId={}", result.getSession().getUserId());

        // 缓存新的 accessToken
        if (tokenCache != null) {
            result.getSession().setAuthTime(System.currentTimeMillis());
            tokenCache.put(result.getNewAccessToken(), result.getSession());
        }

        // 构建已认证 Token，携带新的 refreshToken（供 Filter 写入响应头）
        return buildAuthenticatedToken(
                result.getNewAccessToken(),
                result.getNewRefreshToken(),
                result.getSession());
    }

    /**
     * 从 CasUserSession 构建已认证的 Token
     *
     * @param accessToken     访问令牌
     * @param newRefreshToken 新的刷新令牌（仅刷新成功时有值，正常认证为 null）
     * @param session         用户会话信息
     */
    private Authentication buildAuthenticatedToken(String accessToken, String newRefreshToken,
                                                     CasUserSession session) {
        // 构建 Assertion（CasUserDetailsService 需要从 Assertion 中获取属性）
        Map<String, Object> attributes = new HashMap<>();
        if (session.getAttributes() != null) {
            attributes.putAll(session.getAttributes());
        }
        AttributePrincipal principal = new AttributePrincipalImpl(session.getUserId(), attributes);
        Assertion assertion = new AssertionImpl(principal, attributes);

        // 委托给 CasUserDetailsService 加载权限
        UserDetails userDetails = userDetailsService.loadUserByCasAssertion(session.getUserId(), assertion);

        // 构建 CasUserDetails
        CasUserDetails casUserDetails;
        if (userDetails instanceof CasUserDetails cud) {
            casUserDetails = cud;
        } else {
            casUserDetails = new CasUserDetails(session, new ArrayList<>(userDetails.getAuthorities()));
        }

        // 使用三参数构造函数，携带新的 refreshToken（如果有）
        if (newRefreshToken != null) {
            return new CasBearerTokenAuthenticationToken(accessToken, newRefreshToken, casUserDetails);
        }
        return new CasBearerTokenAuthenticationToken(accessToken, casUserDetails);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return CasBearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
