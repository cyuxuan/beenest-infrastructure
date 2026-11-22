package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.AppWorkspaceResponse;
import club.beenest.iam.admin.dto.MappingSourceResponse;
import club.beenest.iam.admin.dto.MenuCreateRequest;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.MenuUpdateRequest;
import club.beenest.iam.admin.dto.PermissionCreateRequest;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.PermissionUpdateRequest;
import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.RoleUpdateRequest;
import club.beenest.iam.admin.service.AppWorkspaceService;
import club.beenest.iam.admin.service.ApplicationServiceService;
import club.beenest.iam.admin.service.MenuService;
import club.beenest.iam.admin.service.PermissionService;
import club.beenest.iam.admin.service.RoleService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Menu;
import club.beenest.iam.persistence.entity.Permission;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamAppAdminManager;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamMenuManager;
import club.beenest.iam.persistence.manager.IamPermissionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 应用管理工作台实现。
 * <p>应用管理员在此维护本应用的菜单、APP 角色与权限命名空间;资源主键一律以路径中的应用为边界,
 * 请求体中的应用字段不被采信。</p>
 */
@Service
public class AppWorkspaceServiceImpl implements AppWorkspaceService {

    /** 全局应用管理权限:持有者可进入任意应用工作台 */
    private static final String GLOBAL_MANAGE_AUTHORITY = "app-service:update";
    /** 容器类型:域角色(角色映射来源) */
    private static final String CONTAINER_REALM = "REALM";
    /** 容器类型:应用角色(工作台新建角色固定使用) */
    private static final String CONTAINER_APP = "APP";
    /** 映射来源:用户组 */
    private static final String SOURCE_GROUP = "GROUP";
    /** 映射来源:域角色 */
    private static final String SOURCE_REALM_ROLE = "REALM_ROLE";

    private final IamApplicationServiceManager appServiceManager;
    private final IamMenuManager menuManager;
    private final IamRoleManager roleManager;
    private final IamPermissionManager permissionManager;
    private final IamGroupManager groupManager;
    private final IamAppAdminManager appAdminManager;
    private final IamAppRoleMappingManager appRoleMappingManager;
    private final ApplicationServiceService applicationServiceService;
    private final MenuService menuService;
    private final RoleService roleService;
    private final PermissionService permissionService;
    private final RoleDtoConverter roleDtoConverter;

    public AppWorkspaceServiceImpl(IamApplicationServiceManager appServiceManager,
                                   IamMenuManager menuManager, IamRoleManager roleManager,
                                   IamPermissionManager permissionManager, IamGroupManager groupManager,
                                   IamAppAdminManager appAdminManager,
                                   IamAppRoleMappingManager appRoleMappingManager,
                                   ApplicationServiceService applicationServiceService,
                                   MenuService menuService, RoleService roleService,
                                   PermissionService permissionService,
                                   RoleDtoConverter roleDtoConverter) {
        this.appServiceManager = appServiceManager;
        this.menuManager = menuManager;
        this.roleManager = roleManager;
        this.permissionManager = permissionManager;
        this.groupManager = groupManager;
        this.appAdminManager = appAdminManager;
        this.appRoleMappingManager = appRoleMappingManager;
        this.applicationServiceService = applicationServiceService;
        this.menuService = menuService;
        this.roleService = roleService;
        this.permissionService = permissionService;
        this.roleDtoConverter = roleDtoConverter;
    }

    @Override
    public AppWorkspaceResponse overview(Long appId) {
        ApplicationService app = requireAppInRealm(appId);
        Long realmId = app.getRealmId();
        return new AppWorkspaceResponse(
                applicationServiceService.get(appId),
                menuManager.listByAppServiceId(realmId, appId).size(),
                roleManager.listByAppServiceId(realmId, appId).size(),
                permissionManager.listByAppServiceId(realmId, appId).size(),
                appRoleMappingManager.listByAppServiceId(appId).size(),
                appAdminManager.listByAppServiceId(appId).size(),
                isGlobalManager());
    }

    @Override
    public List<MenuResponse> listMenus(Long appId) {
        requireAppInRealm(appId);
        return menuService.listByApp(appId);
    }

