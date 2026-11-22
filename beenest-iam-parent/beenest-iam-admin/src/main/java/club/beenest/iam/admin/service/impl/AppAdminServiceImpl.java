package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AppAdminCreateRequest;
import club.beenest.iam.admin.dto.AppAdminResponse;
import club.beenest.iam.admin.dto.ManagedAppResponse;
import club.beenest.iam.admin.service.AppAdminService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.AppAdmin;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 应用管理员名单管理实现。
 * <p>主体与管理关系限定在当前身份域;名单变更即时生效,不影响已签发的登录态。</p>
 */
@Service
public class AppAdminServiceImpl implements AppAdminService {

    /** 主体类型:用户 */
    private static final String SUBJECT_USER = "USER";
    /** 主体类型:用户组 */
    private static final String SUBJECT_GROUP = "GROUP";
    /** 全局应用管理权限:持有者可管理全部应用,与 appAuthz 的判定保持一致 */
    private static final String GLOBAL_MANAGE_AUTHORITY = "app-service:update";

    private final IamApplicationServiceManager appServiceManager;
    private final IamAppAdminManager appAdminManager;
    private final IamEffectiveRoleResolver effectiveRoleResolver;
    private final IamUserManager userManager;
    private final IamGroupManager groupManager;

    public AppAdminServiceImpl(IamApplicationServiceManager appServiceManager,
                               IamAppAdminManager appAdminManager,
                               IamEffectiveRoleResolver effectiveRoleResolver,
                               IamUserManager userManager,
                               IamGroupManager groupManager) {
        this.appServiceManager = appServiceManager;
        this.appAdminManager = appAdminManager;
        this.effectiveRoleResolver = effectiveRoleResolver;
        this.userManager = userManager;
        this.groupManager = groupManager;
    }

    @Override
    public List<ManagedAppResponse> listManagedApps() {
        IamUserPrincipal user = CurrentUserHolder.require();
        Long realmId = user.getRealmId();
        // 1. 全局管理权限放开全部应用;无全局权限时按直授/组继承的应用管理员关系收窄
        Set<Long> managedIds = user.getAuthorities().contains(GLOBAL_MANAGE_AUTHORITY)
                ? null
                : appAdminManager.listManagedAppIds(realmId, user.getUserId(),
                        effectiveRoleResolver.resolveGroupIds(realmId, user.getUserId()));
        return appServiceManager.listByRealmId(realmId).stream()
                .filter(app -> managedIds == null || managedIds.contains(app.getId()))
                .map(AppAdminServiceImpl::toManagedResponse)
                .toList();
    }

    @Override
    public List<AppAdminResponse> listAdmins(Long appId) {
        ApplicationService app = requireAppInRealm(appId);
        List<AppAdmin> admins = appAdminManager.listByAppServiceId(appId);
        if (admins.isEmpty()) {
            return List.of();
        }
        // 主体展示名批量取回:管理员名单逐行回查主体会把一次列表放大成 N 次查询
        Map<Long, User> users = userManager.listByIds(admins.stream()
                        .filter(admin -> SUBJECT_USER.equals(admin.getSubjectType()))
                        .map(AppAdmin::getSubjectId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (first, second) -> first));
        // 组数量在域内有限,一次取回比按主键批量再拼装更直接(与其他组相关页面口径一致)
        Map<Long, Group> groups = groupManager.listByRealm(app.getRealmId()).stream()
                .collect(Collectors.toMap(Group::getId, Function.identity(), (first, second) -> first));
        return admins.stream()
                .map(admin -> toResponse(admin,
                        users.get(admin.getSubjectId()), groups.get(admin.getSubjectId())))
                .toList();
    }

