package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AssignMenusRequest;
import club.beenest.iam.admin.dto.AssignPermissionsRequest;
import club.beenest.iam.admin.dto.MenuResponse;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.RoleCreateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.RoleUpdateRequest;
import club.beenest.iam.admin.service.RoleService;
import club.beenest.iam.common.annotation.LogAudit;
import club.beenest.iam.common.result.PageResult;
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
 * 角色管理接口。
 * <p>操作范围由当前登录主体所属身份域决定;角色分域级、应用、客户端三类容器,
 * 应用角色只能授予本应用的权限与菜单。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * 分页查询角色。
     *
     * @param roleCode      角色编码(模糊匹配,可空)
     * @param roleName      角色名称(模糊匹配,可空)
     * @param containerType 容器类型(精确匹配,可空;为空时返回域角色、应用角色与客户端角色)
     * @param clientId      客户端主键(查询历史 CLIENT 角色时用,可空)
     * @param appServiceId  应用主键(查询应用角色时用,可空)
     * @param pageNum       页码
     * @param pageSize      每页条数
     * @return 当前域角色分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('role:view')")
    public Result<PageResult<RoleResponse>> page(
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String roleName,
            @RequestParam(required = false) String containerType,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long appServiceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(roleService.page(roleCode, roleName, containerType, clientId,
                appServiceId, pageNum, pageSize));
    }

    /**
     * 查询单个角色。
     *
     * @param id 角色主键
     * @return 角色详情
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('role:view')")
    public Result<RoleResponse> get(@PathVariable Long id) {
        return Result.success(roleService.get(id));
    }

    /**
     * 新建角色。
     *
     * @param request 新建请求;容器类型限 REALM/APP/CLIENT,REALM 不挂应用与客户端,APP 必须挂本域应用,CLIENT 必须挂本域客户端;角色编码在同作用域唯一
     * @return 新建的角色
     * @throws club.beenest.iam.common.exception.BusinessException 容器类型非法、归属缺失或编码重复
     */
    @PostMapping
    @PreAuthorize("hasAuthority('role:create')")
    @LogAudit(action = "ROLE_CREATE", description = "新建角色")
    public Result<RoleResponse> create(@Valid @RequestBody RoleCreateRequest request) {
        return Result.success(roleService.create(request));
    }

    /**
     * 更新角色。
     *
     * @param id      角色主键
     * @param request 更新请求,仅覆盖显式给出的字段
     * @return 更新后的角色
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于当前域
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:update')")
    @LogAudit(action = "ROLE_UPDATE", description = "更新角色")
    public Result<RoleResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody RoleUpdateRequest request) {
        return Result.success(roleService.update(id, request));
    }

    /**
     * 删除角色(逻辑删除;系统内置角色不允许删除)。
     *
     * @param id 角色主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在、不属于当前域或为系统内置角色
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:delete')")
    @LogAudit(action = "ROLE_DELETE", description = "删除角色")
    public Result<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.success();
    }

    /**
     * 查询角色已直接授予的权限。
     * <p>菜单派生权限不在此列:它们由菜单授予产生,属于另一条聚合路径。</p>
     *
     * @param id 角色主键
     * @return 直接权限列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于当前域
     */
    @GetMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:view')")
    public Result<List<PermissionResponse>> listPermissions(@PathVariable Long id) {
        return Result.success(roleService.listPermissions(id));
    }

    /**
     * 整体覆盖角色的直接权限,菜单派生权限不受影响。
     * <p>权限变更对已登录会话不立即生效:会话持有的是认证时刻的权限快照,
     * 受影响的账号需重新登录(或由管理员执行强制下线)。</p>
     *
     * @param id      角色主键
     * @param request 授权请求,包含目标权限主键集合;空集合表示清空直接权限;应用角色只能授予本应用的权限
     * @return 覆盖后的直接权限列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或存在越权的权限
     */
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:assign-permission')")
    @LogAudit(action = "ROLE_ASSIGN_PERMISSION", description = "分配角色权限")
    public Result<List<PermissionResponse>> assignPermissions(
            @PathVariable Long id, @Valid @RequestBody AssignPermissionsRequest request) {
        return Result.success(roleService.assignPermissions(id, request.permissionIds()));
    }

    /**
     * 查询角色已授予的菜单。
     * <p>菜单授予派生菜单上挂载的权限,与直接权限是两条独立路径。</p>
     *
     * @param id 角色主键
     * @return 已授予的菜单列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或不属于当前域
     */
    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('role:view')")
    public Result<List<MenuResponse>> listMenus(@PathVariable Long id) {
        return Result.success(roleService.listMenus(id));
    }

    /**
     * 整体覆盖角色的菜单授予,授予菜单即派生菜单上挂载的权限。
     *
     * @param id      角色主键
     * @param request 授权请求,包含目标菜单主键集合;空集合表示清空菜单授予;应用角色只能授予本应用的菜单
     * @return 覆盖后的菜单列表
     * @throws club.beenest.iam.common.exception.BusinessException 角色不存在或存在越权的菜单
     */
    @PutMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('role:assign-menu')")
    @LogAudit(action = "ROLE_ASSIGN_MENU", description = "分配角色菜单")
    public Result<List<MenuResponse>> assignMenus(
            @PathVariable Long id, @Valid @RequestBody AssignMenusRequest request) {
        return Result.success(roleService.assignMenus(id, request.menuIds()));
    }
}
