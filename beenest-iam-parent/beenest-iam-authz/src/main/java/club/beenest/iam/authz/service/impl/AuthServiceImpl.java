package club.beenest.iam.authz.service.impl;

import club.beenest.iam.authz.authentication.IamAuthenticationProvider;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.authz.logout.IamLogoutService;
import club.beenest.iam.authz.token.TokenStateCache;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.i18n.IamMessages;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现。
 * <p>登录复用容器内的认证管理器,与表单登录行为完全一致;
 * MFA 第二步在动态口令校验通过后调用 {@link #completeMfaLogin} 构建主体。</p>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserProvider userProvider;
    private final RealmProvider realmProvider;
    private final IamAuthenticationProvider authenticationProvider;
    private final IamLogoutService logoutService;
    private final TokenStateCache tokenStateCache;

    public AuthServiceImpl(AuthenticationManager authenticationManager, UserProvider userProvider,
                           RealmProvider realmProvider, IamAuthenticationProvider authenticationProvider,
                           IamLogoutService logoutService, TokenStateCache tokenStateCache) {
        this.authenticationManager = authenticationManager;
        this.userProvider = userProvider;
        this.realmProvider = realmProvider;
        this.authenticationProvider = authenticationProvider;
        this.logoutService = logoutService;
        this.tokenStateCache = tokenStateCache;
    }

    @Override
    public IamUserPrincipal authenticate(String username, String password) {
        // 1. 交给统一认证管理器:账号状态、失败计数、权限计算均在 Provider 内完成
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        // 2. 主体必须是本平台的用户快照,其它类型说明认证流程被别的 Provider 接管
        if (!(authentication.getPrincipal() instanceof IamUserPrincipal principal)) {
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
        return principal;
    }

    @Override
    public IamUserPrincipal completeMfaLogin(String realmKey, Long userId, String loginType) {
        // 1. 域必须存在且启用
        RealmModel realm = realmProvider.findByRealmKey(realmKey)
                .filter(RealmModel::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在或未启用: " + realmKey));
        // 2. 用户必须存在且状态正常(MFA 期间账号可能被禁用)
        UserModel user = userProvider.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + userId));
        if (user.status() != 1) {
            throw new DisabledException(IamMessages.message(ErrorCode.ACCOUNT_LOCKED));
        }
        // 3. 复用 Provider 的主体构建(同一套权限公式)
        Authentication authentication = authenticationProvider.buildAuthentication(realm, user, loginType);
        if (!(authentication.getPrincipal() instanceof IamUserPrincipal principal)) {
            throw new BadCredentialsException(IamMessages.message(ErrorCode.BAD_CREDENTIALS));
        }
        return principal;
    }

    @Override
    public int logoutAll(Long realmId, String realmKey, Long userId) {
        // 1. 令牌版本递增:已签发的 access/refresh/id token 在验签时全部失效
        userProvider.incrementTokenVersion(userId);
        // 2. 令牌状态缓存失效,使验签立即读到新版本(而非等缓存过期)
        tokenStateCache.invalidate(userId);
        // 3. 结束该用户全部活跃会话:逐个会话标记吊销并广播登出
        int ended = logoutService.logoutAll(realmId, userId);
        // 4. 权限集合本身未变化,无需失效权限缓存(代次机制见 IamPermissionEpoch)
        return ended;
    }
}
