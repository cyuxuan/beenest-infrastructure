package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.GroupCreateRequest;
import club.beenest.iam.admin.dto.GroupMemberAssignment;
import club.beenest.iam.admin.dto.GroupMemberResponse;
import club.beenest.iam.admin.dto.GroupMembersRequest;
import club.beenest.iam.admin.dto.GroupResponse;
import club.beenest.iam.admin.dto.GroupRolesRequest;
import club.beenest.iam.admin.dto.GroupTreeNode;
import club.beenest.iam.admin.dto.GroupUpdateRequest;
import club.beenest.iam.admin.dto.RoleResponse;
import club.beenest.iam.admin.service.GroupService;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.result.PageResult;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.entity.UserGroup;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户组管理实现(组织架构与任职)。
 * <p>组与任职关系限定在当前身份域;组成员与组角色为覆盖式分配,保存后由持久层抬升权限代次,
 * 受影响用户的权限缓存随之失效。</p>
 * <p>组层级只表达归属,不参与授权继承:角色由组自身挂载,成员按组继承角色,子组成员不继承父组角色。
 * 部门({@code groupType=DEPARTMENT})与普通组共用同一棵树,成员关系带岗位与主部门标记。</p>
 */
@Service
public class GroupServiceImpl implements GroupService {

    /**
     * 组层级深度上限,作为树装配递归与环检测上溯的终止条件。
     */
    private static final int MAX_GROUP_DEPTH = 64;

    /** 组织类型:部门 */
    private static final String TYPE_DEPARTMENT = "DEPARTMENT";
    /** 组织类型:普通用户组 */
    private static final String TYPE_GROUP = "GROUP";

    private final IamGroupManager groupManager;
    private final IamRoleManager roleManager;
    private final IamUserManager userManager;
    private final IamPositionManager positionManager;
    private final RoleDtoConverter roleDtoConverter;

    public GroupServiceImpl(IamGroupManager groupManager, IamRoleManager roleManager,
                            IamUserManager userManager, IamPositionManager positionManager,
                            RoleDtoConverter roleDtoConverter) {
        this.groupManager = groupManager;
        this.roleManager = roleManager;
        this.userManager = userManager;
        this.positionManager = positionManager;
        this.roleDtoConverter = roleDtoConverter;
    }

