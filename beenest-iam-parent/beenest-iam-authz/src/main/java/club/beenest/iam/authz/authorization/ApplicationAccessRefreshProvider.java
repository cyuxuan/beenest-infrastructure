package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.persistence.entity.ApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.security.Principal;

/**
 * 刷新令牌的应用访问闸门。
 * <p>授权端点只在首次授权时判定可见性,刷新换票若不复核,收回授权后应用仍可凭刷新令牌
 * 无限续期——收回动作形同虚设。本 Provider 排在默认刷新 Provider 之前,只做否决:
 * 仍有权时返回 null 交给默认实现,判定不通过时以 invalid_grant 结束请求。</p>
 * <p>判定按库中当前授权关系重算,不使用授权记录里的权限快照:快照是签发时的旧值,
 * 授权被收回后不会自动更新。</p>
 */
public class ApplicationAccessRefreshProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAccessRefreshProvider.class);

    private final OAuth2AuthorizationService authorizationService;
    private final RegisteredClientRepository registeredClientRepository;
    private final ApplicationVisibilityService visibilityService;
    private final IamClientSessionService clientSessionService;
    private final SessionRevocationService revocationService;
    /** 是否启用闸门;关闭后回到"首次授权后不再复核"的旧行为 */
    private final boolean enabled;

    public ApplicationAccessRefreshProvider(OAuth2AuthorizationService authorizationService,
                                            RegisteredClientRepository registeredClientRepository,
                                            ApplicationVisibilityService visibilityService,
                                            IamClientSessionService clientSessionService,
                                            SessionRevocationService revocationService,
                                            boolean enabled) {
        this.authorizationService = authorizationService;
        this.registeredClientRepository = registeredClientRepository;
        this.visibilityService = visibilityService;
        this.clientSessionService = clientSessionService;
        this.revocationService = revocationService;
        this.enabled = enabled;
    }

    /**
     * 复核刷新令牌所属用户是否仍可使用目标应用。
     * <p>授权记录不存在、无用户主体或客户端未绑定应用时一律返回 null:这些场景要么由
     * 默认 Provider 给出标准错误,要么不存在门户可见性概念,本 Provider 不介入。</p>
     *
     * @param authentication 刷新令牌认证请求
     * @return 始终为 null(本 Provider 只否决,不负责换票)
     * @throws AuthenticationException 用户已无权使用该应用
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!enabled) {
            return null;
        }
        if (!(authentication instanceof OAuth2RefreshTokenAuthenticationToken refreshToken)) {
            return null;
        }
        OAuth2Authorization authorization = authorizationService.findByToken(
                refreshToken.getRefreshToken(), OAuth2TokenType.REFRESH_TOKEN);
        if (authorization == null) {
            // 授权记录不存在或已过期:默认 Provider 会按 invalid_grant 处理
            return null;
        }
        // 会话已登出或被顶下线:该授权签出的令牌不得再续期
        String sid = clientSessionService.resolveSessionId(authorization.getId());
        if (revocationService.isRevoked(sid)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                    "会话已结束,请重新登录", null));
        }
        IamUserPrincipal principal = principalOf(authorization);
        if (principal == null || principal.getRealmId() == null || principal.getUserId() == null) {
            // 机器对机器调用没有用户主体,不涉及门户可见性
            return null;
        }
        RegisteredClient client = registeredClientRepository.findById(authorization.getRegisteredClientId());
        if (client == null) {
            return null;
        }
        ApplicationService app = visibilityService.boundApplication(principal.getRealmId(), client);
        if (app == null) {
            return null;
        }
        if (visibilityService.canAccessByUserId(principal.getRealmId(), principal.getUserId(), app)) {
            // 仍有访问权:交给默认刷新 Provider 正常换票
            return null;
        }
        log.info("拒绝无权应用的刷新请求: realm={}, clientId={}, appCode={}, userId={}",
                principal.getRealmKey(), client.getClientId(), app.getAppCode(), principal.getUserId());
        throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_GRANT,
                "应用访问权限已收回", null));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2RefreshTokenAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /**
     * 从授权属性中取登录主体快照。
     *
     * @param authorization 授权记录
     * @return 登录主体;属性中不含本项目主体时返回 null
     */
    private static IamUserPrincipal principalOf(OAuth2Authorization authorization) {
        Object attribute = authorization.getAttribute(Principal.class.getName());
        if (attribute instanceof Authentication authentication
                && authentication.getPrincipal() instanceof IamUserPrincipal user) {
            return user;
        }
        return null;
    }
}
