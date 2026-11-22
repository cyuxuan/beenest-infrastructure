package club.beenest.iam.persistence.manager;

import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.GroupMemberCount;
import club.beenest.iam.persistence.entity.GroupRole;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.UserGroup;
import club.beenest.iam.persistence.mapper.GroupMapper;
import club.beenest.iam.persistence.mapper.GroupRoleMapper;
import club.beenest.iam.persistence.mapper.RoleMapper;
import club.beenest.iam.persistence.mapper.UserGroupMapper;
import club.beenest.iam.persistence.mapper.UserMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户组存储实现(iam_group / iam_user_group / iam_group_role)。
 * <p>组是权限主体:组角色随组成员一起参与权限公式计算;
 * 删除组时级联清理成员关联与角色关联。</p>
 */
@Component
public class IamGroupManager {

    private static final Logger log = LoggerFactory.getLogger(IamGroupManager.class);

    /**
     * 组成员任职指派。
     *
     * @param userId     成员用户主键
     * @param positionId 在该组担任的岗位(为空表示只归属不任职)
     * @param primary    是否为主部门
     */
    public record MemberAssignment(Long userId, Long positionId, boolean primary) {
    }

    private final GroupMapper groupMapper;
    private final UserGroupMapper userGroupMapper;
    private final GroupRoleMapper groupRoleMapper;
    private final RoleMapper roleMapper;
    /** 权限缓存代次:组相关变更后自增 */
    private final IamPermissionEpoch permissionEpoch;

    public IamGroupManager(GroupMapper groupMapper, UserGroupMapper userGroupMapper,
                           GroupRoleMapper groupRoleMapper, RoleMapper roleMapper,
                           UserMapper userMapper,
                           IamPermissionEpoch permissionEpoch) {
        this.groupMapper = groupMapper;
        this.userGroupMapper = userGroupMapper;
        this.groupRoleMapper = groupRoleMapper;
        this.roleMapper = roleMapper;
        this.permissionEpoch = permissionEpoch;
    }

    // ===== 组 CRUD =====

    /**
     * 按主键查询组。
     *
     * @param id 组主键
     * @return 组,不存在时返回 null
     */
    public Group getById(Long id) {
        return groupMapper.selectById(id);
    }

    /**
     * 按域与组编码查询,用于唯一性校验。
     *
     * @param realmId   域主键
     * @param groupCode 组编码
     * @return 组,不存在时返回 null
     */
    public Group getByCode(Long realmId, String groupCode) {
        return groupMapper.selectByRealmIdAndCode(realmId, groupCode);
    }

    /**
     * 分页查询域内组。
     *
     * @param realmId   域主键
     * @param condition 查询条件
     * @param pageNum   页码,从 1 开始
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public PageResult<Group> page(Long realmId, Group condition, int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<Group> list = groupMapper.selectByCondition(realmId, condition);
        PageInfo<Group> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getPageNum(), pageInfo.getPageSize(),
                pageInfo.getTotal(), pageInfo.getPages(), pageInfo.getList());
    }

    /**
     * 查询域内全部组,供树形装配。
     *
     * @param realmId 域主键
     * @return 组列表
     */
    public List<Group> listByRealm(Long realmId) {
        return groupMapper.selectByRealmId(realmId);
    }

    /**
     * 新建组,并自增该域权限缓存代次。
     *
     * @param group 组实体
     * @return 影响行数
     */
    public int create(Group group) {
        int rows = groupMapper.insert(group);
        permissionEpoch.bump(group.getRealmId());
        return rows;
    }

    /**
     * 更新组非空字段,并自增该域权限缓存代次。
     *
     * @param group 组实体
     * @return 影响行数
     */
    public int update(Group group) {
        int rows = groupMapper.updateById(group);
        permissionEpoch.bump(group.getRealmId());
        return rows;
    }

    /**
     * 逻辑删除组,级联清理成员关联与角色关联。
     * <p>子组提升到被删组的父级,避免子组的 parent_id 悬空;
     * 完成后自增被删组所属域的权限缓存代次。</p>
     *
     * @param id 组主键
     * @return 影响行数
     */
    @Transactional
    public int remove(Long id) {
        // 删除前先取域标识:代次按域自增,删除动作本身不携带域
        Group existing = groupMapper.selectById(id);
        userGroupMapper.deleteByGroupId(id);
        groupRoleMapper.deleteByGroupId(id);
        int moved = groupMapper.reparentChildren(id, existing == null ? null : existing.getParentId());
        if (moved > 0) {
            log.info("删除用户组,其子组已提升到上一级: groupId={}, 子组数={}", id, moved);
        }
        int rows = groupMapper.softDeleteById(id);
        if (existing != null) {
            permissionEpoch.bump(existing.getRealmId());
        }
        return rows;
    }

