package club.beenest.iam.authz.web;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamSessionPayload;
import club.beenest.iam.authz.session.IamUserSessionService;
import club.beenest.iam.authz.token.TokenStateCache;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 会话授权校验过滤器:每个带会话的请求复核账号状态与权限代次。
 * <p>浏览器会话与 Bearer 令牌共用同一套新鲜度规则:令牌每次验签都会读账号状态与令牌版本
 * (见 {@code IamTokenVersionValidator}),会话在本过滤器中复核同样的两个信号,按版本戳判定,不依赖定时器。</p>
 * <ul>
 *   <li>账号状态 + 令牌版本:账号被禁用、锁定、删除,或执行全端下线时,会话立即作废;</li>
 *   <li>权限代次:主体记录权限计算时的代次,当前代次变化时按新权限重算主体并就地刷新会话,
 *       会话不作废、用户不需重新登录。</li>
 * </ul>
 * <p>过滤器在安全上下文加载之后执行,以已认证主体为校验对象。
 * 会话作废时清空安全上下文,后续授权判定按未登录处理:接口返回 401,页面跳转登录入口点。</p>
 */
public class IamSessionGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IamSessionGuardFilter.class);

    /** 账号状态:正常 */
    private static final int STATUS_NORMAL = 1;

    private final IamUserSessionService userSessionService;
    private final TokenStateCache tokenStateCache;
    private final PermissionProvider permissionProvider;
    private final IamPrincipalFactory principalFactory;
    private final RealmProvider realmProvider;
    private final UserProvider userProvider;
    private final LoginEventPublisher loginEventPublisher;
    private final SecurityContextRepository securityContextRepository;

    public IamSessionGuardFilter(IamUserSessionService userSessionService,
                                 TokenStateCache tokenStateCache,
                                 PermissionProvider permissionProvider,
                                 IamPrincipalFactory principalFactory,
                                 RealmProvider realmProvider,
                                 UserProvider userProvider,
                                 LoginEventPublisher loginEventPublisher,
                                 SecurityContextRepository securityContextRepository) {
        this.userSessionService = userSessionService;
        this.tokenStateCache = tokenStateCache;
        this.permissionProvider = permissionProvider;
        this.principalFactory = principalFactory;
        this.realmProvider = realmProvider;
        this.userProvider = userProvider;
        this.loginEventPublisher = loginEventPublisher;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * 复核当前会话的新鲜度并滑动续期。
     * <p>无 sid 时直接放行;会话快照不存在(过期、已注销)时清空安全上下文后放行,按未登录处理。
     * 仅对表单登录建立的 {@link UsernamePasswordAuthenticationToken} 做复核,令牌请求不经过会话。
     * 会话存活期间刷新活跃时间,空闲超时依赖该时间滑动。</p>
     *
     * @param request     当前请求
     * @param response    当前响应
     * @param filterChain 过滤器链
     * @throws ServletException 后续过滤器抛出
     * @throws IOException      后续过滤器抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String sid = IamRedisSecurityContextRepository.currentSessionId(request);
        if (sid == null) {
            filterChain.doFilter(request, response);
            return;
        }
        // 安全上下文仓库已读过的快照直接复用,不重复访问 Redis
        IamSessionPayload payload = IamRedisSecurityContextRepository.currentPayload(request);
        if (payload == null) {
            payload = userSessionService.loadSession(sid);
        }
        if (payload == null) {
            SecurityContextHolder.clearContext();
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof UsernamePasswordAuthenticationToken
                && authentication.getPrincipal() instanceof IamUserPrincipal principal) {
            // 超过绝对最大时长的会话不再随活跃续期
            if (payload.exceededMaxLifetime(System.currentTimeMillis())) {
                terminateSession(sid, payload, "会话超过最大存续时长");
            } else if (isAuthorizationRevoked(principal)) {
                terminateSession(sid, payload, "账号状态或令牌版本已变更,会话终止");
            } else {
                refreshPermissionsIfStale(request, response, sid, payload, principal);
            }
        }
        userSessionService.touchSession(sid);
        filterChain.doFilter(request, response);
    }

    /**
     * 账号状态或令牌版本是否已使当前会话失效。
     * <p>判定与令牌路径一致:账号非正常状态,或令牌版本高于登录时记录的版本(全端下线)。</p>
     *
     * @param principal 会话中的用户主体
     * @return true 表示会话应立即作废;主体无用户主键时为 false
     */
    private boolean isAuthorizationRevoked(IamUserPrincipal principal) {
        Long userId = principal.getUserId();
        if (userId == null) {
            return false;
        }
        int status = tokenStateCache.status(userId);
        if (status != STATUS_NORMAL) {
            return true;
        }
        return tokenStateCache.tokenVersion(userId) > principal.getTokenVersion();
    }

    /**
     * 权限代次变化时重算权限并就地刷新会话。
     * <p>刷新而不是踢下线:用户不必重新登录,权限却已经是新的。
     * 主体由工厂统一构建,与登录时同一套口径。</p>
     */
    private void refreshPermissionsIfStale(HttpServletRequest request, HttpServletResponse response,
                                           String sid, IamSessionPayload payload, IamUserPrincipal principal) {
        Long realmId = principal.getRealmId();
        if (realmId == null || principal.getUserId() == null) {
            return;
        }
        long currentEpoch = permissionProvider.currentPermissionEpoch(realmId);
        if (currentEpoch == principal.getPermissionEpoch()) {
            return;
        }
        RealmModel realm = realmProvider.findById(realmId).orElse(null);
        UserModel user = userProvider.findById(principal.getUserId()).orElse(null);
        if (realm == null || user == null || user.status() != STATUS_NORMAL) {
            terminateSession(sid, payload, "账号不存在或已停用,会话终止");
            return;
        }
        // 权限刷新不是重新登录:沿用本次会话的认证方式。
        // 重建出的主体只用于本次请求(已有 sid 时不会写回快照),
        // 若准入过滤器在本请求上运行,丢掉 loginType 会被误判为"方式未知"而多弹一次补验
        IamUserPrincipal refreshed = principalFactory.build(realm, user, principal.getLoginType());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(principalFactory.toAuthentication(refreshed));
        SecurityContextHolder.setContext(context);
        // 立即写回:响应提交时上下文才会落库,这里先写一次,避免窗口内拿到旧权限
        securityContextRepository.saveContext(context, request, response);
        log.info("会话权限已按新代次刷新: userId={}, realmId={}, 代次 {} → {}",
                principal.getUserId(), realmId, principal.getPermissionEpoch(), refreshed.getPermissionEpoch());
    }

    /**
     * 作废会话。
     * <p>先取终止原因再删快照:原因随登录事件落库,用于区分账号被禁用与会话超时两类下线。
     * 作废失败只记日志。</p>
     *
     * @param sid     会话标识
     * @param payload 会话快照,提供发布事件所需的主体信息
     * @param reason  终止原因
     */
    private void terminateSession(String sid, IamSessionPayload payload, String reason) {
        try {
            IamUserPrincipal principal = payload.principal();
            userSessionService.expireSession(sid);
            SecurityContextHolder.clearContext();
            if (principal != null) {
                loginEventPublisher.publish(principal.getRealmKey(), principal.getUserId(),
                        principal.getUsername(), LoginEventEvent.SESSION_TIMEOUT, "SESSION",
                        null, null, reason);
            }
            log.info("会话已作废: sid={}, reason={}", sid, reason);
        } catch (Exception e) {
            log.warn("会话作废失败: sid={}, error={}", sid, e.getMessage());
        }
    }

}
