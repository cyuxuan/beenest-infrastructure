package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.AssignRolesRequest;
import club.beenest.iam.admin.dto.RequiredActionsRequest;
import club.beenest.iam.admin.dto.ResetPasswordRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.dto.UserBatchStatusRequest;
import club.beenest.iam.admin.dto.UserCreateRequest;
import club.beenest.iam.admin.dto.UserOrganizationResponse;
import club.beenest.iam.admin.dto.UserResponse;
import club.beenest.iam.admin.dto.UserUpdateRequest;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 用户管理服务。
 * <p>本组操作限定在调用者所属身份域内:身份域是隔离边界,
 * 跨域读写用户等同于越权。</p>
 */
public interface UserService {

    /**
     * 分页查询当前域用户。
     *
     * @param username 登录名模糊匹配,可空
     * @param status   账号状态精确匹配,可空
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     * @return 用户分页结果
     */
    PageResult<UserResponse> page(String username, Integer status, int pageNum, int pageSize);

    /**
     * 查询单个用户。
     *
     * @param id 用户主键
     * @return 用户详情
     * @throws BusinessException 用户不存在或不属于当前域
     */
    UserResponse get(Long id);

    /**
     * 新建用户,可同时设置初始口令。
     * <p>登录名域内唯一;已验证手机号域内唯一;设置初始口令时按域策略校验复杂度、
     * 记录口令历史,并要求用户首次登录改密。</p>
     *
     * @param request 用户创建参数
     * @return 新建后的用户
     * @throws BusinessException 登录名或手机号重复、初始口令不符合域策略
     */
    UserResponse create(UserCreateRequest request);

    /**
     * 更新用户资料,仅覆盖请求中显式给出的字段。
     * <p>状态变更会使令牌状态缓存立即失效;已验证手机号仍受域内唯一约束。</p>
     *
     * @param id      用户主键
     * @param request 用户更新参数
     * @return 更新后的用户
     * @throws BusinessException 用户不存在或手机号被其他账号占用
     */
    UserResponse update(Long id, UserUpdateRequest request);

    /**
     * 逻辑删除用户并停用凭证。
     * <p>不允许删除当前登录账号;删除后其已签发令牌立即失效。</p>
     *
     * @param id 用户主键
     * @throws BusinessException 用户不存在、不属于当前域或为当前登录账号
     */
    void delete(Long id);

    /**
     * 重置用户口令。
     * <p>新口令按域策略校验并记录历史;既有令牌全部失效;用户下次登录必须改密。</p>
     *
     * @param id      用户主键
     * @param request 重置参数,含新口令
     * @throws BusinessException 用户不存在或新口令不符合域策略
     */
    void resetPassword(Long id, ResetPasswordRequest request);

    /**
     * 覆盖式分配用户角色。
     * <p>待分配角色必须全部属于当前域;分配在持久层提升权限代次,域内权限缓存随之失效。</p>
     *
     * @param id      用户主键
     * @param request 角色主键集合
     * @throws BusinessException 用户或角色不存在、角色跨域
     */
    void assignRoles(Long id, AssignRolesRequest request);

    /**
     * 查询用户已分配的角色。
     *
     * @param id 用户主键
     * @return 角色列表
     * @throws BusinessException 用户不存在或不属于当前域
     */
    List<RoleResponse> listRoles(Long id);

    /**
     * 查询用户的组织归属,含部门与任职岗位。
     * <p>主部门排在最前,其余按部门编码排序。</p>
     *
     * @param id 用户主键
     * @return 组织归属列表
     * @throws BusinessException 用户不存在或不属于当前域
     */
    List<UserOrganizationResponse> listOrganizations(Long id);

    /**
     * 锁定用户:状态置为锁定,令牌状态缓存失效,并清除登录失败计数。
     * <p>不允许锁定当前登录账号。</p>
     *
     * @param id 用户主键
     * @throws BusinessException 用户不存在、不属于当前域或为当前登录账号
     */
    void lock(Long id);

    /**
     * 解锁用户:状态恢复正常,令牌状态缓存失效,并清除失败计数与临时锁定标记。
     *
     * @param id 用户主键
     * @throws BusinessException 用户不存在或不属于当前域
     */
    void unlock(Long id);

    /**
     * 强制用户全端下线:递增令牌版本使已签发令牌全部失效,
     * 结束该用户全部活跃会话,并失效权限缓存。
     *
     * @param id 用户主键
     * @return 被结束的会话数量
     * @throws BusinessException 用户不存在或不属于当前域
     */
    int logoutAll(Long id);

    /**
     * 批量启用或禁用用户,他域主键按不存在处理、不生效。
     * <p>锁定带副作用,不参与批量操作;每个受影响用户的令牌状态缓存都会失效。</p>
     *
     * @param request 批量参数,含用户主键集合与目标状态(1 启用 / 0 禁用)
     * @return 实际受影响的行数
     * @throws BusinessException 目标状态不是启用或禁用
     */
    int batchStatus(UserBatchStatusRequest request);

    /**
     * 查询用户当前未完成的待办事项。
     *
     * @param id 用户主键
     * @return 待办事项编码列表
     * @throws BusinessException 用户不存在或不属于当前域
     */
    List<String> listRequiredActions(Long id);

    /**
     * 覆盖式设置用户待办事项,用于管理员要求某人改密、验证邮箱或绑定二步验证。
     * <p>未知编码直接拒绝;传空列表即清空待办。</p>
     *
     * @param id      用户主键
     * @param request 待办事项编码集合
     * @throws BusinessException 用户不存在或编码未知
     */
    void setRequiredActions(Long id, RequiredActionsRequest request);
}
