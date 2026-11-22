package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.PermissionCreateRequest;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.PermissionUpdateRequest;
import club.beenest.iam.admin.service.PermissionService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import org.springframework.stereotype.Service;

/**
 * 权限目录管理实现。
 * <p>权限码在域与应用命名空间内唯一,平台权限(应用为空)与应用权限互不干扰;
 * 删除前检查是否仍被角色、菜单或用户特批引用。</p>
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    /** 资源列长度上限 */
    private static final int RESOURCE_MAX = 100;
    /** 动作列长度上限 */
    private static final int ACTION_MAX = 50;

    private final IamPermissionManager permissionManager;
    private final IamApplicationServiceManager appServiceManager;

    public PermissionServiceImpl(IamPermissionManager permissionManager,
                                 IamApplicationServiceManager appServiceManager) {
        this.permissionManager = permissionManager;
        this.appServiceManager = appServiceManager;
    }

    @Override
    public PageResult<PermissionResponse> page(String permissionCode, String permissionName,
                                               String resource, Long appServiceId,
                                               int pageNum, int pageSize) {
        // 1. 组装单表查询条件
        Permission condition = new Permission();
        condition.setPermissionCode(permissionCode);
        condition.setPermissionName(permissionName);
        condition.setResource(resource);
        condition.setAppServiceId(appServiceId);
        // 2. 分页查询当前域权限后逐条转换
        PageResult<Permission> page =
                permissionManager.page(currentRealmId(), condition, pageNum, pageSize);
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(PermissionServiceImpl::toResponse).toList());
    }

    @Override
    public PermissionResponse get(Long id) {
        return toResponse(requirePermissionInRealm(id));
    }

    @Override
    public PermissionResponse create(PermissionCreateRequest request) {
        Long realmId = currentRealmId();
        // 1. 应用权限必须挂在本域应用下;平台权限不挂应用
        if (request.appServiceId() != null) {
            requireAppInRealm(request.appServiceId());
        }
        // 2. 权限码格式为 resource:action,未显式给出时从编码拆分
        String code = request.permissionCode().trim();
        int separator = code.indexOf(':');
        if (separator <= 0 || separator == code.length() - 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "权限编码格式必须为 resource:action");
        }
        String resource = blankToDefault(request.resource(), code.substring(0, separator));
        String action = blankToDefault(request.action(), code.substring(separator + 1));
        if (resource.length() > RESOURCE_MAX || action.length() > ACTION_MAX) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "资源或动作长度超出限制");
        }
        // 3. 同一命名空间内权限码唯一
        if (permissionManager.getByScope(realmId, request.appServiceId(), code) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "权限编码已存在: " + code);
        }
        // 4. 落库并回读完整字段
        Permission permission = new Permission();
        permission.setRealmId(realmId);
        permission.setAppServiceId(request.appServiceId());
        permission.setPermissionCode(code);
        permission.setPermissionName(request.permissionName());
        permission.setResource(resource);
        permission.setAction(action);
        permission.setDescription(request.description());
        permissionManager.create(permission);
        return toResponse(permissionManager.getById(permission.getId()));
    }

    @Override
    public PermissionResponse update(Long id, PermissionUpdateRequest request) {
        // 1. 目标权限必须在当前域
        Permission permission = requirePermissionInRealm(id);
        // 2. 权限码/资源/动作/所属应用不可变,只更新展示信息
        if (request.permissionName() != null) {
            permission.setPermissionName(request.permissionName());
        }
        if (request.description() != null) {
            permission.setDescription(request.description());
        }
        permissionManager.update(permission);
        return toResponse(permissionManager.getById(id));
    }

    @Override
    public void delete(Long id) {
        // 1. 目标权限必须在当前域
        requirePermissionInRealm(id);
        // 2. 仍被角色、菜单或用户特批引用时拒绝删除,避免授权关系悬空
        if (permissionManager.isInUse(id)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "权限仍被角色、菜单或用户授权引用,不能删除");
        }
        permissionManager.remove(id);
    }

    /**
     * 权限实体转响应。
     *
     * @param permission 权限
     * @return 权限响应
     */
    private static PermissionResponse toResponse(Permission permission) {
        return new PermissionResponse(
                permission.getId(),
                permission.getRealmId(),
                permission.getAppServiceId(),
                permission.getPermissionCode(),
                permission.getPermissionName(),
                permission.getResource(),
                permission.getAction(),
                permission.getDescription(),
                permission.getCreatedTime(),
                permission.getUpdatedTime());
    }

    /**
     * 空白值取默认值。
     *
     * @param value    原始值
     * @param fallback 默认值
     * @return 去空白后的原始值或默认值
     */
    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
     * 读取属于当前域的权限。
     *
     * @param id 权限主键
     * @return 权限
     * @throws BusinessException 权限不存在或不属于当前域
     */
    private Permission requirePermissionInRealm(Long id) {
        Permission permission = permissionManager.getById(id);
        if (permission == null || !currentRealmId().equals(permission.getRealmId())) {
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
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "应用服务不存在: " + appServiceId);
        }
        return app;
    }
}
