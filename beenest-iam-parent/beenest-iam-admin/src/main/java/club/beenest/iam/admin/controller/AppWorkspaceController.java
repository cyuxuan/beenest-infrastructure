package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AppWorkspaceResponse;
import club.beenest.iam.admin.dto.AssignMenusRequest;
import club.beenest.iam.admin.dto.AssignPermissionsRequest;
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
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用管理工作台接口(应用作用域)。
 * <p>整个控制器以 {@code @appAuthz.canManage} 为入口,全局管理员或该应用的管理员可访问;
 * 资源主键均按应用归属校验,他应用主键按不存在处理。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/apps/{appId}/workspace")
@PreAuthorize("@appAuthz.canManage(#appId)")
public class AppWorkspaceController {

    private final AppWorkspaceService appWorkspaceService;

    public AppWorkspaceController(AppWorkspaceService appWorkspaceService) {
        this.appWorkspaceService = appWorkspaceService;
    }

    /**
     * 查询应用工作台概览。
     *
     * @param appId 应用主键
     * @return 工作台概览(菜单、角色与权限的规模信息)
     * @throws club.beenest.iam.common.exception.BusinessException 应用不存在或不属于当前身份域
     */
    @GetMapping
    public Result<AppWorkspaceResponse> overview(@PathVariable Long appId) {
        return Result.success(appWorkspaceService.overview(appId));
    }

    /**
     * 查询应用菜单。
     *
     * @param appId 应用主键
     * @return 菜单扁平列表(含停用项)
     */
    @GetMapping("/menus")
    public Result<List<MenuResponse>> listMenus(@PathVariable Long appId) {
        return Result.success(appWorkspaceService.listMenus(appId));
    }

    /**
     * 新建菜单。
     *
     * @param appId   应用主键
     * @param request 新建请求,包含菜单编码、名称、父级与关联权限
     * @return 新建的菜单
     * @throws club.beenest.iam.common.exception.BusinessException 菜单编码已存在,或父菜单不属于该应用
     */
    @PostMapping("/menus")
    @LogAudit(action = "MENU_CREATE", description = "新建菜单")
    public Result<MenuResponse> createMenu(@PathVariable Long appId,
                                           @Valid @RequestBody MenuCreateRequest request) {
        return Result.success(appWorkspaceService.createMenu(appId, request));
    }

    /**
     * 更新菜单。
     *
     * @param appId   应用主键
     * @param menuId  菜单主键
     * @param request 更新请求,仅写入非空字段
     * @return 更新后的菜单
     * @throws club.beenest.iam.common.exception.BusinessException 菜单不存在或不属于该应用
     */
    @PutMapping("/menus/{menuId}")
    @LogAudit(action = "MENU_UPDATE", description = "修改菜单")
    public Result<MenuResponse> updateMenu(@PathVariable Long appId, @PathVariable Long menuId,
                                           @Valid @RequestBody MenuUpdateRequest request) {
        return Result.success(appWorkspaceService.updateMenu(appId, menuId, request));
    }

    /**
     * 删除菜单。
     *
     * @param appId  应用主键
     * @param menuId 菜单主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 菜单不存在或不属于该应用
     */
    @DeleteMapping("/menus/{menuId}")
    @LogAudit(action = "MENU_DELETE", description = "删除菜单")
    public Result<Void> deleteMenu(@PathVariable Long appId, @PathVariable Long menuId) {
        appWorkspaceService.deleteMenu(appId, menuId);
        return Result.success();
    }

    /**
     * 查询应用角色。
     *
     * @param appId 应用主键
     * @return 应用角色列表
     */
    @GetMapping("/roles")
    public Result<List<RoleResponse>> listRoles(@PathVariable Long appId) {
        return Result.success(appWorkspaceService.listRoles(appId));
    }

    /**
     * 新建应用角色。
     *
     * @param appId   应用主键
     * @param request 新建请求,包含角色编码与名称
     * @return 新建的角色
     * @throws club.beenest.iam.common.exception.BusinessException 角色编码在应用内已存在
     */
    @PostMapping("/roles")
    @LogAudit(action = "ROLE_CREATE", description = "新建角色")
    public Result<RoleResponse> createRole(@PathVariable Long appId,
                                           @Valid @RequestBody RoleCreateRequest request) {
        return Result.success(appWorkspaceService.createRole(appId, request));
    }

    /**
     * 更新应用角色。
     *
     * @param appId   应用主键
     * @param roleId  角色主键
     * @param request 更新请求,仅写入非空字段
     * @return 更新后的角色
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于该应用
     */
    @PutMapping("/roles/{roleId}")
    @LogAudit(action = "ROLE_UPDATE", description = "更新角色")
    public Result<RoleResponse> updateRole(@PathVariable Long appId, @PathVariable Long roleId,
                                           @Valid @RequestBody RoleUpdateRequest request) {
        return Result.success(appWorkspaceService.updateRole(appId, roleId, request));
    }

    /**
     * 删除应用角色。
     *
     * @param appId  应用主键
     * @param roleId 角色主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于该应用
     */
    @DeleteMapping("/roles/{roleId}")
    @LogAudit(action = "ROLE_DELETE", description = "删除角色")
    public Result<Void> deleteRole(@PathVariable Long appId, @PathVariable Long roleId) {
        appWorkspaceService.deleteRole(appId, roleId);
        return Result.success();
    }

