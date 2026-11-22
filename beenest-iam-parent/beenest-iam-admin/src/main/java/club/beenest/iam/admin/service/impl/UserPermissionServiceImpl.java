package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.EffectivePermissionResponse;
import club.beenest.iam.admin.dto.EffectiveRoleResponse;
import club.beenest.iam.admin.dto.PermissionBriefResponse;
import club.beenest.iam.admin.dto.PermissionCandidateResponse;
import club.beenest.iam.admin.dto.PermissionOverrideRequest;
import club.beenest.iam.admin.dto.PermissionOverrideResponse;
import club.beenest.iam.admin.dto.PermissionScopeResponse;
import club.beenest.iam.admin.dto.RoleGroupSourceResponse;
import club.beenest.iam.admin.dto.RolePositionSourceResponse;
import club.beenest.iam.admin.service.UserPermissionService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.entity.UserPermission;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import club.beenest.iam.persistence.manager.IamUserPermissionManager;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户级权限治理实现。
 * <p>所有操作限定在调用者身份域内;禁止修改自己的特批,且只能操作调用者自身已具备的权限,
 * 避免持特批权限的管理员绕过角色直接补权。
 * 特批只在下一次认证/新签发令牌时生效,已签发的令牌不会被追溯修改。</p>
 */
@Service
public class UserPermissionServiceImpl implements UserPermissionService {

    private final IamUserManager userManager;
    private final IamPermissionManager permissionManager;
    private final IamUserPermissionManager userPermissionManager;
    private final IamApplicationServiceManager appServiceManager;
    private final IamEffectiveRoleResolver effectiveRoleResolver;
    private final IamGroupManager groupManager;
    private final IamPositionManager positionManager;

    public UserPermissionServiceImpl(IamUserManager userManager, IamPermissionManager permissionManager,
                                     IamUserPermissionManager userPermissionManager,
                                     IamApplicationServiceManager appServiceManager,
                                     IamEffectiveRoleResolver effectiveRoleResolver,
                                     IamGroupManager groupManager,
                                     IamPositionManager positionManager) {
        this.userManager = userManager;
        this.permissionManager = permissionManager;
        this.userPermissionManager = userPermissionManager;
        this.appServiceManager = appServiceManager;
        this.effectiveRoleResolver = effectiveRoleResolver;
        this.groupManager = groupManager;
        this.positionManager = positionManager;
    }

    @Override
    public List<PermissionOverrideResponse> listOverrides(Long userId) {
        User user = requireUserInRealm(userId);
        List<UserPermission> relations = userPermissionManager.listByUserId(user.getId());
        if (relations.isEmpty()) {
            return List.of();
        }
        Set<Long> permissionIds = relations.stream()
                .map(UserPermission::getPermissionId)
                .collect(Collectors.toSet());
        Map<Long, Permission> permissions = permissionManager.listByIds(List.copyOf(permissionIds)).stream()
                .filter(permission -> user.getRealmId().equals(permission.getRealmId()))
                .collect(Collectors.toMap(Permission::getId, Function.identity()));
        Map<Long, ApplicationService> apps = appsById(user.getRealmId());
        return relations.stream()
                .map(relation -> toOverrideResponse(relation, permissions.get(relation.getPermissionId()), apps))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<PermissionScopeResponse> listScopes(Long userId) {
        User user = requireUserInRealm(userId);
        return appServiceManager.listByRealmId(user.getRealmId()).stream()
                .map(app -> new PermissionScopeResponse(
                        app.getId(), app.getAppCode(), app.getAppName(), app.getEnabled()))
                .toList();
    }

    @Override
    public PageResult<PermissionCandidateResponse> pageCandidates(Long userId, String keyword,
                                                                  Long appServiceId, int pageNum, int pageSize) {
        User user = requireUserInRealm(userId);
        if (appServiceId != null) {
            requireAppInRealm(appServiceId);
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        PageResult<Permission> page = permissionManager.pageForOverride(
                user.getRealmId(), appServiceId, normalizedKeyword, pageNum, pageSize);
        Map<Long, UserPermission> overrides = userPermissionManager.listByUserId(userId).stream()
                .collect(Collectors.toMap(UserPermission::getPermissionId, Function.identity()));
        Map<Long, ApplicationService> apps = appsById(user.getRealmId());
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream()
                        .map(permission -> toCandidate(permission, overrides.get(permission.getId()), apps))
                        .toList());
    }

    @Override
    public EffectivePermissionResponse inspectEffective(Long userId, Long appServiceId) {
        User user = requireUserInRealm(userId);
        ApplicationService app = appServiceId == null ? null : requireAppInRealm(appServiceId);
        IamPermissionManager.PermissionInspection inspection =
                permissionManager.inspect(user.getRealmId(), userId, appServiceId);
        Map<Long, ApplicationService> apps = appsById(user.getRealmId());
        List<EffectiveRoleResponse> roles = effectiveRoles(user.getRealmId(), userId);
        return new EffectivePermissionResponse(
                userId,
                appServiceId,
                app == null ? null : app.getAppName(),
                roles,
                briefs(inspection.roleDirectPermissions(), apps),
                briefs(inspection.menuDerivedPermissions(), apps),
                briefs(inspection.userGrantedPermissions(), apps),
                briefs(inspection.userRevokedPermissions(), apps),
                briefs(inspection.effectivePermissions(), apps),
                LocalDateTime.now());
    }

    @Override
    public PermissionOverrideResponse saveOverride(Long userId, Long permissionId,
                                                   PermissionOverrideRequest request) {
        User target = requireUserInRealm(userId);
        requireNotSelf(target.getId());
        Permission permission = requirePermissionInRealm(permissionId);
        requireWithinOwnPermissions(permission);
        if (request.expiredTime() != null && !request.expiredTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "过期时间必须晚于当前时间");
        }
        UserPermission relation = new UserPermission();
        relation.setRealmId(target.getRealmId());
        relation.setUserId(target.getId());
        relation.setPermissionId(permission.getId());
        relation.setGrantType(request.grantType());
        relation.setReason(request.reason().trim());
        relation.setExpiredTime(request.expiredTime());
        relation.setGrantedBy(currentPrincipal().getUserId());
        UserPermission saved = userPermissionManager.upsert(relation);
        // 权限代次由持久层在事务提交后抬升,此处不逐用户清缓存
        Map<Long, ApplicationService> apps = appsById(target.getRealmId());
        return toOverrideResponse(saved, permission, apps);
    }

