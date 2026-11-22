package club.beenest.iam.admin.controller;

import club.beenest.iam.admin.dto.AssignRolesRequest;
import club.beenest.iam.admin.dto.RequiredActionsRequest;
import club.beenest.iam.admin.dto.ResetPasswordRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.UserBatchStatusRequest;
import club.beenest.iam.admin.dto.UserCreateRequest;
import club.beenest.iam.admin.dto.UserOrganizationResponse;
import club.beenest.iam.admin.dto.UserResponse;
import club.beenest.iam.admin.dto.UserUpdateRequest;
import club.beenest.iam.admin.service.UserService;
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
 * 用户管理接口。
 * <p>操作范围由当前登录主体所属身份域决定,接口不暴露域参数,避免越权读取他域用户。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 分页查询用户。
     *
     * @param username 登录名(模糊匹配,可空)
     * @param status   账号状态(精确匹配,可空)
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 当前域用户分页结果
     */
    @GetMapping
    @PreAuthorize("hasAuthority('user:view')")
    public Result<PageResult<UserResponse>> page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(userService.page(username, status, pageNum, pageSize));
    }

    /**
     * 查询单个用户。
     *
     * @param id 用户主键
     * @return 用户详情
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:view')")
    public Result<UserResponse> get(@PathVariable Long id) {
        return Result.success(userService.get(id));
    }

    /**
     * 新建用户。
     *
     * @param request 新建请求,可同时设置初始口令;登录名域内唯一,已验证手机号域内唯一;初始口令按域策略校验并要求首次登录改密
     * @return 新建的用户
     * @throws club.beenest.iam.common.exception.BusinessException 登录名或手机号重复、初始口令不符合域策略
     */
    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    @LogAudit(action = "USER_CREATE", description = "新建用户")
    public Result<UserResponse> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.success(userService.create(request));
    }

    /**
     * 更新用户资料。
     * <p>状态变更使令牌状态缓存立即失效;已验证手机号仍受域内唯一约束。</p>
     *
     * @param id      用户主键
     * @param request 更新请求,仅覆盖显式给出的字段
     * @return 更新后的用户
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或手机号被其他账号占用
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    @LogAudit(action = "USER_UPDATE", description = "更新用户")
    public Result<UserResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody UserUpdateRequest request) {
        return Result.success(userService.update(id, request));
    }

    /**
     * 删除用户(逻辑删除并停用凭证;已签发令牌立即失效)。
     *
     * @param id 用户主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在、不属于当前域或为当前登录账号
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user:delete')")
    @LogAudit(action = "USER_DELETE", description = "删除用户")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.success();
    }

    /**
     * 重置用户口令。
     * <p>新口令按域策略校验并记录历史;既有令牌全部失效;用户下次登录必须改密。</p>
     *
     * @param id      用户主键
     * @param request 重置请求,含新口令
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或新口令不符合域策略
     */
    @PostMapping("/{id}/password")
    @PreAuthorize("hasAuthority('user:reset-password')")
    @LogAudit(action = "USER_RESET_PASSWORD", description = "重置用户口令")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request);
        return Result.success();
    }

    /**
     * 查询用户当前未完成的待办事项。
     *
     * @param id 用户主键
     * @return 待办事项编码列表
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @GetMapping("/{id}/required-actions")
    @PreAuthorize("hasAuthority('user:view')")
    public Result<List<String>> listRequiredActions(@PathVariable Long id) {
        return Result.success(userService.listRequiredActions(id));
    }

    /**
     * 覆盖式设置用户待办事项(要求改密 / 验证邮箱 / 绑定二步验证)。
     * <p>未知编码直接拒绝;传空列表即清空待办。</p>
     *
     * @param id      用户主键
     * @param request 待办事项编码集合
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或编码未知
     */
    @PutMapping("/{id}/required-actions")
    @PreAuthorize("hasAuthority('user:update')")
    @LogAudit(action = "USER_REQUIRED_ACTIONS", description = "设置用户待办事项")
    public Result<Void> setRequiredActions(@PathVariable Long id,
                                           @Valid @RequestBody RequiredActionsRequest request) {
        userService.setRequiredActions(id, request);
        return Result.success();
    }

    /**
     * 查询用户已分配的角色。
     *
     * @param id 用户主键
     * @return 直接授予的角色列表
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:view')")
    public Result<List<RoleResponse>> listRoles(@PathVariable Long id) {
        return Result.success(userService.listRoles(id));
    }

    /**
     * 查询用户的组织归属(部门、任职岗位、是否主部门)。
     * <p>组织是权限公式的来源之一,该接口直接返回任职明细;主部门排在最前。</p>
     *
     * @param id 用户主键
     * @return 组织归属列表
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @GetMapping("/{id}/organizations")
    @PreAuthorize("hasAuthority('user:view')")
    public Result<List<UserOrganizationResponse>> listOrganizations(@PathVariable Long id) {
        return Result.success(userService.listOrganizations(id));
    }

    /**
     * 分配用户角色(覆盖式)。
     * <p>待分配角色必须全部属于当前域;分配后权限代次抬升,域内权限缓存随之失效。</p>
     *
     * @param id      用户主键
     * @param request 角色主键集合
     * @throws club.beenest.iam.common.exception.BusinessException 用户或角色不存在、角色跨域
     */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:assign-role')")
    @LogAudit(action = "USER_ASSIGN_ROLE", description = "分配用户角色")
    public Result<Void> assignRoles(@PathVariable Long id,
                                    @Valid @RequestBody AssignRolesRequest request) {
        userService.assignRoles(id, request);
        return Result.success();
    }

    /**
     * 锁定用户:状态置为锁定并清理失败计数,已签发令牌立即失效。
     * <p>不允许锁定当前登录账号。</p>
     *
     * @param id 用户主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在、不属于当前域或为当前登录账号
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('user:lock')")
    @LogAudit(action = "USER_LOCK", description = "锁定用户")
    public Result<Void> lock(@PathVariable Long id) {
        userService.lock(id);
        return Result.success();
    }

    /**
     * 解锁用户:恢复正常状态并清除失败计数与临时锁定标记。
     *
     * @param id 用户主键
     * @return 无业务数据的成功响应
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('user:lock')")
    @LogAudit(action = "USER_UNLOCK", description = "解锁用户")
    public Result<Void> unlock(@PathVariable Long id) {
        userService.unlock(id);
        return Result.success();
    }

    /**
     * 强制用户全端下线:令牌版本递增 + 结束全部活跃会话。
     *
     * @param id 用户主键
     * @return 被结束的会话数量
     * @throws club.beenest.iam.common.exception.BusinessException 用户不存在或不属于当前域
     */
    @PostMapping("/{id}/logout")
    @PreAuthorize("hasAuthority('user:logout')")
    @LogAudit(action = "USER_LOGOUT_ALL", description = "强制用户全端下线")
    public Result<Integer> logoutAll(@PathVariable Long id) {
        return Result.success(userService.logoutAll(id));
    }

    /**
     * 批量启用/禁用用户。
     * <p>锁定带副作用,不参与批量操作;他域主键按不存在处理、不生效。</p>
     *
     * @param request 批量请求,含用户主键集合与目标状态(1 启用 / 0 禁用)
     * @return 实际受影响的行数
     * @throws club.beenest.iam.common.exception.BusinessException 目标状态不是启用或禁用
     */
    @PostMapping("/batch-status")
    @PreAuthorize("hasAuthority('user:batch-status')")
    @LogAudit(action = "USER_BATCH_STATUS", description = "批量启用禁用用户")
    public Result<Integer> batchStatus(@Valid @RequestBody UserBatchStatusRequest request) {
        return Result.success(userService.batchStatus(request));
    }
}