    /**
     * 查询角色已直接授予的权限。
     *
     * @param appId  应用主键
     * @param roleId 角色主键
     * @return 角色直接权限列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于该应用
     */
    @GetMapping("/roles/{roleId}/permissions")
    public Result<List<PermissionResponse>> listRolePermissions(@PathVariable Long appId,
                                                                @PathVariable Long roleId) {
        return Result.success(appWorkspaceService.listRolePermissions(appId, roleId));
    }

    /**
     * 覆盖式分配角色权限。
     *
     * @param appId   应用主键
     * @param roleId  角色主键
     * @param request 授权请求,包含目标权限主键集合
     * @return 覆盖后的角色权限列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色或权限不属于该应用
     */
    @PutMapping("/roles/{roleId}/permissions")
    @LogAudit(action = "ROLE_ASSIGN_PERMISSION", description = "分配角色权限")
    public Result<List<PermissionResponse>> assignRolePermissions(
            @PathVariable Long appId, @PathVariable Long roleId,
            @Valid @RequestBody AssignPermissionsRequest request) {
        return Result.success(appWorkspaceService.assignRolePermissions(
                appId, roleId, request.permissionIds()));
    }

    /**
     * 查询角色已授予的菜单。
     *
     * @param appId  应用主键
     * @param roleId 角色主键
     * @return 角色菜单列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于该应用
     */
    @GetMapping("/roles/{roleId}/menus")
    public Result<List<MenuResponse>> listRoleMenus(@PathVariable Long appId,
                                                    @PathVariable Long roleId) {
        return Result.success(appWorkspaceService.listRoleMenus(appId, roleId));
    }

    /**
     * 覆盖式分配角色菜单。
     *
     * @param appId   应用主键
     * @param roleId  角色主键
     * @param request 授权请求,包含目标菜单主键集合
     * @return 覆盖后的角色菜单列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色或菜单不属于该应用
     */
    @PutMapping("/roles/{roleId}/menus")
    @LogAudit(action = "ROLE_ASSIGN_MENU", description = "分配角色菜单")
    public Result<List<MenuResponse>> assignRoleMenus(
            @PathVariable Long appId, @PathVariable Long roleId,
            @Valid @RequestBody AssignMenusRequest request) {
        return Result.success(appWorkspaceService.assignRoleMenus(appId, roleId, request.menuIds()));
    }

    /**
     * 查询应用权限。
     *
     * @param appId 应用主键
     * @return 应用命名空间下的权限列表
     */
    @GetMapping("/permissions")
    public Result<List<PermissionResponse>> listPermissions(@PathVariable Long appId) {
        return Result.success(appWorkspaceService.listPermissions(appId));
    }

    /**
     * 新建应用权限。
     *
     * @param appId   应用主键
     * @param request 新建请求,包含权限编码(resource:action)、名称与资源
     * @return 新建的权限
     * @throws club.beenest.iam.common.exception.BusinessException 权限编码格式非法或在应用内已存在
     */
    @PostMapping("/permissions")
    @LogAudit(action = "PERMISSION_CREATE", description = "新建权限")
    public Result<PermissionResponse> createPermission(@PathVariable Long appId,
                                                       @Valid @RequestBody PermissionCreateRequest request) {
        return Result.success(appWorkspaceService.createPermission(appId, request));
    }

    /**
     * 更新应用权限。
     *
     * @param appId        应用主键
     * @param permissionId 权限主键
     * @param request      更新请求,仅允许修改名称与说明
     * @return 更新后的权限
     * @throws club.beenest.iam.common.exception.BusinessException 权限不存在或不属于该应用
     */
    @PutMapping("/permissions/{permissionId}")
    @LogAudit(action = "PERMISSION_UPDATE", description = "更新权限")
    public Result<PermissionResponse> updatePermission(
            @PathVariable Long appId, @PathVariable Long permissionId,
            @Valid @RequestBody PermissionUpdateRequest request) {
        return Result.success(appWorkspaceService.updatePermission(appId, permissionId, request));
    }

    /**
     * 删除应用权限。
     *
     * @param appId        应用主键
     * @param permissionId 权限主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 权限不存在,或仍被角色、菜单、用户特批引用
     */
    @DeleteMapping("/permissions/{permissionId}")
    @LogAudit(action = "PERMISSION_DELETE", description = "删除权限")
    public Result<Void> deletePermission(@PathVariable Long appId, @PathVariable Long permissionId) {
        appWorkspaceService.deletePermission(appId, permissionId);
        return Result.success();
    }

    /**
     * 查询角色映射可选来源。
     *
     * @param appId      应用主键
     * @param sourceType 来源类型:GROUP 或 REALM_ROLE
     * @param keyword    名称模糊过滤,可空
     * @return 可选来源列表
     * @throws club.beenest.iam.common.exception.BusinessException 来源类型非法
     */
    @GetMapping("/mapping-sources")
    public Result<List<MappingSourceResponse>> listMappingSources(
            @PathVariable Long appId,
            @RequestParam String sourceType,
            @RequestParam(required = false) String keyword) {
        return Result.success(appWorkspaceService.listMappingSources(appId, sourceType, keyword));
    }
}