    // ===== 成员管理 =====

    /**
     * 批量统计各组成员数,单次聚合查询代替逐组计数。
     *
     * @param groupIds 组主键集合
     * @return 组主键到成员数的映射;入参为空时返回空映射
     */
    public Map<Long, Long> countMembers(List<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        return userGroupMapper.countByGroupIds(groupIds).stream()
                .collect(Collectors.toMap(GroupMemberCount::getGroupId,
                        count -> count.getMemberCount() == null ? 0L : count.getMemberCount(),
                        (first, second) -> first));
    }

    /**
     * 分页查询组成员的任职记录。
     * <p>返回用户-组关联行而非用户,岗位与主部门标记在关联行上。
     * 含下级时同一人可能命中多个子部门,按用户主键去重取首条。</p>
     *
     * @param groupId    组主键
     * @param includeSub 是否含全部下级部门
     * @param pageNum    页码,从 1 开始
     * @param pageSize   每页条数
     * @return 任职记录分页结果
     */
    public PageResult<UserGroup> pageMemberRelations(Long groupId, boolean includeSub,
                                                     int pageNum, int pageSize) {
        PageHelper.startPage(pageNum, pageSize);
        List<UserGroup> relations = includeSub
                ? userGroupMapper.selectByGroupIdIncludingSubtree(groupId)
                : userGroupMapper.selectByGroupId(groupId);
        PageInfo<UserGroup> info = new PageInfo<>(relations);
        List<UserGroup> rows = info.getList();
        if (!includeSub) {
            return new PageResult<>(pageNum, pageSize, info.getTotal(), info.getPages(), rows);
        }
        Set<Long> seen = new LinkedHashSet<>();
        List<UserGroup> deduped = new ArrayList<>();
        for (UserGroup relation : rows) {
            if (seen.add(relation.getUserId())) {
                deduped.add(relation);
            }
        }
        return new PageResult<>(pageNum, pageSize, info.getTotal(), info.getPages(), deduped);
    }

    /**
     * 查询用户的全部任职记录。
     *
     * @param userId 用户主键
     * @return 用户-组关联行列表,含岗位与主部门标记
     */
    public List<UserGroup> listRelationsByUserId(Long userId) {
        return userGroupMapper.selectByUserId(userId);
    }

    /**
     * 查询用户所属的全部组。
     *
     * @param userId 用户主键
     * @return 组列表,按关联记录顺序去重
     */
    public List<Group> listByUserId(Long userId) {
        Set<Long> groupIds = new LinkedHashSet<>();
        for (UserGroup relation : userGroupMapper.selectByUserId(userId)) {
            groupIds.add(relation.getGroupId());
        }
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return groupMapper.selectByIds(new ArrayList<>(groupIds));
    }

    /**
     * 更新上级组。
     * <p>通用更新只写非空字段,清空上级组需显式调用 updateParentId。</p>
     *
     * @param id       组主键
     * @param parentId 新的上级组,为空表示提升为顶级组
     * @return 受影响行数
     */
    public int updateParentId(Long id, Long parentId) {
        return groupMapper.updateParentId(id, parentId);
    }

    /**
     * 查询组的全部成员关联。
     *
     * @param groupId 组主键
     * @return 用户-组关联行列表
     */
    public List<UserGroup> listMembers(Long groupId) {
        return userGroupMapper.selectByGroupId(groupId);
    }

    /**
     * 查询组成员的用户主键集合。
     *
     * @param groupId 组主键
     * @return 用户主键列表
     */
    public List<Long> listMemberIds(Long groupId) {
        return userGroupMapper.selectByGroupId(groupId).stream()
                .map(UserGroup::getUserId)
                .toList();
    }

    /**
     * 查询用户所属的全部组主键。
     *
     * @param userId 用户主键
     * @return 组主键列表
     */
    public List<Long> listGroupIdsByUser(Long userId) {
        return userGroupMapper.selectByUserId(userId).stream()
                .map(UserGroup::getGroupId)
                .toList();
    }