    @Override
    public MenuResponse createMenu(Long appId, MenuCreateRequest request) {
        requireAppInRealm(appId);
        // 请求体中的 appServiceId 不可信:工作台只允许在路径指定的应用下建菜单
        MenuCreateRequest scoped = new MenuCreateRequest(appId, request.parentId(), request.menuCode(),
                request.menuName(), request.menuType(), request.path(), request.icon(),
                request.permissionId(), request.sortOrder(), request.enabled());
        return menuService.create(scoped);
    }

    @Override
    public MenuResponse updateMenu(Long appId, Long menuId, MenuUpdateRequest request) {
        requireMenuInApp(appId, menuId);
        return menuService.update(menuId, request);
    }

    @Override
    public void deleteMenu(Long appId, Long menuId) {
        requireMenuInApp(appId, menuId);
        menuService.delete(menuId);
    }

    @Override
    public List<RoleResponse> listRoles(Long appId) {
        requireAppInRealm(appId);
        return roleManager.listByAppServiceId(currentRealmId(), appId).stream()
                .map(roleDtoConverter::toResponse)
                .toList();
    }

    @Override
    public RoleResponse createRole(Long appId, RoleCreateRequest request) {
        requireAppInRealm(appId);
        // 工作台只创建 APP 角色:域角色与客户端角色归属全局管理员
        RoleCreateRequest scoped = new RoleCreateRequest(request.roleCode(), request.roleName(),
                CONTAINER_APP, null, appId);
        return roleService.create(scoped);
    }

    @Override
    public RoleResponse updateRole(Long appId, Long roleId, RoleUpdateRequest request) {
        requireRoleInApp(appId, roleId);
        // 工作台下只改名称与启用状态:归属由路径决定,放开后应用管理员可把本应用角色
        // 提升为域级角色,或挂到别的应用
        RoleUpdateRequest scoped = new RoleUpdateRequest(request.roleName(), request.isActive(),
                null, null, null);
        return roleService.update(roleId, scoped);
    }

    @Override
    public void deleteRole(Long appId, Long roleId) {
        requireRoleInApp(appId, roleId);
        roleService.delete(roleId);
    }

    @Override
    public List<PermissionResponse> listRolePermissions(Long appId, Long roleId) {
        requireRoleInApp(appId, roleId);
        return roleService.listPermissions(roleId);
    }

    @Override
    public List<PermissionResponse> assignRolePermissions(Long appId, Long roleId, List<Long> permissionIds) {
        requireRoleInApp(appId, roleId);
        return roleService.assignPermissions(roleId, permissionIds);
    }

    @Override
    public List<MenuResponse> listRoleMenus(Long appId, Long roleId) {
        requireRoleInApp(appId, roleId);
        return roleService.listMenus(roleId);
    }

    @Override
    public List<MenuResponse> assignRoleMenus(Long appId, Long roleId, List<Long> menuIds) {
        requireRoleInApp(appId, roleId);
        return roleService.assignMenus(roleId, menuIds);
    }

    @Override
    public List<PermissionResponse> listPermissions(Long appId) {
        requireAppInRealm(appId);
        return permissionManager.listByAppServiceId(currentRealmId(), appId).stream()
                .map(AppWorkspaceServiceImpl::toPermissionResponse)
                .toList();
    }

    @Override
    public PermissionResponse createPermission(Long appId, PermissionCreateRequest request) {
        requireAppInRealm(appId);
        // 工作台下不允许创建平台权限:appServiceId 始终以路径为准
        PermissionCreateRequest scoped = new PermissionCreateRequest(appId, request.permissionCode(),
                request.permissionName(), request.resource(), request.action(), request.description());
        return permissionService.create(scoped);
    }

    @Override
    public PermissionResponse updatePermission(Long appId, Long permissionId, PermissionUpdateRequest request) {
        requirePermissionInApp(appId, permissionId);
        return permissionService.update(permissionId, request);
    }

    @Override
    public void deletePermission(Long appId, Long permissionId) {
        requirePermissionInApp(appId, permissionId);
        permissionService.delete(permissionId);
    }

