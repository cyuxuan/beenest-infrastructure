package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ApplicationServiceDtoConverter;
import club.beenest.iam.admin.dto.AppServiceGrantResponse;
import club.beenest.iam.admin.dto.ApplicationServiceCreateRequest;
import club.beenest.iam.admin.dto.ApplicationServiceResponse;
import club.beenest.iam.admin.dto.ApplicationServiceUpdateRequest;
import club.beenest.iam.admin.service.ApplicationServiceService;
import club.beenest.iam.authz.authorization.ApplicationAccessRevocationService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.AppServiceGrant;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用服务管理实现。
 * <p>校验接入配置的一致性:入口地址必须是 http(s) 地址,OIDC 接入必须绑定本域客户端,
 * 按权限可见必须给出权限编码;删除应用时一并清理其管理员与角色映射。</p>
 */
@Service
public class ApplicationServiceServiceImpl implements ApplicationServiceService {

    /** 单点接入方式:OIDC */
    private static final String MODE_OIDC = "OIDC";
    /** 可见性:按权限 */
    private static final String VISIBILITY_PERMISSION = "PERMISSION";
    /** 令牌受众数量上限,与库列宽(1000)和接入文档口径一致 */
    private static final int MAX_AUDIENCES = 5;
    /** 单个令牌受众长度上限 */
    private static final int MAX_AUDIENCE_LENGTH = 200;
    /** 授权主体类型:角色 */
    private static final String SUBJECT_ROLE = "ROLE";
    /** 授权主体类型:用户 */
    private static final String SUBJECT_USER = "USER";
    /** 授权主体类型:组 */
    private static final String SUBJECT_GROUP = "GROUP";

    private final IamApplicationServiceManager appServiceManager;
    private final IamAppAdminManager appAdminManager;
    private final IamAppRoleMappingManager appRoleMappingManager;
    private final IamClientManager clientManager;
    private final IamRoleManager roleManager;
    private final IamUserManager userManager;
    private final IamGroupManager groupManager;
    private final IamPermissionManager permissionManager;
    private final ApplicationServiceDtoConverter dtoConverter;
    private final ApplicationAccessRevocationService revocationService;

    public ApplicationServiceServiceImpl(IamApplicationServiceManager appServiceManager,
                                         IamAppAdminManager appAdminManager,
                                         IamAppRoleMappingManager appRoleMappingManager,
                                         IamClientManager clientManager,
                                         IamRoleManager roleManager,
                                         IamUserManager userManager,
                                         IamGroupManager groupManager,
                                         IamPermissionManager permissionManager,
                                         ApplicationServiceDtoConverter dtoConverter,
                                         ApplicationAccessRevocationService revocationService) {
        this.appServiceManager = appServiceManager;
        this.appAdminManager = appAdminManager;
        this.appRoleMappingManager = appRoleMappingManager;
        this.clientManager = clientManager;
        this.roleManager = roleManager;
        this.userManager = userManager;
        this.groupManager = groupManager;
        this.permissionManager = permissionManager;
        this.dtoConverter = dtoConverter;
        this.revocationService = revocationService;
    }

    @Override
    public PageResult<ApplicationServiceResponse> page(String appCode, String appName, String appType,
                                                       String category, String visibility, String authorizationMode,
                                                       Boolean enabled, int pageNum, int pageSize) {
        // 1. 组装单表查询条件
        ApplicationService condition = new ApplicationService();
        condition.setAppCode(appCode);
        condition.setAppName(appName);
        condition.setAppType(appType);
        condition.setCategory(category);
        condition.setVisibility(visibility);
        condition.setAuthorizationMode(authorizationMode);
        condition.setEnabled(enabled);
        // 2. 分页查询当前域应用服务后逐条转换
        PageResult<ApplicationService> page =
                appServiceManager.page(currentRealmId(), condition, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(dtoConverter::toResponse).toList());
    }

    @Override
    public ApplicationServiceResponse get(Long id) {
        return dtoConverter.toResponse(requireAppInRealm(id));
    }

