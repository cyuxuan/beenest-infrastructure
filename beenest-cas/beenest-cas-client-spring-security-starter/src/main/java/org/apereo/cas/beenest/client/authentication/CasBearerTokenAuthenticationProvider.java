package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.cache.BearerTokenCache;
import org.apereo.cas.beenest.client.cache.BearerAuthorityVersionService;
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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /** 等待并发刷新结果的最大时长（秒） */
    private static final long REFRESH_AWAIT_TIMEOUT_SECONDS = 30;

    private final CasTgtValidator tgtValidator;
    private final CasSecurityProperties properties;
    private final BearerTokenCache tokenCache;
    private final BearerTokenRevocationService revocationService;
    private final CasUserDetailsService userDetailsService;
    private final CasTokenRefresher tokenRefresher;
    private final BearerAuthorityVersionService authorityVersionService;

    /**
     * 正在执行中的刷新请求去重映射。
     * <p>
     * Key: refreshToken，Value: 代表刷新结果的 CompletableFuture。
     * 通过 {@link ConcurrentHashMap#putIfAbsent} 保证同一个 refreshToken
     * 同时只有一个线程执行实际刷新，其余线程等待并复用结果。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Authentication>> inflightRefreshes = new ConcurrentHashMap<>();

    public CasBearerTokenAuthenticationProvider(CasTgtValidator tgtValidator,
                                                 CasSecurityProperties properties,
                                                 BearerTokenCache tokenCache,
                                                 BearerTokenRevocationService revocationService,
                                                 CasUserDetailsService userDetailsService,
                                                 CasTokenRefresher tokenRefresher) {
        this(tgtValidator, properties, tokenCache, revocationService, userDetailsService, tokenRefresher, null);
    }

    public CasBearerTokenAuthenticationProvider(CasTgtValidator tgtValidator,
                                                 CasSecurityProperties properties,
                                                 BearerTokenCache tokenCache,
                                                 BearerTokenRevocationService revocationService,
                                                 CasUserDetailsService userDetailsService,
                                                 CasTokenRefresher tokenRefresher,
                                                 BearerAuthorityVersionService authorityVersionService) {
        this.tgtValidator = tgtValidator;
        this.properties = properties;
        this.tokenCache = tokenCache;
        this.revocationService = revocationService;
        this.userDetailsService = userDetailsService;
        this.tokenRefresher = tokenRefresher;
        this.authorityVersionService = authorityVersionService;
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
                String cachedVersion = resolveAuthorityVersion(cached);
                if (authorityVersionService != null
                        && authorityVersionService.isVersionStale(cached.getUserId(), cachedVersion)) {
                    LOGGER.debug("Bearer Token 权限版本已过期，准备重新校验: userId={}, token={}",
                            cached.getUserId(), accessToken);
                    tokenCache.remove(accessToken);
                } else {
                LOGGER.debug("Bearer Token 缓存命中");
                CasUserDetails cachedUserDetails = tokenCache.getUserDetails(accessToken);
                if (cachedUserDetails != null) {
                    return buildAuthenticatedToken(accessToken, null, cached, cachedUserDetails);
                }
                return buildAuthenticatedToken(accessToken, null, cached, null);
                }
            }
        }

        // 2. 远程验证 TGT
        CasUserSession session = tgtValidator.validate(accessToken);
        if (session != null) {
            // TGT 有效，构建认证结果并缓存
            session.setAuthTime(System.currentTimeMillis());
            return buildAuthenticatedToken(accessToken, null, session, null);
        }

        // 3. TGT 过期 — 通过 Singleflight 机制尝试无感刷新
        if (properties.getTokenAuth().isAutoRefreshEnabled() && refreshToken != null && !refreshToken.isBlank()) {
            LOGGER.debug("accessToken 过期，尝试使用 refreshToken 自动刷新");
            Authentication refreshed = singleflightRefresh(accessToken, refreshToken);
            if (refreshed != null) {
                return refreshed;
            }
        }

        // 4. 刷新失败或无 refreshToken，返回认证失败
        throw new BadCredentialsException("CAS TGT 验证失败: token 无效或已过期");
    }

    /**
     * Singleflight 刷新：保证同一个 refreshToken 同时只有一个线程执行实际刷新请求。
     * <p>
     * 通过 {@link ConcurrentHashMap#putIfAbsent} 注册刷新 Future，
     * 第一个线程负责执行实际的网络请求，后续并发线程等待第一个线程的结果。
     * <p>
     * 刷新成功后，不仅缓存新的 accessToken，还在旧 accessToken 的缓存位
     * 放入一个短 TTL 的迁移条目，让仍在使用旧 token 到达的请求可以直接命中。
     *
     * @param expiredAccessToken 过期的 accessToken（用于缓存迁移）
     * @param refreshToken       刷新令牌
     * @return 刷新成功返回已认证 Token，失败返回 null
     */
    private Authentication singleflightRefresh(String expiredAccessToken, String refreshToken) {
        CompletableFuture<Authentication> future = new CompletableFuture<>();
        CompletableFuture<Authentication> existing = inflightRefreshes.putIfAbsent(refreshToken, future);

        if (existing != null) {
            // 已有线程在刷新同一个 refreshToken，等待其结果
            LOGGER.debug("已有并发刷新正在进行，等待结果: refreshToken={}...", refreshToken.substring(0, Math.min(8, refreshToken.length())));
            try {
                return existing.get(REFRESH_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("等待并发刷新结果超时或异常: {}", e.getMessage());
                return null;
            }
        }

        // 当前线程负责执行实际刷新
        try {
            Authentication result = doRefresh(expiredAccessToken, refreshToken);
            future.complete(result);
            return result;
        } catch (Exception e) {
            future.complete(null);
            LOGGER.warn("Token 刷新执行失败: {}", e.getMessage());
            return null;
        } finally {
            inflightRefreshes.remove(refreshToken);
        }
    }

    /**
     * 执行实际的 Token 刷新网络请求。
     *
     * @param expiredAccessToken 过期的 accessToken（用于缓存迁移）
     * @param refreshToken       刷新令牌
     * @return 刷新成功返回已认证 Token，失败返回 null
     */
    private Authentication doRefresh(String expiredAccessToken, String refreshToken) {
        CasTokenRefresher.TokenRefreshResult result = tokenRefresher.refreshToken(refreshToken);
        if (result == null) {
            LOGGER.warn("Token 自动刷新失败");
            return null;
        }

        LOGGER.info("Token 自动刷新成功: userId={}", result.getSession().getUserId());

        // 缓存新的 accessToken
        result.getSession().setAuthTime(System.currentTimeMillis());

        Authentication authResult = buildAuthenticatedToken(
                result.getNewAccessToken(),
                result.getNewRefreshToken(),
                result.getSession(),
                null);

        // 将新 token 的认证结果也缓存到旧 accessToken 下，使仍在使用旧 token 的并发请求能直接命中
        if (tokenCache != null && expiredAccessToken != null) {
            tokenCache.put(expiredAccessToken, result.getSession(), authResult.getPrincipal() instanceof CasUserDetails cud ? cud : null);
        }

        return authResult;
    }

    /**
     * 从 CasUserSession 构建已认证的 Token
     *
     * @param accessToken     访问令牌
     * @param newRefreshToken 新的刷新令牌（仅刷新成功时有值，正常认证为 null）
     * @param session         用户会话信息
     */
    private Authentication buildAuthenticatedToken(String accessToken, String newRefreshToken,
                                                     CasUserSession session,
                                                     CasUserDetails cachedUserDetails) {
        CasUserDetails casUserDetails = cachedUserDetails;

        if (casUserDetails == null) {
            // 1. 构建 Assertion（CasUserDetailsService 需要从 Assertion 中获取属性）
            Map<String, Object> attributes = new HashMap<>();
            if (session.getAttributes() != null) {
                attributes.putAll(session.getAttributes());
            }
            AttributePrincipal principal = new AttributePrincipalImpl(session.getUserId(), attributes);
            Assertion assertion = new AssertionImpl(principal, attributes);

            // 2. 委托给 CasUserDetailsService 加载权限
            UserDetails userDetails = userDetailsService.loadUserByCasAssertion(session.getUserId(), assertion);

            // 3. 统一转换为 CasUserDetails，确保后续缓存结构稳定
            if (userDetails instanceof CasUserDetails cud) {
                casUserDetails = cud;
            } else {
                casUserDetails = new CasUserDetails(session, new ArrayList<>(userDetails.getAuthorities()));
            }
        }

        // 4. 回填本地缓存，后续同 token 请求可直接复用权限
        if (tokenCache != null) {
            tokenCache.put(accessToken, session, casUserDetails);
        }
        String currentVersion = resolveAuthorityVersion(casUserDetails.getCasUserSession());
        if (authorityVersionService != null && StringUtils.hasText(currentVersion)) {
            authorityVersionService.updateUserVersion(casUserDetails.getUserId(), currentVersion);
        }

        // 使用三参数构造函数，携带新的 refreshToken（如果有）
        if (newRefreshToken != null) {
            return new CasBearerTokenAuthenticationToken(accessToken, newRefreshToken, casUserDetails);
        }
        return new CasBearerTokenAuthenticationToken(accessToken, casUserDetails);
    }

    /**
     * 从会话属性中提取权限版本。
     *
     * @param session 用户会话
     * @return 权限版本，未配置或不存在时返回 null
     */
    private String resolveAuthorityVersion(CasUserSession session) {
        if (session == null || session.getAttributes() == null || session.getAttributes().isEmpty()) {
            return null;
        }
        String attributeKey = properties.getTokenAuth().getAuthorityVersionAttribute();
        if (!StringUtils.hasText(attributeKey)) {
            return null;
        }
        String value = session.getAttributes().get(attributeKey);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return CasBearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
