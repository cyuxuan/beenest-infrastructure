package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.GroupCreateRequest;
import club.beenest.iam.admin.dto.GroupMemberResponse;
import club.beenest.iam.admin.dto.GroupMembersRequest;
import club.beenest.iam.admin.dto.GroupResponse;
import club.beenest.iam.admin.dto.GroupRolesRequest;
import club.beenest.iam.admin.dto.GroupTreeNode;
import club.beenest.iam.admin.dto.GroupUpdateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.common.result.PageResult;

import java.util.List;

/**
 * 用户组(组织架构)管理服务。
 * <p>组是权限主体:组角色随组成员参与权限计算,支持按组织架构批量授权。
 * 组的层级只表达归属,不参与授权继承,子组成员不继承父组角色。</p>
 * <p>成员关系是一份任职,岗位与主部门标记挂在这条关系上,岗位携带角色,调整任职即调整权限。</p>
 */
public interface GroupService {

    /**
     * 分页查询当前域的组。
     *
     * @param groupCode 组编码模糊匹配,可空
     * @param groupName 组名称模糊匹配,可空
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 组分页结果,含成员数与负责人展示名
     */
    PageResult<GroupResponse> page(String groupCode, String groupName, int pageNum, int pageSize);

    /**
     * 查询当前域的用户组树。
     * <p>按层级返回,父组在子组之前;筛选命中节点时其祖先一并保留,保持树上下文完整。</p>
     *
     * @param groupCode 组编码模糊匹配,可空
     * @param groupName 组名称模糊匹配,可空
     * @return 树的根节点列表
     */
    List<GroupTreeNode> tree(String groupCode, String groupName);

    /**
     * 查询单个组。
     *
     * @param id 组主键
     * @return 组详情,含成员数与负责人展示名
     * @throws BusinessException 组不存在或不属于当前域
     */
    GroupResponse get(Long id);

    /**
     * 新建组。
     * <p>组编码域内唯一;父组与负责人必须属于当前域;类型只接受 DEPARTMENT 与 GROUP。</p>
     *
     * @param request 组创建参数
     * @return 新建后的组
     * @throws BusinessException 组编码重复或父组、负责人不合法
     */
    GroupResponse create(GroupCreateRequest request);

    /**
     * 更新组。
     * <p>上级组不能是自身或当前组的下级;clearParent、clearLeader 显式清空对应关系。</p>
     *
     * @param id      组主键
     * @param request 组更新参数
     * @return 更新后的组
     * @throws BusinessException 组不存在、层级成环或父组、负责人不合法
     */
    GroupResponse update(Long id, GroupUpdateRequest request);

    /**
     * 删除组,级联清理成员与角色关联,子组提升到被删组的父级。
     * <p>权限代次由持久层在事务提交后抬升,失效受影响用户的权限缓存。</p>
     *
     * @param id 组主键
     * @throws BusinessException 组不存在或不属于当前域
     */
    void delete(Long id);

    /**
     * 分页查询组成员,含任职信息。
     *
     * @param id         组主键
     * @param pageNum    页码,从 1 开始
     * @param pageSize   每页条数
     * @param includeSub 为 true 时包含全部下级部门的成员
     * @return 成员分页结果
     * @throws BusinessException 组不存在或不属于当前域
     */
    PageResult<GroupMemberResponse> pageMembers(Long id, int pageNum, int pageSize, boolean includeSub);

    /**
     * 查询组成员全量主键。
     * <p>与展示用分页列表分开,供覆盖式分配界面回填已选成员,集合不全时保存会把未显示的成员移出组。</p>
     *
     * @param id 组主键
     * @return 成员用户主键列表
     * @throws BusinessException 组不存在或不属于当前域
     */
    List<Long> memberIds(Long id);

    /**
     * 覆盖式设置组成员及其任职。
     * <p>一次提交内每个用户最多指定一个主部门;成员与岗位必须属于当前域;
     * 保存后权限代次抬升,失效受影响用户的权限缓存。</p>
     *
     * @param id      组主键
     * @param request 成员任职列表
     * @throws BusinessException 组不存在、主部门超限或成员、岗位跨域
     */
    void assignMembers(Long id, GroupMembersRequest request);

    /**
     * 查询组已分配的角色。
     *
     * @param id 组主键
     * @return 角色列表
     * @throws BusinessException 组不存在或不属于当前域
     */
    List<RoleResponse> listRoles(Long id);

    /**
     * 覆盖式分配组角色。
     * <p>角色必须全部属于当前域;保存后权限代次抬升,影响组内全体成员。</p>
     *
     * @param id      组主键
     * @param request 角色主键集合
     * @throws BusinessException 组或角色不存在、角色跨域
     */
    void assignRoles(Long id, GroupRolesRequest request);
}