    @Override
    @Transactional
    public ApplicationServiceResponse create(ApplicationServiceCreateRequest request) {
        Long realmId = currentRealmId();
        // 1. 应用编码在域内唯一
        if (appServiceManager.getByAppCode(realmId, request.appCode()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "应用编码已存在: " + request.appCode());
        }
        // 2. 落库前校验接入配置的一致性
        ApplicationService app = dtoConverter.toEntity(request, realmId);
        validateConsistency(app, realmId);
        // 3. 权限编码归属校验依赖应用主键,创建落库后执行;校验失败由事务回滚
        appServiceManager.create(app);
        validatePermissionBinding(app, realmId);
        return dtoConverter.toResponse(appServiceManager.getById(app.getId()));
    }

    @Override
    public ApplicationServiceResponse update(Long id, ApplicationServiceUpdateRequest request) {
        Long realmId = currentRealmId();
        // 1. 目标应用必须在当前域
        ApplicationService app = requireAppInRealm(id);
        // 2. 仅更新显式给出的字段
        dtoConverter.applyUpdate(app, request);
        // 3. 更新后仍需满足一致性约束
        validateConsistency(app, realmId);
        validatePermissionBinding(app, realmId);
        appServiceManager.update(app);
        return dtoConverter.toResponse(appServiceManager.getById(id));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireAppInRealm(id);
        // 应用删除后管理员与映射不再有任何入口,随应用一并逻辑删除
        appAdminManager.removeByAppServiceId(id);
        appRoleMappingManager.removeByAppServiceId(id);
        appServiceManager.remove(id);
    }

    @Override
    public List<AppServiceGrantResponse> listGrants(Long id, String subjectType) {
        ApplicationService app = requireAppInRealm(id);
        String type = normalizeSubjectType(subjectType);
        Long realmId = currentRealmId();
        // 已删除或已不属于该应用作用域的历史授权行不返回:保存为整表覆盖,
        // 回传的失效主体会被校验拒绝
        return appServiceManager.listGrants(app.getId()).stream()
                .filter(grant -> type.equals(grant.getSubjectType()))
                .filter(grant -> subjectInAppScope(app, grant.getSubjectType(), grant.getSubjectId(), realmId))
                .map(grant -> new AppServiceGrantResponse(
                        grant.getId(), app.getId(), grant.getSubjectType(), grant.getSubjectId()))
                .toList();
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public List<AppServiceGrantResponse> replaceGrants(Long id, String subjectType, List<Long> subjectIds) {
        Long realmId = currentRealmId();
        ApplicationService app = requireAppInRealm(id);
        String type = normalizeSubjectType(subjectType);
        // 1. 主体必须存在、属于本域且落在该应用作用域内,缺失时报错,不做静默丢弃
        List<Long> distinctIds = subjectIds == null ? List.of()
                : subjectIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (Long subjectId : distinctIds) {
            requireSubjectInAppScope(app, type, subjectId, realmId);
        }
        // 2. 覆盖前记录 USER 直授原有名单:被移除的成员在提交后要收回既有访问
        Set<Long> previousUserIds = SUBJECT_USER.equals(type)
                ? appServiceManager.listGrants(app.getId()).stream()
                        .filter(grant -> SUBJECT_USER.equals(grant.getSubjectType()))
                        .map(AppServiceGrant::getSubjectId)
                        .collect(Collectors.toSet())
                : Set.of();
        // 3. 整体覆盖该主体类型的授权名单
        appServiceManager.replaceGrants(realmId, app.getId(), type, distinctIds);
        // 4. 被移除的 USER 直授不再享有该应用:提交后吊销令牌、定向登出并关闭会话。
        //    角色/组授权移除的成员范围需全量展开,由刷新令牌闸门兜底
        List<Long> removedUserIds = previousUserIds.stream()
                .filter(subjectId -> !distinctIds.contains(subjectId))
                .toList();
        if (!removedUserIds.isEmpty()) {
            revocationService.revokeOnGrantRemoved(realmId, app.getId(), removedUserIds);
        }
        return listGrants(app.getId(), type);
    }

    /**
     * 判断主体是否存在、属于当前域,并且落在该应用的作用域内。
     * <p>应用角色只对所属应用有效:授权记录挂到其他应用上时,门户会按该角色显示不相干的应用。</p>
     *
     * @param app         目标应用
     * @param subjectType 主体类型
     * @param subjectId   主体主键
     * @param realmId     身份域主键
     * @return 主体可授权给该应用时为 true
     */
    private boolean subjectInAppScope(ApplicationService app, String subjectType, Long subjectId, Long realmId) {
        if (SUBJECT_ROLE.equals(subjectType)) {
            Role role = roleManager.getById(subjectId);
            return role != null && realmId.equals(role.getRealmId())
                    && (role.getAppServiceId() == null || role.getAppServiceId().equals(app.getId()));
        }
        if (SUBJECT_GROUP.equals(subjectType)) {
            Group group = groupManager.getById(subjectId);
            return group != null && realmId.equals(group.getRealmId());
        }
        User user = userManager.getById(subjectId);
        return user != null && realmId.equals(user.getRealmId());
    }

    /**
     * 校验待授权主体,失败时给出可操作的提示。
     *
     * @param app         目标应用
     * @param subjectType 主体类型
     * @param subjectId   主体主键
     * @param realmId     身份域主键
     * @throws BusinessException 主体不存在、跨域或应用角色属于其他应用
     */
    private void requireSubjectInAppScope(ApplicationService app, String subjectType, Long subjectId, Long realmId) {
        if (subjectInAppScope(app, subjectType, subjectId, realmId)) {
            return;
        }
        if (SUBJECT_ROLE.equals(subjectType)) {
            Role role = roleManager.getById(subjectId);
            if (role != null && realmId.equals(role.getRealmId())) {
                ApplicationService owner = appServiceManager.getById(role.getAppServiceId());
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "应用角色只能授权给所属应用: ROLE#" + subjectId + " 属于 "
                                + (owner == null ? "应用#" + role.getAppServiceId() : owner.getAppCode()));
            }
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID,
                "授权主体不存在于当前身份域: " + subjectType + "#" + subjectId);
    }