    /**
     * 覆盖式设置组成员任职:以目标集合为准,新增缺失关联、更新已有任职、移除多余关联。
     * <p>主部门唯一性由数据库部分唯一索引按语句即时校验,故写入前先清除本次标记为主部门
     * 的用户在其他部门的主部门标记。完成后自增该域权限缓存代次。</p>
     *
     * @param realmId     域主键
     * @param groupId     组主键
     * @param assignments 目标成员任职集合;userId 为空的条目被忽略
     */
    @Transactional
    public void assignMembers(Long realmId, Long groupId, List<MemberAssignment> assignments) {
        Map<Long, UserGroup> current = new LinkedHashMap<>();
        for (UserGroup userGroup : userGroupMapper.selectByGroupId(groupId)) {
            current.put(userGroup.getUserId(), userGroup);
        }
        Map<Long, MemberAssignment> target = new LinkedHashMap<>();
        for (MemberAssignment assignment : assignments == null ? List.<MemberAssignment>of() : assignments) {
            if (assignment.userId() != null) {
                target.put(assignment.userId(), assignment);
            }
        }
        // 1. 先清主部门:同一提交内已在他部门为主的用户需先摘除标记
        for (MemberAssignment assignment : target.values()) {
            if (assignment.primary()) {
                userGroupMapper.clearPrimaryForUser(assignment.userId());
            }
        }
        // 2. 新增 / 更新
        for (MemberAssignment assignment : target.values()) {
            Long positionId = assignment.positionId();
            Boolean primary = assignment.primary();
            if (current.containsKey(assignment.userId())) {
                userGroupMapper.updateAssignment(assignment.userId(), groupId, positionId, primary);
            } else {
                UserGroup userGroup = new UserGroup();
                userGroup.setRealmId(realmId);
                userGroup.setUserId(assignment.userId());
                userGroup.setGroupId(groupId);
                userGroup.setPositionId(positionId);
                userGroup.setIsPrimary(primary);
                userGroupMapper.insert(userGroup);
            }
        }
        // 3. 移除
        for (Long userId : current.keySet()) {
            if (!target.containsKey(userId)) {
                userGroupMapper.deleteByUserIdAndGroupId(userId, groupId);
            }
        }
        // 组成员与岗位变化参与权限求值,自增代次使该域缓存失效
        permissionEpoch.bump(realmId);
    }

    /**
     * 更新部门负责人。
     * <p>负责人仅作展示与流程指派,不参与权限求值,故不抬升权限代次。</p>
     *
     * @param id           组主键
     * @param leaderUserId 负责人用户主键,为空表示清空
     * @return 受影响行数
     */
    public int updateLeaderUserId(Long id, Long leaderUserId) {
        return groupMapper.updateLeaderUserId(id, leaderUserId);
    }

    /**
     * 将单个成员移出组,并自增该组所属域的权限缓存代次。
     *
     * @param groupId 组主键
     * @param userId  用户主键
     * @return 受影响行数
     */
    public int removeMember(Long groupId, Long userId) {
        int rows = userGroupMapper.deleteByUserIdAndGroupId(userId, groupId);
        Group group = groupMapper.selectById(groupId);
        if (group != null) {
            permissionEpoch.bump(group.getRealmId());
        }
        return rows;
    }

    // ===== 组角色管理 =====

    /**
     * 查询组已分配的角色。
     *
     * @param groupId 组主键
     * @return 角色列表
     */
    public List<Role> listRoles(Long groupId) {
        List<Long> roleIds = groupRoleMapper.selectByGroupId(groupId).stream()
                .map(GroupRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectByIds(roleIds);
    }

    /**
     * 覆盖式分配组角色:以目标集合为准,新增缺失关联、移除多余关联。
     * <p>组成员经组继承组角色,分配变化直接改变每个成员的有效权限,
     * 完成后自增该域权限缓存代次。</p>
     *
     * @param realmId  域主键
     * @param groupId  组主键
     * @param roleIds  目标角色主键集合,为空表示清空组角色
     * @param grantedBy 授权人用户主键
     */
    @Transactional
    public void assignRoles(Long realmId, Long groupId, List<Long> roleIds, Long grantedBy) {
        Set<Long> current = new HashSet<>();
        for (GroupRole groupRole : groupRoleMapper.selectByGroupId(groupId)) {
            current.add(groupRole.getRoleId());
        }
        Set<Long> target = new HashSet<>(roleIds == null ? List.of() : roleIds);
        for (Long roleId : target) {
            if (!current.contains(roleId)) {
                GroupRole groupRole = new GroupRole();
                groupRole.setRealmId(realmId);
                groupRole.setGroupId(groupId);
                groupRole.setRoleId(roleId);
                groupRole.setGrantedBy(grantedBy);
                groupRoleMapper.insert(groupRole);
            }
        }
        for (Long roleId : current) {
            if (!target.contains(roleId)) {
                groupRoleMapper.deleteByGroupIdAndRoleId(groupId, roleId);
            }
        }
        permissionEpoch.bump(realmId);
    }
}
