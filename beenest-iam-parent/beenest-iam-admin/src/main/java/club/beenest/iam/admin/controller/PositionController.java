package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.PositionCreateRequest;
import club.beenest.iam.admin.dto.PositionResponse;
import club.beenest.iam.admin.dto.PositionRolesRequest;
import club.beenest.iam.admin.dto.PositionUpdateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.service.PositionService;
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
 * 岗位管理接口。
 * <p>岗位是人事语义的职务;任职该岗位的用户继承岗位挂载的角色,岗位角色变更即批量调整
 * 任职者的权限来源。操作范围由当前登录主体所属身份域决定。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/positions")
public class PositionController {

    private final PositionService positionService;

    public PositionController(PositionService positionService) {
        this.positionService = positionService;
    }

    /**
     * 分页查询岗位。
     *
     * @param positionCode 岗位编码(模糊匹配,可空)
     * @param positionName 岗位名称(模糊匹配,可空)
     * @param pageNum      页码
     * @param pageSize     每页条数
     * @return 当前域岗位分页结果,含角色数
     */
    @GetMapping
    @PreAuthorize("hasAuthority('position:view')")
    public Result<PageResult<PositionResponse>> page(
            @RequestParam(required = false) String positionCode,
            @RequestParam(required = false) String positionName,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(positionService.page(positionCode, positionName, pageNum, pageSize));
    }

    /**
     * 查询单个岗位。
     *
     * @param id 岗位主键
     * @return 岗位详情,含角色数
     * @throws club.beenest.iam.common.exception.BusinessException 岗位不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('position:view')")
    public Result<PositionResponse> get(@PathVariable Long id) {
        return Result.success(positionService.get(id));
    }

    /**
     * 新建岗位。
     *
     * @param request 新建请求,包含岗位编码与名称;编码域内唯一,创建后不可修改
     * @return 新建的岗位
     * @throws club.beenest.iam.common.exception.BusinessException 岗位编码在域内已存在
     */
    @PostMapping
    @PreAuthorize("hasAuthority('position:create')")
    @LogAudit(action = "POSITION_CREATE", description = "新建岗位")
    public Result<PositionResponse> create(@Valid @RequestBody PositionCreateRequest request) {
        return Result.success(positionService.create(request));
    }

    /**
     * 更新岗位。
     * <p>岗位编码不可修改,它是外部系统(HR/AD)对齐岗位的依据。</p>
     *
     * @param id      岗位主键
     * @param request 更新请求
     * @return 更新后的岗位
     * @throws club.beenest.iam.common.exception.BusinessException 岗位不存在或不属于当前域
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('position:update')")
    @LogAudit(action = "POSITION_UPDATE", description = "更新岗位")
    public Result<PositionResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody PositionUpdateRequest request) {
        return Result.success(positionService.update(id, request));
    }

    /**
     * 删除岗位(级联清理岗位角色关联,任职记录保留)。
     *
     * @param id 岗位主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 岗位不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('position:delete')")
    @LogAudit(action = "POSITION_DELETE", description = "删除岗位")
    public Result<Void> delete(@PathVariable Long id) {
        positionService.delete(id);
        return Result.success();
    }

    /**
     * 查询岗位已分配的角色。
     *
     * @param id 岗位主键
     * @return 岗位角色列表
     * @throws club.beenest.iam.common.exception.BusinessException 岗位不存在或不属于当前域
     */
    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('position:view')")
    public Result<List<RoleResponse>> listRoles(@PathVariable Long id) {
        return Result.success(positionService.listRoles(id));
    }

    /**
     * 覆盖式分配岗位角色,分配结果落到所有任职该岗位的用户。
     *
     * @param id      岗位主键
     * @param request 角色主键集合,必须全部属于当前域
     * @throws club.beenest.iam.common.exception.BusinessException 岗位或角色不存在、角色跨域
     */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('position:assign-role')")
    @LogAudit(action = "POSITION_ASSIGN_ROLES", description = "分配岗位角色")
    public Result<Void> assignRoles(@PathVariable Long id,
                                    @Valid @RequestBody PositionRolesRequest request) {
        positionService.assignRoles(id, request);
        return Result.success();
    }
}
