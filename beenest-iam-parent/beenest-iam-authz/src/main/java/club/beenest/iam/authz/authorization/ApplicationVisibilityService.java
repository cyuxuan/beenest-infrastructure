package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 应用可见性判定。
 * <p>门户列表、启动与应用菜单共用同一口径:全员可见、按权限码、按用户/角色/组授权
 * 三类规则的并集。授权端点与凭据登录也走这里,避免"门户看不到、协议端点却能进"。</p>
 * <p>只回答"这个用户能否使用这个应用",不涉及应用内的权限码;应用内的按钮与接口
 * 鉴权仍由令牌里的 {@code permissions} 决定。</p>
 */
@Component
public class ApplicationVisibilityService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationVisibilityService.class);

    /** 可见性:全员可见 */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    /** 可见性:按权限码 */
    public static final String VISIBILITY_PERMISSION = "PERMISSION";
    /** 授权主体类型:用户 */
    private static final String SUBJECT_USER = "USER";
    /** 授权主体类型:角色 */
    private static final String SUBJECT_ROLE = "ROLE";
    /** 授权主体类型:组 */
    private static final String SUBJECT_GROUP = "GROUP";

    private final IamApplicationServiceManager appServiceManager;
    private final IamEffectiveRoleResolver effectiveRoleResolver;

    public ApplicationVisibilityService(IamApplicationServiceManager appServiceManager,
                                        IamEffectiveRoleResolver effectiveRoleResolver) {
        this.appServiceManager = appServiceManager;
        this.effectiveRoleResolver = effectiveRoleResolver;
    }

    /**
     * 当前用户可见的应用(已启用)。
     *
     * @param principal 当前登录用户
     * @return 已启用且对当前用户可见的应用
     */
    public List<ApplicationService> visibleApps(IamUserPrincipal principal) {
        if (principal == null || principal.getRealmId() == null) {
            return List.of();
        }
        Set<Long> granted = grantedAppIds(principal.getRealmId(), principal.getUserId());
        Set<String> authorities = authoritiesOf(principal);
        return appServiceManager.listByRealmId(principal.getRealmId()).stream()
                .filter(app -> Boolean.TRUE.equals(app.getEnabled()))
                .filter(app -> isVisible(app, authorities, granted))
                .toList();
    }

    /**
     * 当前用户经直授、角色或组获得的应用(已启用)。
     * <p>不含 PUBLIC 可见性应用:该方法只返回有显式授权记录的应用,
     * 用于令牌受众回退解析——用户恰好被授权一个应用时,令牌自动携带该应用受众。</p>
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 已启用且经授权的应用列表
     */
    public List<ApplicationService> grantedApps(Long realmId, Long userId) {
        if (realmId == null || userId == null) {
            return List.of();
        }
        Set<Long> ids = grantedAppIds(realmId, userId);
        if (ids.isEmpty()) {
            return List.of();
        }
        return appServiceManager.listByIds(new java.util.ArrayList<>(ids)).stream()
                .filter(app -> Boolean.TRUE.equals(app.getEnabled()))
                .filter(app -> realmId.equals(app.getRealmId()))
                .toList();
    }

    /**
     * 当前用户能否使用指定应用。
     * <p>应用停用、跨域或不可见一律拒绝;调用方只需判断返回值。</p>
     *
     * @param principal 当前登录用户
     * @param app       应用服务
     * @return true 表示可以进入该应用
     */
    public boolean canAccess(IamUserPrincipal principal, ApplicationService app) {
        if (principal == null || app == null || principal.getRealmId() == null) {
            return false;
        }
        if (!principal.getRealmId().equals(app.getRealmId())) {
            return false;
        }
        if (!Boolean.TRUE.equals(app.getEnabled())) {
            return false;
        }
        return isVisible(app, authoritiesOf(principal), grantedAppIds(principal.getRealmId(), principal.getUserId()));
    }

    /**
     * 按用户主键重新计算能否使用指定应用。
     * <p>不读取会话或授权记录中的权限快照:授权被收回后,既有令牌里的权限集合仍是
     * 签发时的旧值,刷新换票必须按库中当前事实重新判定,否则一次"收回"只在门户生效。</p>
     * <p>只覆盖授权式可见性。全员可见与按权限码可见不随授权记录变化,交由各自的
     * 判定链路处理,这里按放行返回。</p>
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @param app     应用服务
     * @return true 表示用户当前仍可进入该应用
     */
    public boolean canAccessByUserId(Long realmId, Long userId, ApplicationService app) {
        if (realmId == null || userId == null || app == null) {
            return false;
        }
        if (!realmId.equals(app.getRealmId()) || !Boolean.TRUE.equals(app.getEnabled())) {
            return false;
        }
        if (VISIBILITY_PUBLIC.equals(app.getVisibility())
                || VISIBILITY_PERMISSION.equals(app.getVisibility())) {
            return true;
        }
        return grantedAppIds(realmId, userId).contains(app.getId());
    }

    /**
     * 取客户端绑定的应用服务。
     *
     * @param realmId 身份域主键
     * @param client  协议层客户端
     * @return 绑定的应用服务;未绑定或主键无法解析时返回 null
     */
    public ApplicationService boundApplication(Long realmId, RegisteredClient client) {
        if (realmId == null || client == null) {
            return null;
        }
        long clientPk;
        try {
            clientPk = Long.parseLong(client.getId());
        } catch (NumberFormatException e) {
            // 主键不是数据库行 id 属于实现异常,按未绑定处理,不阻断登录
            log.warn("客户端主键无法解析为数据库 id,跳过应用授权判定: clientId={}, id={}",
                    client.getClientId(), client.getId());
            return null;
        }
        return appServiceManager.getBySsoClientPk(realmId, clientPk);
    }

    /**
     * 单条可见性规则。
     *
     * @param app         应用
     * @param authorities 当前用户权限码
     * @param granted     当前用户经用户/角色/组获得的授权应用主键
     * @return 是否可见
     */
    private static boolean isVisible(ApplicationService app, Set<String> authorities, Set<Long> granted) {
        String visibility = app.getVisibility();
        if (VISIBILITY_PUBLIC.equals(visibility)) {
            return true;
        }
        // 按权限:用户权限集中必须含指定权限码;未配置权限码视为不可见,
        // 与门户列表保持一致,避免一条不完整的配置把应用静默放开
        if (VISIBILITY_PERMISSION.equals(visibility)) {
            String required = app.getRequiredPermissionCode();
            return required != null && authorities.contains(required);
        }
        // 其余(ROLE 等)一律以授权记录为准
        return granted.contains(app.getId());
    }

    /**
     * 用户经直授、有效角色与所属组获得的授权应用主键。
     * <p>有效角色的 app_service_id 一并计入:用户持有某应用的应用角色即可见该应用,
     * 无需另建授权记录。</p>
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 应用服务主键集合
     */
    private Set<Long> grantedAppIds(Long realmId, Long userId) {
        Set<Long> granted = new HashSet<>(
                appServiceManager.listGrantedAppServiceIds(SUBJECT_USER, List.of(userId)));
        List<Role> roles = effectiveRoleResolver.resolve(realmId, userId);
        if (!roles.isEmpty()) {
            granted.addAll(appServiceManager.listGrantedAppServiceIds(SUBJECT_ROLE,
                    roles.stream().map(Role::getId).toList()));
            roles.stream()
                    .map(Role::getAppServiceId)
                    .filter(Objects::nonNull)
                    .forEach(granted::add);
        }
        List<Long> groupIds = effectiveRoleResolver.resolveGroupIds(realmId, userId);
        granted.addAll(appServiceManager.listGrantedAppServiceIds(SUBJECT_GROUP, groupIds));
        return granted;
    }

    /**
     * 主体权限码集合,空权限按空集合处理。
     *
     * @param principal 当前登录用户
     * @return 权限码集合
     */
    private static Set<String> authoritiesOf(IamUserPrincipal principal) {
        List<String> authorities = principal.getAuthorities();
        return authorities == null || authorities.isEmpty()
                ? Set.of() : Set.copyOf(authorities);
    }
}