    @Override
    public void deleteOverride(Long userId, Long permissionId) {
        User target = requireUserInRealm(userId);
        requireNotSelf(target.getId());
        UserPermission relation = userPermissionManager.getByUserAndPermission(target.getId(), permissionId);
        if (relation == null || !target.getRealmId().equals(relation.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权限特批不存在: " + permissionId);
        }
        // 解除撤销类特批等同于把权限还给对方,同样受"不得超出自身权限"约束
        Permission permission = permissionManager.getById(relation.getPermissionId());
        if (permission != null) {
            requireWithinOwnPermissions(permission);
        }
        userPermissionManager.remove(relation.getId());
        // 权限代次由持久层在事务提交后抬升,此处不逐用户清缓存
    }

    /**
     * 有效角色及继承来源。
     * <p>组与岗位两类来源都列出,便于定位角色的授予链路。</p>
     *
     * @param realmId 身份域主键
     * @param userId  用户主键
     * @return 有效角色列表,含直接授予与组、岗位来源
     */
    private List<EffectiveRoleResponse> effectiveRoles(Long realmId, Long userId) {
        // 组一次取回:源诊断要按组展示来源,逐个 getById 会随角色数线性放大查询次数
        Map<Long, Group> groupsById = groupManager.listByRealm(realmId).stream()
                .collect(Collectors.toMap(Group::getId, Function.identity(), (first, second) -> first));
        return effectiveRoleResolver.resolveWithSources(realmId, userId).stream()
                .map(source -> {
                    List<RoleGroupSourceResponse> groups = source.groupIds().stream()
                            .map(groupsById::get)
                            .filter(Objects::nonNull)
                            .map(UserPermissionServiceImpl::toGroupSource)
                            .toList();
                    List<RolePositionSourceResponse> positions = positionManager.listByIds(source.positionIds())
                            .stream()
                            .map(UserPermissionServiceImpl::toPositionSource)
                            .toList();
                    return new EffectiveRoleResponse(
                            source.role().getId(),
                            source.role().getRoleCode(),
                            source.role().getRoleName(),
                            source.role().getContainerType(),
                            source.direct(),
                            groups,
                            positions);
                })
                .toList();
    }

    /**
     * 岗位实体转角色来源响应。
     *
     * @param position 岗位
     * @return 岗位来源响应
     */
    private static RolePositionSourceResponse toPositionSource(Position position) {
        return new RolePositionSourceResponse(position.getId(), position.getPositionCode(),
                position.getPositionName());
    }

    /**
     * 权限实体列表转简要视图,补齐所属应用名称。
     *
     * @param permissions 权限列表
     * @param apps        应用主键到应用的映射
     * @return 权限简要视图列表
     */
    private static List<PermissionBriefResponse> briefs(List<Permission> permissions,
                                                       Map<Long, ApplicationService> apps) {
        return permissions.stream()
                .map(permission -> new PermissionBriefResponse(
                        permission.getId(),
                        permission.getPermissionCode(),
                        permission.getPermissionName(),
                        permission.getAppServiceId(),
                        appName(apps.get(permission.getAppServiceId()))))
                .toList();
    }

    /**
     * 特批关系转响应,权限已删除时返回 null。
     *
     * @param relation   特批关系
     * @param permission 权限,可为 null
     * @param apps       应用主键到应用的映射
     * @return 特批响应,权限不存在时为 null
     */
    private static PermissionOverrideResponse toOverrideResponse(
            UserPermission relation, Permission permission, Map<Long, ApplicationService> apps) {
        if (permission == null) {
            return null;
        }
        return new PermissionOverrideResponse(
                relation.getId(),
                relation.getUserId(),
                relation.getPermissionId(),
                permission.getPermissionCode(),
                permission.getPermissionName(),
                permission.getAppServiceId(),
                appName(apps.get(permission.getAppServiceId())),
                relation.getGrantType(),
                relation.getReason(),
                relation.getExpiredTime(),
                active(relation.getExpiredTime()),
                relation.getGrantedBy(),
                relation.getCreatedTime(),
                relation.getUpdatedTime());
    }

    /**
     * 权限候选转响应,附带用户当前特批状态。
     *
     * @param permission 权限
     * @param relation   用户的特批关系,可为 null
     * @param apps       应用主键到应用的映射
     * @return 权限候选响应
     */
    private static PermissionCandidateResponse toCandidate(
            Permission permission, UserPermission relation, Map<Long, ApplicationService> apps) {
        ApplicationService app = apps.get(permission.getAppServiceId());
        return new PermissionCandidateResponse(
                permission.getId(),
                permission.getPermissionCode(),
                permission.getPermissionName(),
                permission.getAppServiceId(),
                appName(app),
                app == null ? null : app.getAppCode(),
                relation == null ? null : relation.getGrantType(),
                relation == null ? null : active(relation.getExpiredTime()),
                relation == null ? null : relation.getExpiredTime());
    }

    /**
     * 组实体转角色来源响应。
     *
     * @param group 用户组
     * @return 组来源响应
     */
    private static RoleGroupSourceResponse toGroupSource(Group group) {
        return new RoleGroupSourceResponse(group.getId(), group.getGroupCode(), group.getGroupName());
    }

    /**
     * 取应用名称,应用不存在时返回 null。
     *
     * @param app 应用服务,可为 null
     * @return 应用名称
     */
    private static String appName(ApplicationService app) {
        return app == null ? null : app.getAppName();
    }

    /**
     * 判断特批是否仍在有效期内。
     *
     * @param expiredTime 过期时间,可为 null
     * @return 无过期时间或未过期时为 true
     */
    private static boolean active(LocalDateTime expiredTime) {
        return expiredTime == null || expiredTime.isAfter(LocalDateTime.now());
    }

    /**
     * 读取当前域全部应用,用于补齐应用名称与范围筛选。
     *
     * @param realmId 身份域主键
     * @return 应用主键到应用服务的映射
     */
    private Map<Long, ApplicationService> appsById(Long realmId) {
        return appServiceManager.listByRealmId(realmId).stream()
                .collect(Collectors.toMap(ApplicationService::getId, Function.identity()));
    }

    /**
     * 拒绝修改当前登录用户自己的特批。
     *
     * @param targetUserId 目标用户主键
     * @throws BusinessException 目标用户为当前登录账号
     */
    private void requireNotSelf(Long targetUserId) {
        if (targetUserId.equals(currentPrincipal().getUserId())) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "不允许修改自己的权限特批");
        }
    }

    /**
     * 拒绝操作当前登录账号不具备的权限。
     * <p>特批是绕过角色的直通通道:只挡"给自己"仍可通过第二个账号提权——给该账号
     * 特批任意权限,再用它登录。因此授出与撤销都限制在操作者自身权限范围内。</p>
     *
     * @param permission 目标权限
     * @throws BusinessException 操作者不具备该权限码
     */
    private void requireWithinOwnPermissions(Permission permission) {
        if (!currentPrincipal().getAuthorities().contains(permission.getPermissionCode())) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "只能操作自己已具备的权限: " + permission.getPermissionCode());
        }
    }

    /**
     * 当前调用者主体。
     *
     * @return 用户主体
     */
    private IamUserPrincipal currentPrincipal() {
        return CurrentUserHolder.require();
    }

    /**
     * 读取属于当前域的用户,不存在或跨域一律按不存在处理。
     *
     * @param id 用户主键
     * @return 用户
     * @throws BusinessException 用户不存在或不属于当前域
     */
    private User requireUserInRealm(Long id) {
        User user = userManager.getById(id);
        if (user == null || !currentPrincipal().getRealmId().equals(user.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + id);
        }
        return user;
    }

    /**
     * 读取属于当前域的权限。
     *
     * @param id 权限主键
     * @return 权限
     * @throws BusinessException 权限不存在或不属于当前域
     */
    private Permission requirePermissionInRealm(Long id) {
        Permission permission = permissionManager.getById(id);
        if (permission == null || !currentPrincipal().getRealmId().equals(permission.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权限不存在: " + id);
        }
        return permission;
    }

    /**
     * 读取属于当前域的应用。
     *
     * @param appServiceId 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long appServiceId) {
        ApplicationService app = appServiceManager.getById(appServiceId);
        if (app == null || !currentPrincipal().getRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用服务不存在: " + appServiceId);
        }
        return app;
    }
}