    @Override
    public AppAdminResponse addAdmin(Long appId, AppAdminCreateRequest request) {
        ApplicationService app = requireAppInRealm(appId);
        Long realmId = app.getRealmId();
        // 1. 主体必须存在且属于本域
        validateSubject(realmId, request.subjectType(), request.subjectId());
        // 2. 同一主体重复登记没有语义,直接拒绝避免名单噪音
        if (appAdminManager.getByAppAndSubject(appId, request.subjectType(), request.subjectId()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "该主体已是应用管理员");
        }
        // 3. 落库并回读展示信息
        AppAdmin admin = new AppAdmin();
        admin.setRealmId(realmId);
        admin.setAppServiceId(appId);
        admin.setSubjectType(request.subjectType());
        admin.setSubjectId(request.subjectId());
        admin.setGrantedBy(CurrentUserHolder.require().getUserId());
        appAdminManager.create(admin);
        return toResponse(appAdminManager.getById(admin.getId()));
    }

    @Override
    public void removeAdmin(Long appId, Long adminId) {
        ApplicationService app = requireAppInRealm(appId);
        AppAdmin admin = appAdminManager.getById(adminId);
        if (admin == null || !appId.equals(admin.getAppServiceId())
                || !app.getRealmId().equals(admin.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用管理员关系不存在: " + adminId);
        }
        appAdminManager.remove(adminId);
    }

    /**
     * 校验主体存在且属于当前域。
     *
     * @param realmId     身份域主键
     * @param subjectType 主体类型,USER 或 GROUP
     * @param subjectId   主体主键
     * @throws BusinessException 主体不存在、不属于当前域或类型非法
     */
    private void validateSubject(Long realmId, String subjectType, Long subjectId) {
        if (SUBJECT_USER.equals(subjectType)) {
            User user = userManager.getById(subjectId);
            if (user == null || !realmId.equals(user.getRealmId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "用户不存在于当前身份域: " + subjectId);
            }
            return;
        }
        if (SUBJECT_GROUP.equals(subjectType)) {
            Group group = groupManager.getById(subjectId);
            if (group == null || !realmId.equals(group.getRealmId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "用户组不存在于当前身份域: " + subjectId);
            }
            return;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "主体类型只能是 USER 或 GROUP");
    }

    /**
     * 管理员关系转响应,补齐主体展示名与编码。
     *
     * @param admin 管理员关系
     * @return 管理员响应
     */
    private AppAdminResponse toResponse(AppAdmin admin) {
        User user = SUBJECT_USER.equals(admin.getSubjectType())
                ? userManager.getById(admin.getSubjectId()) : null;
        Group group = SUBJECT_GROUP.equals(admin.getSubjectType())
                ? groupManager.getById(admin.getSubjectId()) : null;
        return toResponse(admin, user, group);
    }

    /**
     * 管理员关系转响应,主体实体由调用方批量取回后传入。
     *
     * @param admin 管理员关系
     * @param user  主体用户,主体为组时为 null
     * @param group 主体组,主体为用户时为 null
     * @return 管理员响应
     */
    private AppAdminResponse toResponse(AppAdmin admin, User user, Group group) {
        String subjectName = null;
        String subjectCode = null;
        if (user != null) {
            subjectName = user.getNickname() != null && !user.getNickname().isBlank()
                    ? user.getNickname() : user.getUsername();
            subjectCode = user.getUsername();
        } else if (group != null) {
            subjectName = group.getGroupName();
            subjectCode = group.getGroupCode();
        }
        return new AppAdminResponse(admin.getId(), admin.getAppServiceId(), admin.getSubjectType(),
                admin.getSubjectId(), subjectName, subjectCode, admin.getGrantedBy(),
                admin.getCreatedTime());
    }

    /**
     * 应用服务转可管理应用响应。
     *
     * @param app 应用服务
     * @return 可管理应用响应
     */
    private static ManagedAppResponse toManagedResponse(ApplicationService app) {
        return new ManagedAppResponse(app.getId(), app.getAppCode(), app.getAppName(),
                app.getAuthorizationMode(), app.getTokenProfile(), app.getEnabled());
    }

    /**
     * 读取属于当前域的应用服务。
     *
     * @param appId 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long appId) {
        ApplicationService app = appServiceManager.getById(appId);
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用服务不存在: " + appId);
        }
        return app;
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return CurrentUserHolder.require().getRealmId();
    }
}