    @Override
    public PageResult<GroupResponse> page(String groupCode, String groupName, int pageNum, int pageSize) {
        Group condition = new Group();
        condition.setGroupCode(groupCode);
        condition.setGroupName(groupName);
        PageResult<Group> page = groupManager.page(currentRealmId(), condition, pageNum, pageSize);
        // 成员数一次聚合取回
        Map<Long, Long> counts = groupManager.countMembers(
                page.getRecords().stream().map(Group::getId).toList());
        Map<Long, String> leaderNames = leaderNames(page.getRecords());
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream()
                        .map(group -> toResponse(group, counts.getOrDefault(group.getId(), 0L),
                                leaderNames.get(group.getLeaderUserId())))
                        .toList());
    }

    @Override
    public List<GroupTreeNode> tree(String groupCode, String groupName) {
        Long realmId = currentRealmId();
        // 1. 本域全部组
        List<Group> all = groupManager.listByRealm(realmId);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, Group> byId = all.stream()
                .collect(Collectors.toMap(Group::getId, Function.identity(), (first, second) -> first));
        // 2. 成员数一次聚合
        Map<Long, Long> counts = groupManager.countMembers(all.stream().map(Group::getId).toList());
        Map<Long, String> leaderNames = leaderNames(all);
        // 3. 筛选:命中节点及其祖先一并保留
        Set<Long> included = new LinkedHashSet<>();
        for (Group group : all) {
            if (!matches(group, groupCode, groupName)) {
                continue;
            }
            Long cursor = group.getId();
            for (int depth = 0; cursor != null && depth < MAX_GROUP_DEPTH; depth++) {
                included.add(cursor);
                Group node = byId.get(cursor);
                cursor = node == null ? null : node.getParentId();
            }
        }
        if (included.isEmpty()) {
            return List.of();
        }
        // 4. 装配成树
        return buildTree(all, byId, included, counts, leaderNames);
    }

    @Override
    public GroupResponse get(Long id) {
        Group group = requireGroupInRealm(id);
        Map<Long, String> leaderNames = leaderNames(List.of(group));
        return toResponse(group, groupManager.countMembers(List.of(id)).getOrDefault(id, 0L),
                leaderNames.get(group.getLeaderUserId()));
    }

    @Override
    public GroupResponse create(GroupCreateRequest request) {
        Long realmId = currentRealmId();
        // 1. 组编码域内唯一
        if (groupManager.getByCode(realmId, request.groupCode()) != null) {
            throw new BusinessException(ErrorCode.ALREADY_EXISTS, "组编码已存在: " + request.groupCode());
        }
        // 2. 父组必须属于当前域(防止跨域挂靠)
        if (request.parentId() != null) {
            requireGroupInRealm(request.parentId());
        }
        // 3. 负责人必须是本域用户
        if (request.leaderUserId() != null) {
            requireUserInRealm(request.leaderUserId());
        }
        Group group = new Group();
        group.setRealmId(realmId);
        group.setParentId(request.parentId());
        group.setGroupCode(request.groupCode());
        group.setGroupName(request.groupName());
        group.setGroupType(normalizeType(request.groupType()));
        group.setLeaderUserId(request.leaderUserId());
        group.setSortOrder(request.sortOrder());
        groupManager.create(group);
        return get(group.getId());
    }

    @Override
    @Transactional
    public GroupResponse update(Long id, GroupUpdateRequest request) {
        Group group = requireGroupInRealm(id);
        // 上级组变更单独走一条语句:通用更新只写非空字段
        Long newParentId = group.getParentId();
        boolean parentChanged = false;
        if (Boolean.TRUE.equals(request.clearParent())) {
            // 显式提升为顶级组
            newParentId = null;
            parentChanged = true;
        } else if (request.parentId() != null) {
            if (request.parentId().equals(id)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "父组不能是自身");
            }
            requireGroupInRealm(request.parentId());
            // 上级组不能落在自己的子树里,避免层级成环
            assertNotDescendant(id, request.parentId());
            newParentId = request.parentId();
            parentChanged = true;
        }
        if (request.groupName() != null) {
            group.setGroupName(request.groupName());
        }
        if (request.groupType() != null) {
            group.setGroupType(normalizeType(request.groupType()));
        }
        if (request.sortOrder() != null) {
            group.setSortOrder(request.sortOrder());
        }
        groupManager.update(group);
        if (parentChanged) {
            groupManager.updateParentId(id, newParentId);
        }
        // 负责人:传值即校验后更新,clearLeader 显式清空;两者都没给表示不动
        boolean leaderChanged = false;
        Long newLeader = null;
        if (Boolean.TRUE.equals(request.clearLeader())) {
            leaderChanged = true;
        } else if (request.leaderUserId() != null) {
            requireUserInRealm(request.leaderUserId());
            newLeader = request.leaderUserId();
            leaderChanged = true;
        }
        if (leaderChanged) {
            groupManager.updateLeaderUserId(id, newLeader);
        }
        return get(id);
    }

    @Override
    public void delete(Long id) {
        requireGroupInRealm(id);
        // 删除级联清理成员与角色关联,子组提升到被删组的父级;权限代次由持久层在事务提交后抬升
        groupManager.remove(id);
    }

    @Override
    public PageResult<GroupMemberResponse> pageMembers(Long id, int pageNum, int pageSize, boolean includeSub) {
        requireGroupInRealm(id);
        PageResult<UserGroup> page = groupManager.pageMemberRelations(id, includeSub, pageNum, pageSize);
        List<UserGroup> relations = page.getRecords();
        if (relations.isEmpty()) {
            return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(),
                    page.getPages(), List.of());
        }
        // 用户、部门、岗位各一次批量取回
        Long realmId = currentRealmId();
        Map<Long, User> users = userManager.listByIds(relations.stream().map(UserGroup::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (first, second) -> first));
        Map<Long, Group> groups = groupManager.listByRealm(realmId).stream()
                .collect(Collectors.toMap(Group::getId, Function.identity(), (first, second) -> first));
        Map<Long, Position> positions = positionManager.listByIds(
                        relations.stream().map(UserGroup::getPositionId)
                                .filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Position::getId, Function.identity(), (first, second) -> first));
        List<GroupMemberResponse> rows = relations.stream()
                .map(relation -> toMemberResponse(relation, users, groups, positions, realmId))
                .filter(Objects::nonNull)
                .toList();
        return new PageResult<>(page.getPageNum(), page.getPageSize(), page.getTotal(), page.getPages(), rows);
    }

    @Override
    public List<Long> memberIds(Long id) {
        requireGroupInRealm(id);
        return groupManager.listMemberIds(id);
    }

    @Override
    public void assignMembers(Long id, GroupMembersRequest request) {
        requireGroupInRealm(id);
        Long realmId = currentRealmId();
        List<GroupMemberAssignment> members = request.members() == null ? List.of() : request.members();
        // 1. 至多一个主部门:主部门决定令牌里的 dept 声明
        long primaryCount = members.stream()
                .filter(member -> Boolean.TRUE.equals(member.primary()))
                .map(GroupMemberAssignment::userId)
                .distinct()
                .count();
        if (primaryCount > 1) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "一次提交里最多只能指定一个主部门");
        }
        // 2. 成员必须属于当前域,一次批量取回
        Map<Long, User> users = userManager.listByIds(
                        members.stream().map(GroupMemberAssignment::userId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (first, second) -> first));
        for (GroupMemberAssignment member : members) {
            User user = users.get(member.userId());
            if (user == null || !realmId.equals(user.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + member.userId());
            }
        }
        // 3. 岗位必须属于当前域,岗位携带角色
        Map<Long, Position> positions = positionManager.listByIds(members.stream()
                        .map(GroupMemberAssignment::positionId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(Position::getId, Function.identity(), (first, second) -> first));
        for (Long positionId : members.stream()
                .map(GroupMemberAssignment::positionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList()) {
            Position position = positions.get(positionId);
            if (position == null || !realmId.equals(position.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "岗位不存在: " + positionId);
            }
        }
        groupManager.assignMembers(realmId, id,
                members.stream()
                        .map(member -> new IamGroupManager.MemberAssignment(
                                member.userId(), member.positionId(), Boolean.TRUE.equals(member.primary())))
                        .toList());
        // 成员变化影响组角色的继承范围,权限代次由持久层在事务提交后抬升
    }

    @Override
    public List<RoleResponse> listRoles(Long id) {
        requireGroupInRealm(id);
        return groupManager.listRoles(id).stream()
                .map(roleDtoConverter::toResponse)
                .toList();
    }

    @Override
    public void assignRoles(Long id, GroupRolesRequest request) {
        requireGroupInRealm(id);
        // 角色必须全部属于当前域
        for (Long roleId : request.roleIds()) {
            Role role = roleManager.getById(roleId);
            if (role == null || !currentRealmId().equals(role.getRealmId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在: " + roleId);
            }
        }
        groupManager.assignRoles(currentRealmId(), id, request.roleIds(), currentPrincipal().getUserId());
        // 组角色变化影响全体成员,权限代次由持久层在事务提交后抬升
    }

    // ===== 内部实现 =====

    /**
     * 装配组树。
     * <p>没有父级、父级不在可见集合内或父级已不存在的组作为根;成环且未被访问的组补为顶级组。</p>
     *
     * @param all         当前域全部组
     * @param byId        组主键到组的映射
     * @param included    需要展示的组主键
     * @param counts      组成员数
     * @param leaderNames 负责人展示名
     * @return 组树
     */
    private List<GroupTreeNode> buildTree(List<Group> all, Map<Long, Group> byId,
                                         Set<Long> included, Map<Long, Long> counts,
                                         Map<Long, String> leaderNames) {
        // 1. 子节点索引与根候选
        Map<Long, List<Group>> childrenByParent = new LinkedHashMap<>();
        List<Group> roots = new ArrayList<>();
        for (Group group : all) {
            if (!included.contains(group.getId())) {
                continue;
            }
            Long parentId = group.getParentId();
            boolean parentVisible = parentId != null
                    && included.contains(parentId) && byId.containsKey(parentId);
            if (parentVisible) {
                childrenByParent.computeIfAbsent(parentId, key -> new ArrayList<>()).add(group);
            } else {
                roots.add(group);
            }
        }
        // 2. 从根遍历
        Set<Long> visited = new HashSet<>();
        List<GroupTreeNode> tree = new ArrayList<>();
        for (Group root : roots) {
            GroupTreeNode node = toTreeNode(root, 0, root.getGroupName(),
                    childrenByParent, counts, leaderNames, visited, 0);
            if (node != null) {
                tree.add(node);
            }
        }
        // 3. 成环的组补成顶级组,保证可见
        for (Group group : all) {
            if (!included.contains(group.getId()) || visited.contains(group.getId())) {
                continue;
            }
            GroupTreeNode node = toTreeNode(group, 0, group.getGroupName(),
                    childrenByParent, counts, leaderNames, visited, 0);
            if (node != null) {
                tree.add(node);
            }
        }
        return tree;
    }

    /**
     * 组转树节点,递归装配子节点。
     *
     * @param group            当前组
     * @param depth            节点深度
     * @param path             节点路径
     * @param childrenByParent 父组主键到子组列表的映射
     * @param counts           组成员数
     * @param leaderNames      负责人展示名
     * @param visited          已访问组主键
     * @param guard            递归深度
     * @return 树节点,超过深度上限或重复访问时为 null
     */
    private GroupTreeNode toTreeNode(Group group, int depth, String path,
                                     Map<Long, List<Group>> childrenByParent,
                                     Map<Long, Long> counts, Map<Long, String> leaderNames,
                                     Set<Long> visited, int guard) {
        if (guard > MAX_GROUP_DEPTH || !visited.add(group.getId())) {
            return null;
        }
        List<GroupTreeNode> children = new ArrayList<>();
        for (Group child : childrenByParent.getOrDefault(group.getId(), List.of())) {
            GroupTreeNode childNode = toTreeNode(child, depth + 1,
                    path + " / " + child.getGroupName(), childrenByParent, counts, leaderNames,
                    visited, guard + 1);
            if (childNode != null) {
                children.add(childNode);
            }
        }
        return new GroupTreeNode(group.getId(), group.getParentId(), group.getGroupCode(),
                group.getGroupName(), group.getGroupType(), group.getLeaderUserId(),
                leaderNames.get(group.getLeaderUserId()),
                counts.getOrDefault(group.getId(), 0L), depth, path,
                group.getCreatedTime(), children);
    }

    /**
     * 断言新上级组不在当前组的子树内,沿父链上溯并有深度上限。
     *
     * @param groupId           当前组主键
     * @param candidateParentId 候选上级组主键
     * @throws BusinessException 上级组是当前组的下级
     */
    private void assertNotDescendant(Long groupId, Long candidateParentId) {
        Long cursor = candidateParentId;
        for (int depth = 0; cursor != null && depth < MAX_GROUP_DEPTH; depth++) {
            if (groupId.equals(cursor)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "上级组不能是当前组的下级,否则会形成环");
            }
            Group parent = groupManager.getById(cursor);
            cursor = (parent == null || !currentRealmId().equals(parent.getRealmId()))
                    ? null : parent.getParentId();
        }
    }

    /**
     * 批量取回部门负责人展示名。
     *
     * @param groups 组列表
     * @return 用户主键到展示名的映射,可为空
     */
    private Map<Long, String> leaderNames(List<Group> groups) {
        List<Long> leaderIds = groups.stream()
                .map(Group::getLeaderUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (leaderIds.isEmpty()) {
            // 调用方会以 null 键取未设置负责人,返回允许 null 键的 Map
            return new HashMap<>();
        }
        return userManager.listByIds(leaderIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getNickname() == null || user.getNickname().isBlank()
                                ? user.getUsername() : user.getNickname(),
                        (first, second) -> first));
    }

    /**
     * 任职关联行转成员视图,用户已不存在或跨域时返回 null。
     *
     * @param relation  任职关联
     * @param users     用户主键到用户的映射
     * @param groups    组主键到组的映射
     * @param positions 岗位主键到岗位的映射
     * @param realmId   身份域主键
     * @return 成员视图,用户不存在时为 null
     */
    private GroupMemberResponse toMemberResponse(UserGroup relation, Map<Long, User> users,
                                                 Map<Long, Group> groups, Map<Long, Position> positions,
                                                 Long realmId) {
        User user = users.get(relation.getUserId());
        if (user == null || !realmId.equals(user.getRealmId())) {
            return null;
        }
        Group group = groups.get(relation.getGroupId());
        Position position = relation.getPositionId() == null
                ? null : positions.get(relation.getPositionId());
        return new GroupMemberResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getStatus(),
                group == null ? relation.getGroupId() : group.getId(),
                group == null ? "" : group.getGroupName(),
                position == null ? null : position.getId(),
                position == null ? null : position.getPositionCode(),
                position == null ? null : position.getPositionName(),
                Boolean.TRUE.equals(relation.getIsPrimary()));
    }

    /**
     * 归一化组织类型,只接受 DEPARTMENT 与 GROUP。
     *
     * @param raw 原始类型
     * @return 归一化后的类型,空值或未知值回落为 GROUP
     */
    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TYPE_GROUP;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        return TYPE_DEPARTMENT.equals(type) ? TYPE_DEPARTMENT : TYPE_GROUP;
    }

    /**
     * 判断组是否命中编码或名称筛选。
     *
     * @param group     组
     * @param groupCode 编码筛选词
     * @param groupName 名称筛选词
     * @return 命中或筛选词均为空时为 true
     */
    private static boolean matches(Group group, String groupCode, String groupName) {
        if (isBlank(groupCode) && isBlank(groupName)) {
            return true;
        }
        if (!isBlank(groupCode) && contains(group.getGroupCode(), groupCode)) {
            return true;
        }
        return !isBlank(groupName) && contains(group.getGroupName(), groupName);
    }

    /**
     * 判断值是否包含关键词,忽略大小写。
     *
     * @param value   待检索值
     * @param keyword 关键词
     * @return 包含时为 true
     */
    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 待判定值
     * @return 为 null 或全空白时为 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 组转响应。
     *
     * @param group       组
     * @param memberCount 成员数
     * @param leaderName  负责人展示名
     * @return 组响应
     */
    private GroupResponse toResponse(Group group, long memberCount, String leaderName) {
        return new GroupResponse(group.getId(), group.getParentId(), group.getGroupCode(),
                group.getGroupName(), group.getGroupType(), group.getLeaderUserId(), leaderName,
                group.getSortOrder(), group.getExternalId(), memberCount, group.getCreatedTime());
    }

    /**
     * 当前调用者的身份域主键。
     *
     * @return 身份域主键
     */
    private Long currentRealmId() {
        return currentPrincipal().getRealmId();
    }

    /**
     * 当前调用者主体。
     *
     * @return 用户主体
     */
    private IamUserPrincipal currentPrincipal() {
        return CurrentUserHolder.require();
    }

    /**
     * 读取属于当前域的组。
     *
     * @param id 组主键
     * @return 组
     * @throws BusinessException 组不存在或不属于当前域
     */
    private Group requireGroupInRealm(Long id) {
        Group group = groupManager.getById(id);
        if (group == null || !currentRealmId().equals(group.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户组不存在: " + id);
        }
        return group;
    }

    /**
     * 校验用户属于当前域。
     *
     * @param userId 用户主键
     * @throws BusinessException 用户不存在或不属于当前域
     */
    private void requireUserInRealm(Long userId) {
        User user = userManager.getById(userId);
        if (user == null || !currentRealmId().equals(user.getRealmId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: " + userId);
        }
    }
}
