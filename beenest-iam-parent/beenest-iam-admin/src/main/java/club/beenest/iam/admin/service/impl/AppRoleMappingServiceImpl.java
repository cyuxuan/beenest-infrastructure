package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.AppRoleMappingCreateRequest;
import club.beenest.iam.admin.dto.AppRoleMappingResponse;
import club.beenest.iam.admin.service.AppRoleMappingService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.AppRoleMapping;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 应用角色映射管理实现。
 * <p>来源主体限定在当前身份域,REALM_ROLE 只接受域角色;映射在应用授权模式为 MAPPED
 * 时参与令牌计算,其他模式下允许预配置。</p>
 */
@Service
public class AppRoleMappingServiceImpl implements AppRoleMappingService {

    /** 来源类型:用户组 */
    private static final String SOURCE_GROUP = "GROUP";
    /** 来源类型:域角色 */
    private static final String SOURCE_REALM_ROLE = "REALM_ROLE";
    /** 域角色的容器类型 */
    private static final String CONTAINER_REALM = "REALM";

    private final IamApplicationServiceManager appServiceManager;
    private final IamAppRoleMappingManager mappingManager;
    private final IamGroupManager groupManager;
    private final IamRoleManager roleManager;

    public AppRoleMappingServiceImpl(IamApplicationServiceManager appServiceManager,
                                     IamAppRoleMappingManager mappingManager,
                                     IamGroupManager groupManager,
                                     IamRoleManager roleManager) {
        this.appServiceManager = appServiceManager;
        this.mappingManager = mappingManager;
        this.groupManager = groupManager;
        this.roleManager = roleManager;
    }

    @Override
    public List<AppRoleMappingResponse> list(Long appId) {
        requireAppInRealm(appId);
        return mappingManager.listByAppServiceId(appId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public AppRoleMappingResponse create(Long appId, AppRoleMappingCreateRequest request) {
        ApplicationService app = requireAppInRealm(appId);
        Long realmId = app.getRealmId();
        String targetRoleCode = request.targetRoleCode().trim();
        // 1. 来源必须存在且属于本域
        validateSource(realmId, request.sourceType(), request.sourceId());
        // 2. 同来源到同目标角色只保留一条,避免令牌重复与维护歧义
        if (mappingManager.getByAppAndSource(appId, request.sourceType(), request.sourceId(),
                targetRoleCode) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS,
                    "该来源到目标角色的映射已存在: " + targetRoleCode);
        }
        // 3. 落库并回读展示信息
        AppRoleMapping mapping = new AppRoleMapping();
        mapping.setRealmId(realmId);
        mapping.setAppServiceId(appId);
        mapping.setSourceType(request.sourceType());
        mapping.setSourceId(request.sourceId());
        mapping.setTargetRoleCode(targetRoleCode);
        mapping.setEnabled(request.enabled() == null || request.enabled());
        mappingManager.create(mapping);
        return toResponse(mappingManager.getById(mapping.getId()));
    }

    @Override
    public void delete(Long appId, Long mappingId) {
        ApplicationService app = requireAppInRealm(appId);
        AppRoleMapping mapping = mappingManager.getById(mappingId);
        if (mapping == null || !appId.equals(mapping.getAppServiceId())
                || !app.getRealmId().equals(mapping.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色映射不存在: " + mappingId);
        }
        mappingManager.remove(mappingId);
    }

    /**
     * 校验映射来源存在且属于当前域。
     *
     * @param realmId    身份域主键
     * @param sourceType 来源类型,GROUP 或 REALM_ROLE
     * @param sourceId   来源主键
     * @throws BusinessException 来源不存在、不属于当前域或类型非法
     */
    private void validateSource(Long realmId, String sourceType, Long sourceId) {
        if (SOURCE_GROUP.equals(sourceType)) {
            Group group = groupManager.getById(sourceId);
            if (group == null || !realmId.equals(group.getRealmId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "用户组不存在于当前身份域: " + sourceId);
            }
            return;
        }
        if (SOURCE_REALM_ROLE.equals(sourceType)) {
            Role role = roleManager.getById(sourceId);
            if (role == null || !realmId.equals(role.getRealmId())
                    || !CONTAINER_REALM.equals(role.getContainerType())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "域角色不存在于当前身份域: " + sourceId);
            }
            return;
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "来源类型只能是 GROUP 或 REALM_ROLE");
    }

    /**
     * 映射关系转响应,补齐来源展示名与编码。
     *
     * @param mapping 映射关系
     * @return 映射响应
     */
    private AppRoleMappingResponse toResponse(AppRoleMapping mapping) {
        String sourceName = null;
        String sourceCode = null;
        if (SOURCE_GROUP.equals(mapping.getSourceType())) {
            Group group = groupManager.getById(mapping.getSourceId());
            if (group != null) {
                sourceName = group.getGroupName();
                sourceCode = group.getGroupCode();
            }
        } else if (SOURCE_REALM_ROLE.equals(mapping.getSourceType())) {
            Role role = roleManager.getById(mapping.getSourceId());
            if (role != null) {
                sourceName = role.getRoleName();
                sourceCode = role.getRoleCode();
            }
        }
        return new AppRoleMappingResponse(mapping.getId(), mapping.getAppServiceId(),
                mapping.getSourceType(), mapping.getSourceId(), sourceName, sourceCode,
                mapping.getTargetRoleCode(), mapping.getEnabled(), mapping.getCreatedTime());
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
