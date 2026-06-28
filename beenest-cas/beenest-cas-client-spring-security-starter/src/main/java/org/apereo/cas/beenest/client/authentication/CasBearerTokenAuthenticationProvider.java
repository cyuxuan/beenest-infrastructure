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
import org.apereo.cas.beenest.client.accesscontrol.AccessControlResult;
import org.apereo.cas.beenest.client.accesscontrol.CasAccessControlManager;
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
 * 处理 {@link CasBearerTokenAuthenticationToken}，通过 CAS 原生票据验证器验证 TGT，
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

    /**
     * 等待并发刷新结果的最大时长（秒）
     */
    private static final long REFRESH_AWAIT_TIMEOUT_SECONDS = 30;

    private final CasNativeTicketValidator nativeTicketValidator;
    private final CasSecurityProperties properties;
    private final BearerTokenCache tokenCache;
    private final BearerTokenRevocationService revocationService;
    private final CasUserDetailsService userDetailsService;
    private final CasTokenRefresher tokenRefresher;
    private final BearerAuthorityVersionService authorityVersionService;

    /** 访问控制协调器（可选，未启用时为 null） */
    private final CasAccessControlManager accessControlManager;

    /**
     * 正在执行中的刷新请求去重映射。
     * <p>
     * Key: refreshToken，Value: 代表刷新结果的 CompletableFuture。
     * 通过 {@link ConcurrentHashMap#putIfAbsent} 保证同一个 refreshToken
     * 同时只有一个线程执行实际刷新，其余线程等待并复用结果。
     */
    private final ConcurrentHashMap<String, CompletableFuture<Authentication>> inflightRefreshes = new ConcurrentHashMap<>();

    /**
     * 正在执行中的 TGT 验证去重映射。
     * <p>
     * Key: accessToken（TGT），Value: 代表验证结果的 CompletableFuture。
     * 通过 {@link ConcurrentHashMap#putIfAbsent} 保证同一个 TGT
     * 同时只有一个线程执行远程验证（TGT→ST→serviceValidate），
     * 其余并发线程等待并复用结果，避免多个线程同时用同一 TGT 向 CAS 兑换 ST
     * 导致 ST 被提前消费而出现 INVALID_TICKET 错误。
     */
    private final ConcurrentHashMap<String, CompletableFuture<CasUserSession>> inflightValidations = new ConcurrentHashMap<>();

    public CasBearerTokenAuthenticationProvider(CasNativeTicketValidator nativeTicketValidator,
                                                CasSecurityProperties properties,
                                                BearerTokenCache tokenCache,
                                                BearerTokenRevocationService revocationService,
                                                CasUserDetailsService userDetailsService,
                                                CasTokenRefresher tokenRefresher) {
        this(nativeTicketValidator, properties, tokenCache, revocationService, userDetailsService, tokenRefresher, null);
    }

    public CasBearerTokenAuthenticationProvider(CasNativeTicketValidator nativeTicketValidator,
                                                CasSecurityProperties properties,
                                                BearerTokenCache tokenCache,
                                                BearerTokenRevocationService revocationService,
                                                CasUserDetailsService userDetailsService,
                                                CasTokenRefresher tokenRefresher,
                                                BearerAuthorityVersionService authorityVersionService) {
        this(nativeTicketValidator, properties, tokenCache, revocationService, userDetailsService, tokenRefresher, authorityVersionService, null);
    }

    public CasBearerTokenAuthenticationProvider(CasNativeTicketValidator nativeTicketValidator,
                                                CasSecurityProperties properties,
                                                BearerTokenCache tokenCache,
                                                BearerTokenRevocationService revocationService,
                                                CasUserDetailsService userDetailsService,
                                                CasTokenRefresher tokenRefresher,
                                                BearerAuthorityVersionService authorityVersionService,
                                                CasAccessControlManager accessControlManager) {
        this.nativeTicketValidator = nativeTicketValidator;
        this.properties = properties;
        this.tokenCache = tokenCache;
        this.revocationService = revocationService;
        this.userDetailsService = userDetailsService;
        this.tokenRefresher = tokenRefresher;
        this.authorityVersionService = authorityVersionService;
        this.accessControlManager = accessControlManager;
    }

    @SuppressWarnings("null")
    @Override
    public Authentication authenticate(Authentication authentication) {
        CasBearerTokenAuthenticationToken token = (CasBearerTokenAuthenticationToken) authentication;
        String accessToken = token.getAccessToken();
        String refreshToken = token.getRefreshToken();

        log.debug("[Bearer认证] 开始: accessToken={}..., hasRefreshToken={}",
            accessToken != null ? accessToken.substring(0, Math.min(20, accessToken.length())) : "null",
            refreshToken != null && !refreshToken.isBlank());

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
                    log.debug("[Bearer认证] 缓存命中但权限版本过期: userId={}", cached.getUserId());
                    tokenCache.remove(accessToken);
                } else {
                    log.debug("[Bearer认证] 缓存命中: userId={}", cached.getUserId());
                    CasUserDetails cachedUserDetails = tokenCache.getUserDetails(accessToken);
                    if (cachedUserDetails != null) {
                        return buildAuthenticatedToken(accessToken, null, cached, cachedUserDetails);
                    }
                    return buildAuthenticatedToken(accessToken, null, cached, null);
                }
            }
            log.debug("[Bearer认证] 缓存未命中");
        }

        // 2. 远程验证 TGT（Singleflight 去重，避免并发请求同时兑换 ST 导致 INVALID_TICKET）
        CasUserSession session = singleflightValidate(accessToken);
        if (session != null) {
            // TGT 有效，构建认证结果并缓存
            log.info("[Bearer认证] TGT 验证成功: userId={}", session.getUserId());
            session.setAuthTime(System.currentTimeMillis());
            return buildAuthenticatedToken(accessToken, null, session, null);
        }

        // 3. TGT 过期 — 通过 Singleflight 机制尝试无感刷新
        if (properties.getTokenAuth().isAutoRefreshEnabled() && refreshToken != null && !refreshToken.isBlank()) {
            log.info("[Bearer认证] TGT 验证失败，尝试 refreshToken 自动刷新");
            Authentication refreshed = singleflightRefresh(accessToken, refreshToken);
            if (refreshed != null) {
                return refreshed;
            }
        }

        // 4. 刷新失败或无 refreshToken，返回认证失败
        throw new BadCredentialsException("CAS TGT 验证失败: token 无效或已过期");
    }

    /**
     * Singleflight TGT 验证：保证同一个 accessToken 同时只有一个线程执行远程验证。
     * <p>
     * 问题背景：前端登录成功后可能并发发出多个 API 请求，这些请求携带同一个 TGT，
     * 如果多个线程同时调用 {@link CasNativeTicketValidator#validate}，
     * 会导致多个线程同时向 CAS 的 {@code POST /v1/tickets/{TGT}} 端点兑换 ST，
     * CAS 在处理并发请求时会更新 TGT 关联的 ST 列表，导致先创建的 ST 被删除，
     * 后续用该 ST 调 {@code /p3/serviceValidate} 时返回 {@code INVALID_TICKET}。
     * <p>
     * 解决方案：通过 {@link ConcurrentHashMap#putIfAbsent} 注册验证 Future，
     * 第一个线程负责执行实际的远程验证（TGT→ST→serviceValidate），
     * 后续并发线程等待第一个线程的结果并直接复用。
     *
     * @param accessToken TGT
     * @return 用户会话，验证失败返回 null
     */
    private CasUserSession singleflightValidate(String accessToken) {
        CompletableFuture<CasUserSession> future = new CompletableFuture<>();
        CompletableFuture<CasUserSession> existing = inflightValidations.putIfAbsent(accessToken, future);

        if (existing != null) {
            // 已有线程在验证同一个 TGT，等待其结果
            log.debug("[TGT验证] 已有并发验证正在进行，等待结果: accessToken={}...",
                accessToken.substring(0, Math.min(20, accessToken.length())));
            try {
                return existing.get(REFRESH_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[TGT验证] 等待并发验证结果超时或异常: {}", e.getMessage());
                return null;
            }
        }

        // 当前线程负责执行实际验证
        try {
            CasUserSession result = nativeTicketValidator.validate(accessToken);
            future.complete(result);
            return result;
        } catch (Exception e) {
            future.complete(null);
            log.warn("[TGT验证] 远程验证执行失败: {}", e.getMessage());
            return null;
        } finally {
            inflightValidations.remove(accessToken);
        }
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
            log.debug("已有并发刷新正在进行，等待结果: refreshToken={}...", refreshToken.substring(0, Math.min(8, refreshToken.length())));
            try {
                return existing.get(REFRESH_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("等待并发刷新结果超时或异常: {}", e.getMessage());
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
            log.warn("Token 刷新执行失败: {}", e.getMessage());
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
            log.warn("Token 自动刷新失败");
            return null;
        }

        log.info("Token 自动刷新成功: userId={}", result.getSession().getUserId());

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
    @SuppressWarnings("null")
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

        // 5. 访问控制 SPI 同步（如果启用）
        //    CAS 服务端 accessStrategy 已完成权限校验，此处只做本地用户状态同步：
        //    有 CAS 角色 → 创建/更新本地用户；无 CAS 角色 → 禁用本地用户。
        if (accessControlManager != null && accessControlManager.isEnabled() && casUserDetails != null) {
            Map<String, Object> casAttributes = casUserDetails.getCasUserSession().getAttributes();

            AccessControlResult acResult = accessControlManager.onAuthentication(
                casUserDetails.getUsername(), casAttributes);
            if (!acResult.granted()) {
                throw new BadCredentialsException(acResult.reason());
            }
        }

        // 6. 构造最终认证 Token
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
        Object value = session.getAttributes().get(attributeKey);
        if (value == null) {
            return null;
        }
        // 兼容 String 和 List 类型
        if (value instanceof java.util.List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            return first != null && StringUtils.hasText(first.toString()) ? first.toString().trim() : null;
        }
        return StringUtils.hasText(value.toString()) ? value.toString().trim() : null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return CasBearerTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
