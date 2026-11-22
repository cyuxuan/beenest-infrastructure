package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.GroupCreateRequest;
import club.beenest.iam.admin.dto.GroupMemberResponse;
import club.beenest.iam.admin.dto.GroupMembersRequest;
import club.beenest.iam.admin.dto.GroupResponse;
import club.beenest.iam.admin.dto.GroupRolesRequest;
import club.beenest.iam.admin.dto.GroupTreeNode;
import club.beenest.iam.admin.dto.GroupUpdateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.service.GroupService;
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
 * 用户组(组织架构)管理接口。
 * <p>组角色随组成员一起参与权限公式;成员关系带岗位与主部门标记,
 * 岗位携带的角色同样参与授权。操作范围由当前登录主体所属身份域决定。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * 分页查询用户组。
     *
     * @param groupCode 组编码(模糊匹配,可空)
     * @param groupName 组名称(模糊匹配,可空)
     * @param pageNum   页码
     * @param pageSize  每页条数
     * @return 当前域的分页结果,含成员数与负责人展示名
     */
    @GetMapping
    @PreAuthorize("hasAuthority('group:view')")
    public Result<PageResult<GroupResponse>> page(
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String groupName,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(groupService.page(groupCode, groupName, pageNum, pageSize));
    }

    /**
     * 查询用户组树。
     * <p>按层级返回,父组在子组之前;筛选命中节点时其祖先一并保留,保持树上下文完整。</p>
     *
     * @param groupCode 组编码(模糊匹配,可空)
     * @param groupName 组名称(模糊匹配,可空)
     * @return 树的根节点列表
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('group:view')")
    public Result<List<GroupTreeNode>> tree(
            @RequestParam(required = false) String groupCode,
            @RequestParam(required = false) String groupName) {
        return Result.success(groupService.tree(groupCode, groupName));
    }

    /**
     * 查询单个用户组。
     *
     * @param id 组主键
     * @return 组详情,含成员数与负责人展示名
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('group:view')")
    public Result<GroupResponse> get(@PathVariable Long id) {
        return Result.success(groupService.get(id));
    }

    /**
     * 新建用户组。
     *
     * @param request 新建请求,包含组编码、类型、父组与负责人;组编码域内唯一,类型只接受 DEPARTMENT 与 GROUP
     * @return 新建的组
     * @throws club.beenest.iam.common.exception.BusinessException 组编码重复或父组、负责人不合法
     */
    @PostMapping
    @PreAuthorize("hasAuthority('group:create')")
    @LogAudit(action = "GROUP_CREATE", description = "新建用户组")
    public Result<GroupResponse> create(@Valid @RequestBody GroupCreateRequest request) {
        return Result.success(groupService.create(request));
    }

    /**
     * 更新用户组。
     *
     * @param id      组主键
     * @param request 更新请求;上级组不能是自身或当前组的下级;clearParent、clearLeader 显式清空对应关系
     * @return 更新后的组
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在、层级成环或父组、负责人不合法
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('group:update')")
    @LogAudit(action = "GROUP_UPDATE", description = "更新用户组")
    public Result<GroupResponse> update(@PathVariable Long id,
                                        @Valid @RequestBody GroupUpdateRequest request) {
        return Result.success(groupService.update(id, request));
    }

    /**
     * 删除用户组(级联清理成员与角色关联,子组提升到被删组的父级)。
     *
     * @param id 组主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在或不属于当前域
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('group:delete')")
    @LogAudit(action = "GROUP_DELETE", description = "删除用户组")
    public Result<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return Result.success();
    }

    /**
     * 分页查询组成员(含任职信息)。
     * <p>{@code includeSub=true} 时返回本部门及全部下级部门的成员,对应按组织架构全量取数的场景。</p>
     *
     * @param id         组主键
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @param includeSub 为 true 时包含全部下级部门的成员
     * @return 成员分页结果
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在或不属于当前域
     */
    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('group:view')")
    public Result<PageResult<GroupMemberResponse>> pageMembers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false, defaultValue = "false") boolean includeSub) {
        return Result.success(groupService.pageMembers(id, pageNum, pageSize, includeSub));
    }

    /**
     * 查询组成员主键。
     * <p>成员分配是覆盖式动作,界面回填已选成员依赖完整集合;分页列表未显示的成员
     * 在保存时会被移出组。</p>
     *
     * @param id 组主键
     * @return 成员用户主键全量列表
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在或不属于当前域
     */
    @GetMapping("/{id}/member-ids")
    @PreAuthorize("hasAuthority('group:view')")
    public Result<List<Long>> memberIds(@PathVariable Long id) {
        return Result.success(groupService.memberIds(id));
    }

    /**
     * 覆盖式设置组成员及其任职(岗位、主部门)。
     * <p>一次提交内每个用户最多指定一个主部门;保存后权限代次抬升,失效受影响用户的权限缓存。</p>
     *
     * @param id      组主键
     * @param request 成员任职列表,包含用户、岗位与主部门标记
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在、主部门超限或成员、岗位跨域
     */
    @PutMapping("/{id}/members")
    @PreAuthorize("hasAuthority('group:assign-user')")
    @LogAudit(action = "GROUP_ASSIGN_MEMBERS", description = "设置用户组成员")
    public Result<Void> assignMembers(@PathVariable Long id,
                                      @Valid @RequestBody GroupMembersRequest request) {
        groupService.assignMembers(id, request);
        return Result.success();
    }

    /**
     * 查询组已分配的角色。
     *
     * @param id 组主键
     * @return 组角色列表
     * @throws club.beenest.iam.common.exception.BusinessException 组不存在或不属于当前域
     */
    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('group:view')")
    public Result<List<RoleResponse>> listRoles(@PathVariable Long id) {
        return Result.success(groupService.listRoles(id));
    }

    /**
     * 覆盖式分配组角色。
     * <p>保存后权限代次抬升,影响组内全体成员。</p>
     *
     * @param id      组主键
     * @param request 角色主键集合,必须全部属于当前域
     * @throws club.beenest.iam.common.exception.BusinessException 组或角色不存在、角色跨域
     */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('group:assign-role')")
    @LogAudit(action = "GROUP_ASSIGN_ROLES", description = "分配组角色")
    public Result<Void> assignRoles(@PathVariable Long id,
                                    @Valid @RequestBody GroupRolesRequest request) {
        groupService.assignRoles(id, request);
        return Result.success();
    }
}
