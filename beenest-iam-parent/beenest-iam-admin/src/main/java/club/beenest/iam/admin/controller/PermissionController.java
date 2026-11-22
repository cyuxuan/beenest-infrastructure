package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.PermissionCreateRequest;
import club.beenest.iam.admin.dto.PermissionResponse;
import club.beenest.iam.admin.dto.PermissionUpdateRequest;
import club.beenest.iam.admin.service.PermissionService;
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

/**
 * 权限目录接口。
 * <p>平台权限随功能发布由初始化脚本定义;应用权限在应用命名空间内维护。
 * 权限码创建后不可修改,角色与权限的关联维护在角色侧。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 分页查询权限。
     *
     * @param permissionCode 权限编码(模糊匹配,可空)
     * @param permissionName 权限名称(模糊匹配,可空)
     * @param resource       资源(精确匹配,可空)
     * @param appServiceId   所属应用主键(可空,不传表示全部命名空间)
     * @param pageNum        页码
     * @param pageSize       每页条数
     * @return 当前域权限分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('permission:view')")
    public Result<PageResult<PermissionResponse>> page(
            @RequestParam(required = false) String permissionCode,
            @RequestParam(required = false) String permissionName,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) Long appServiceId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(permissionService.page(permissionCode, permissionName, resource,
                appServiceId, pageNum, pageSize));
    }

    /**
     * 查询单个权限。
     *
     * @param id 权限主键
     * @return 权限详情
     * @throws club.beenest.iam.common.exception.BusinessException 权限不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('permission:view')")
    public Result<PermissionResponse> get(@PathVariable Long id) {
        return Result.success(permissionService.get(id));
    }

    /**
     * 新建权限(平台级或应用级)。
     *
     * @param request 新建请求,包含权限编码(resource:action)、名称与所属应用;编码在同命名空间内唯一
     * @return 新建的权限
     * @throws club.beenest.iam.common.exception.BusinessException 权限编码重复或所属应用不合法
     */
    @PostMapping
    @PreAuthorize("hasAuthority('permission:create')")
    @LogAudit(action = "PERMISSION_CREATE", description = "新建权限")
    public Result<PermissionResponse> create(@Valid @RequestBody PermissionCreateRequest request) {
        return Result.success(permissionService.create(request));
    }

    /**
     * 更新权限名称/说明。
     *
     * @param id      权限主键
     * @param request 更新请求;权限编码与归属不可修改
     * @return 更新后的权限
     * @throws club.beenest.iam.common.exception.BusinessException 权限不存在或不属于当前域
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('permission:update')")
    @LogAudit(action = "PERMISSION_UPDATE", description = "更新权限")
    public Result<PermissionResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody PermissionUpdateRequest request) {
        return Result.success(permissionService.update(id, request));
    }

    /**
     * 删除未被引用的权限(逻辑删除)。
     *
     * @param id 权限主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 权限不存在、不属于当前域或仍被角色、菜单、用户特批引用
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('permission:delete')")
    @LogAudit(action = "PERMISSION_DELETE", description = "删除权限")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.success();
    }
}
