package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用作用域鉴权:判断当前用户是否可管理指定应用。
 * <p>先按平台级应用管理权限判定,不命中再按应用管理员名单判定,
 * 用户直授与所属组授权等价。应用必须属于调用方身份域,防止跨域 ID 替换。</p>
 */
@Component("appAuthz")
public class AppAuthorizationService {

    /** 平台级应用管理权限(全局管理员) */
    private static final String GLOBAL_MANAGE_AUTHORITY = "app-service:update";

    private final IamApplicationServiceManager appServiceManager;
    private final IamAppAdminManager appAdminManager;
    private final IamEffectiveRoleResolver effectiveRoleResolver;

    public AppAuthorizationService(IamApplicationServiceManager appServiceManager,
                                   IamAppAdminManager appAdminManager,
                                   IamEffectiveRoleResolver effectiveRoleResolver) {
        this.appServiceManager = appServiceManager;
        this.appAdminManager = appAdminManager;
        this.effectiveRoleResolver = effectiveRoleResolver;
    }

    /**
     * 判定当前登录用户是否可管理指定应用。
     * <p>依次校验:应用存在且与当前用户同域 → 持有平台级应用管理权限 → 在应用管理员名单中
     * (用户直授角色与所属组继承角色等价)。</p>
     *
     * @param appId 应用服务主键
     * @return true 表示可管理;未登录、应用不存在、跨域或不在名单内均返回 false
     */
    public boolean canManage(Long appId) {
        if (appId == null) {
            return false;
        }
        IamUserPrincipal user = CurrentUserHolder.optional();
        if (user == null) {
            return false;
        }
        ApplicationService app = appServiceManager.getById(appId);
        if (app == null || !user.getRealmId().equals(app.getRealmId())) {
            return false;
        }
        if (user.getAuthorities().contains(GLOBAL_MANAGE_AUTHORITY)) {
            return true;
        }
        List<Long> groupIds = effectiveRoleResolver.resolveGroupIds(user.getRealmId(), user.getUserId());
        return appAdminManager.isAdmin(user.getRealmId(), appId, user.getUserId(), groupIds);
    }
}
