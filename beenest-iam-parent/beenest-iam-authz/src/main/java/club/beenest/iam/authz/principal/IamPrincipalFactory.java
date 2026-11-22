package club.beenest.iam.authz.principal;

import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录主体工厂。
 * <p>主体是授权决策的快照(用户 + 权限码 + 权限代次),登录与会话权限刷新都经此处
 * 构建,算法仅此一处,保证会话内的权限与权限引擎输出一致。</p>
 */
@Service
public class IamPrincipalFactory {

    private final PermissionProvider permissionProvider;

    public IamPrincipalFactory(PermissionProvider permissionProvider) {
        this.permissionProvider = permissionProvider;
    }

    /**
     * 按用户当前的有效权限构建主体。
     * <p>先读权限代次再算权限:并发变更时代次只会偏旧,会话因此多刷新一次,
     * 不会掩盖真实的权限变更。</p>
     *
     * @param realm     所属身份域
     * @param user      用户
     * @param loginType 本次认证方式(LoginMethod 存储码);未知传 null
     * @return 登录主体快照
     */
    public IamUserPrincipal build(RealmModel realm, UserModel user, String loginType) {
        long epoch = permissionProvider.currentPermissionEpoch(realm.id());
        List<String> permissionCodes = List.copyOf(
                permissionProvider.computePermissionCodes(realm.id(), user.id()));
        return new IamUserPrincipal(
                user.id(), realm.id(), realm.realmKey(), user.username(), user.nickname(),
                user.tokenVersion(), permissionCodes, user.attributes(), epoch, loginType);
    }

    /**
     * 将主体转换为已认证的 Authentication(权限码即 authority)。
     *
     * @param principal 登录主体
     * @return 已认证的 Authentication
     */
    public Authentication toAuthentication(IamUserPrincipal principal) {
        List<GrantedAuthority> authorities = principal.getAuthorities().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }
}