    @Override
    public List<MappingSourceResponse> listMappingSources(Long appId, String sourceType, String keyword) {
        Long realmId = requireAppInRealm(appId).getRealmId();
        String type = sourceType == null ? "" : sourceType.trim().toUpperCase(Locale.ROOT);
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (SOURCE_GROUP.equals(type)) {
            return groupManager.listByRealm(realmId).stream()
                    .filter(group -> matches(needle, group.getGroupCode(), group.getGroupName()))
                    .map(group -> new MappingSourceResponse(group.getId(), group.getGroupCode(),
                            group.getGroupName()))
                    .toList();
        }
        if (SOURCE_REALM_ROLE.equals(type)) {
            return roleManager.listByContainerType(realmId, CONTAINER_REALM).stream()
                    .filter(role -> matches(needle, role.getRoleCode(), role.getRoleName()))
                    .map(role -> new MappingSourceResponse(role.getId(), role.getRoleCode(),
                            role.getRoleName()))
                    .toList();
        }
        throw new BusinessException(ErrorCode.PARAM_INVALID, "映射来源类型只能是 GROUP 或 REALM_ROLE");
    }

    /**
     * 当前调用者是否持有全局应用管理权限,用于前端提示配置归属。
     *
     * @return 持有全局应用管理权限时为 true
     */
    private boolean isGlobalManager() {
        return CurrentUserHolder.require().getAuthorities().contains(GLOBAL_MANAGE_AUTHORITY);
    }

    /**
     * 判断编码或名称是否包含检索词。
     *
     * @param needle 小写检索词
     * @param code   编码
     * @param name   名称
     * @return 命中或检索词为空时为 true
     */
    private static boolean matches(String needle, String code, String name) {
        return needle.isEmpty()
                || (code != null && code.toLowerCase(Locale.ROOT).contains(needle))
                || (name != null && name.toLowerCase(Locale.ROOT).contains(needle));
    }

    /**
     * 权限实体转响应。
     *
     * @param permission 权限实体
     * @return 权限响应
     */
    private static PermissionResponse toPermissionResponse(Permission permission) {
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
     * @param appId 应用服务主键
     * @return 应用服务
     * @throws BusinessException 应用不存在或不属于当前域
     */
    private ApplicationService requireAppInRealm(Long appId) {
        ApplicationService app = appId == null ? null : appServiceManager.getById(appId);
        if (app == null || !currentRealmId().equals(app.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "应用服务不存在: " + appId);
        }
        return app;
    }

    /**
     * 读取属于当前域且属于该应用的菜单。
     *
     * @param appId  应用服务主键
     * @param menuId 菜单主键
     * @return 菜单
     * @throws BusinessException 菜单不存在、不属于当前域或不属于该应用
     */
    private Menu requireMenuInApp(Long appId, Long menuId) {
        Menu menu = menuManager.getById(menuId);
        if (menu == null || !currentRealmId().equals(menu.getRealmId())
                || !appId.equals(menu.getAppServiceId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "菜单不存在: " + menuId);
        }
        return menu;
    }

    /**
     * 读取属于当前域且属于该应用的角色。
     *
     * @param appId  应用服务主键
     * @param roleId 角色主键
     * @return 角色
     * @throws BusinessException 角色不存在、不属于当前域或不属于该应用
     */
    private Role requireRoleInApp(Long appId, Long roleId) {
        Role role = roleManager.getById(roleId);
        if (role == null || !currentRealmId().equals(role.getRealmId())
                || !appId.equals(role.getAppServiceId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在: " + roleId);
        }
        return role;
    }

    /**
     * 读取属于当前域且属于该应用的权限。
     *
     * @param appId        应用服务主键
     * @param permissionId 权限主键
     * @return 权限
     * @throws BusinessException 权限不存在、不属于当前域或不属于该应用
     */
    private Permission requirePermissionInApp(Long appId, Long permissionId) {
        Permission permission = permissionManager.getById(permissionId);
        if (permission == null || !currentRealmId().equals(permission.getRealmId())
                || !appId.equals(permission.getAppServiceId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权限不存在: " + permissionId);
        }
        return permission;
    }
}
