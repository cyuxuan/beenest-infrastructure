package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.GroupRole;
import club.beenest.iam.persistence.entity.PositionRole;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.entity.UserGroup;
import club.beenest.iam.persistence.entity.UserRole;
import club.beenest.iam.persistence.mapper.GroupRoleMapper;
import club.beenest.iam.persistence.mapper.PositionRoleMapper;
import club.beenest.iam.persistence.mapper.RoleMapper;
import club.beenest.iam.persistence.mapper.UserGroupMapper;
import club.beenest.iam.persistence.mapper.UserRoleMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用户有效角色解析(直授角色 ∪ 组继承角色 ∪ 岗位继承角色)。
 * <p>权限计算、令牌角色声明、门户菜单下发与应用可见性统一使用该口径。
 * 岗位角色按任职生效:用户在部门任职某岗位才继承该岗位的默认角色。</p>
 */
@Component
public class IamEffectiveRoleResolver {

    private final UserRoleMapper userRoleMapper;
    private final UserGroupMapper userGroupMapper;
    private final GroupRoleMapper groupRoleMapper;
    private final PositionRoleMapper positionRoleMapper;
    private final RoleMapper roleMapper;

    public IamEffectiveRoleResolver(UserRoleMapper userRoleMapper, UserGroupMapper userGroupMapper,
                                    GroupRoleMapper groupRoleMapper, PositionRoleMapper positionRoleMapper,
                                    RoleMapper roleMapper) {
        this.userRoleMapper = userRoleMapper;
        this.userGroupMapper = userGroupMapper;
        this.groupRoleMapper = groupRoleMapper;
        this.positionRoleMapper = positionRoleMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 有效角色及其来源。
     *
     * @param role        角色实体
     * @param direct      是否由用户直授
     * @param groupIds    继承该角色的用户组,直授场景可能为空
     * @param positionIds 继承该角色的岗位(因任职而生效),未通过岗位继承时为空
     */
    public record RoleSource(Role role, boolean direct, List<Long> groupIds, List<Long> positionIds) {
    }

    /**
     * 用户所属组主键(限定本域)。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 组主键列表,已去重
     */
    public List<Long> resolveGroupIds(Long realmId, Long userId) {
        return userGroupMapper.selectByUserId(userId).stream()
                .filter(ug -> realmId.equals(ug.getRealmId()))
                .map(UserGroup::getGroupId)
                .distinct()
                .toList();
    }

    /**
     * 用户任职的岗位主键(限定本域,去重)。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 岗位主键列表
     */
    public List<Long> resolvePositionIds(Long realmId, Long userId) {
        return userGroupMapper.selectByUserId(userId).stream()
                .filter(ug -> realmId.equals(ug.getRealmId()))
                .map(UserGroup::getPositionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 有效角色主键:直授、组继承与岗位继承取并集,仅保留本域启用中的角色。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 角色主键列表
     */
    public List<Long> resolveRoleIds(Long realmId, Long userId) {
        return resolve(realmId, userId).stream()
                .map(Role::getId)
                .toList();
    }

    /**
     * 有效角色实体:直授、组继承与岗位继承取并集,仅保留本域启用中的角色。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 角色实体列表
     */
    public List<Role> resolve(Long realmId, Long userId) {
        return resolveWithSources(realmId, userId).stream()
                .map(RoleSource::role)
                .toList();
    }

    /**
     * 有效角色实体及其来源,供权限诊断与管理界面解释授权链路。
     *
     * @param realmId 身份域主键
     * @param userId 用户主键
     * @return 角色及来源列表
     */
    public List<RoleSource> resolveWithSources(Long realmId, Long userId) {
        Map<Long, Boolean> directFlags = new HashMap<>();
        Map<Long, Set<Long>> groupSources = new HashMap<>();
        Map<Long, Set<Long>> positionSources = new HashMap<>();
        for (UserRole userRole : userRoleMapper.selectByUserId(userId)) {
            if (realmId.equals(userRole.getRealmId())) {
                directFlags.putIfAbsent(userRole.getRoleId(), Boolean.TRUE);
            }
        }
        List<Long> groupIds = resolveGroupIds(realmId, userId);
        if (!groupIds.isEmpty()) {
            for (GroupRole groupRole : groupRoleMapper.selectByGroupIds(groupIds)) {
                if (realmId.equals(groupRole.getRealmId())) {
                    directFlags.putIfAbsent(groupRole.getRoleId(), Boolean.FALSE);
                    groupSources.computeIfAbsent(groupRole.getRoleId(), key -> new HashSet<>())
                            .add(groupRole.getGroupId());
                }
            }
        }
        // 岗位角色:按任职生效,与组角色等价并入。岗位本身不鉴权,挂载的角色才鉴权
        List<Long> positionIds = resolvePositionIds(realmId, userId);
        if (!positionIds.isEmpty()) {
            for (PositionRole positionRole : positionRoleMapper.selectByPositionIds(positionIds)) {
                if (realmId.equals(positionRole.getRealmId())) {
                    directFlags.putIfAbsent(positionRole.getRoleId(), Boolean.FALSE);
                    positionSources.computeIfAbsent(positionRole.getRoleId(), key -> new HashSet<>())
                            .add(positionRole.getPositionId());
                }
            }
        }
        if (directFlags.isEmpty()) {
            return List.of();
        }
        List<RoleSource> resolved = new ArrayList<>();
        for (Role role : roleMapper.selectByIds(List.copyOf(directFlags.keySet()))) {
            if (!realmId.equals(role.getRealmId()) || !Boolean.TRUE.equals(role.getIsActive())) {
                continue;
            }
            resolved.add(new RoleSource(role, Boolean.TRUE.equals(directFlags.get(role.getId())),
                    sorted(groupSources.get(role.getId())),
                    sorted(positionSources.get(role.getId()))));
        }
        return List.copyOf(resolved);
    }

    /**
     * 来源主键排序后固化,保证输出顺序稳定。
     *
     * @param ids 来源主键集合
     * @return 升序排列的主键列表;集合为空时返回空列表
     */
    private static List<Long> sorted(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new TreeSet<>(ids));
    }
}