    /**
     * 归一化主体类型。
     *
     * @param subjectType 原始主体类型,可为 null
     * @return 归一化后的主体类型
     * @throws BusinessException 类型不在 USER、ROLE、GROUP 之内
     */
    private String normalizeSubjectType(String subjectType) {
        String type = subjectType == null ? "" : subjectType.trim().toUpperCase();
        if (!SUBJECT_ROLE.equals(type) && !SUBJECT_USER.equals(type) && !SUBJECT_GROUP.equals(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "授权主体类型只能是 USER、ROLE 或 GROUP");
        }
        return type;
    }

    /**
     * 校验应用服务的接入配置与可见性配置自洽。
     *
     * @param app     应用服务
     * @param realmId 身份域主键
     * @throws BusinessException 接入配置或可见性配置不合法
     */
    private void validateConsistency(ApplicationService app, Long realmId) {
        // 令牌受众写错是静默故障:令牌照常签发,只是每次调用都被资源服务器拒掉,
        // 且现象(403/受众不匹配)离"配置写错了"很远,必须在落库前拦下
        validateAudiences(app);
        // 0. 令牌收敛档只接受两个值:拼错会被签发侧按收敛档处理,配置意图与实际行为不一致
        String tokenProfile = app.getTokenProfile() == null ? null : app.getTokenProfile().trim();
        if (tokenProfile != null && !tokenProfile.isEmpty()
                && !"SCOPED".equals(tokenProfile) && !"LEGACY_GLOBAL".equals(tokenProfile)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "令牌收敛档只能是 SCOPED 或 LEGACY_GLOBAL: " + tokenProfile);
        }
        // 1. 入口地址是门户唯一的跳转目标,填了就得是浏览器能跳的 http(s) 地址
        String entryUrl = app.getEntryUrl() == null ? null : app.getEntryUrl().trim();
        if (entryUrl != null && !entryUrl.isEmpty() && !isHttpUrl(entryUrl)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "入口地址必须是 http:// 或 https:// 开头的地址: " + entryUrl);
        }
        // 2. OIDC 接入必须绑定本域客户端,绑定的客户端用于签发令牌
        if (MODE_OIDC.equals(app.getSsoMode())) {
            if (app.getSsoClientPk() == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "OIDC 接入方式必须绑定客户端");
            }
            Client client = clientManager.getById(app.getSsoClientPk());
            if (client == null || !realmId.equals(client.getRealmId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "绑定的客户端不存在于当前身份域");
            }
            // 一个客户端只能绑定一个应用,按 client 反查应用需保持唯一对应
            ApplicationService bound = appServiceManager.getBySsoClientPk(realmId, app.getSsoClientPk());
            if (bound != null && !bound.getId().equals(app.getId())) {
                throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                        "该客户端已绑定应用: " + bound.getAppCode());
            }
        }
        // 3. 按权限可见必须给出权限编码,门户侧以该编码命中卡片
        if (VISIBILITY_PERMISSION.equals(app.getVisibility())
                && (app.getRequiredPermissionCode() == null || app.getRequiredPermissionCode().isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "按权限可见时必须指定所需权限编码");
        }
    }

    /**
     * 校验令牌受众。
     * <p>受众是资源服务器判定"这张令牌是不是签给我"的唯一依据。值本身写错不会有任何报错,
     * 只会让令牌在调用侧被拒,因此这里把三类常见错法挡在落库前:</p>
     * <ol>
     *   <li><b>用逗号把两个值连写</b>——存进去是一个受众,而校验方按单值比对,永远不命中;</li>
     *   <li><b>夹带空格或中文标点</b>——受众值里不允许出现,否则字面量对不上;</li>
     *   <li><b>超长或超量</b>——库列宽与令牌体积都有上限。</li>
     * </ol>
     *
     * @param app 应用服务(受众已由转换器拼成逗号分隔单串)
     * @throws BusinessException 受众写法非法
     */
    private void validateAudiences(ApplicationService app) {
        String configured = app.getAudience();
        if (configured == null || configured.isBlank()) {
            return;
        }
        String[] values = configured.split(",");
        if (values.length > MAX_AUDIENCES) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "令牌受众最多 " + MAX_AUDIENCES + " 个");
        }
        for (String raw : values) {
            String value = raw.trim();
            if (value.isEmpty()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "令牌受众不能有空值");
            }
            if (value.contains("，") || value.contains("、") || value.contains("；")) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "多个令牌受众只能用英文逗号分隔,检测到中文标点: " + value);
            }
            if (value.chars().anyMatch(Character::isWhitespace)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "令牌受众不能包含空格: " + value);
            }
            if (value.length() > MAX_AUDIENCE_LENGTH) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "单个令牌受众不能超过 " + MAX_AUDIENCE_LENGTH + " 字符: " + value);
            }
        }
    }

    /**
     * 判定是否为可跳转的 http(s) 地址。
     *
     * @param value 待判定地址
     * @return 以 http:// 或 https:// 开头时返回 true
     */
    private static boolean isHttpUrl(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * 校验按权限可见所需的权限码属于该应用。
     * <p>应用内未找到时回落到平台级权限码。</p>
     *
     * @param app     应用服务
     * @param realmId 身份域主键
     * @throws BusinessException 权限码不存在于当前身份域
     */
    private void validatePermissionBinding(ApplicationService app, Long realmId) {
        if (!VISIBILITY_PERMISSION.equals(app.getVisibility())) {
            return;
        }
        String code = app.getRequiredPermissionCode();
        if (code == null || code.isBlank()) {
            return;
        }
        Permission permission = permissionManager.getByScope(realmId, app.getId(), code);
        if (permission == null) {
            permission = permissionManager.getByScope(realmId, null, code);
        }
        if (permission == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "所需权限编码不存在于当前身份域: " + code);
        }
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return CurrentUserHolder.require().getRealmId();
    }

    /**
     * 读取属于当前域的应用服务。
     *
     * @param id 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long id) {
        ApplicationService app = appServiceManager.getById(id);
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用服务不存在: " + id);
        }
        return app;
    }
}
