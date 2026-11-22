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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 有效角色解析测试。
 * <p>直授与组继承必须合并去重,停用角色与跨域关联不参与解析,
 * 并保留每个角色的组继承来源供权限诊断使用。</p>
 */
class IamEffectiveRoleResolverTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 9L;
    private static final Long DIRECT_ROLE_ID = 10L;
    private static final Long GROUP_ROLE_ID = 14L;
    private static final Long DISABLED_ROLE_ID = 11L;
    private static final Long POSITION_ROLE_ID = 18L;
    private static final Long POSITION_ID = 21L;

    private final UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
    private final UserGroupMapper userGroupMapper = mock(UserGroupMapper.class);
    private final GroupRoleMapper groupRoleMapper = mock(GroupRoleMapper.class);
    private final PositionRoleMapper positionRoleMapper = mock(PositionRoleMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);

    private final IamEffectiveRoleResolver resolver = new IamEffectiveRoleResolver(
            userRoleMapper, userGroupMapper, groupRoleMapper, positionRoleMapper, roleMapper);

    @Test
    @DisplayName("合并直授与组继承角色并保留来源")
    void mergesDirectAndGroupRolesWithSources() {
        when(userRoleMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userRole(DIRECT_ROLE_ID, REALM_ID),
                userRole(99L, 2L)));
        when(userGroupMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userGroup(5L, REALM_ID),
                userGroup(7L, REALM_ID),
                userGroup(8L, 2L)));
        when(groupRoleMapper.selectByGroupIds(List.of(5L, 7L))).thenReturn(List.of(
                groupRole(DIRECT_ROLE_ID, 5L, REALM_ID),
                groupRole(DIRECT_ROLE_ID, 7L, REALM_ID),
                groupRole(GROUP_ROLE_ID, 7L, REALM_ID),
                groupRole(DISABLED_ROLE_ID, 5L, REALM_ID)));
        when(roleMapper.selectByIds(any())).thenReturn(List.of(
                role(DIRECT_ROLE_ID, REALM_ID, true),
                role(GROUP_ROLE_ID, REALM_ID, true),
                role(DISABLED_ROLE_ID, REALM_ID, false),
                role(77L, 2L, true)));

        List<IamEffectiveRoleResolver.RoleSource> sources = resolver.resolveWithSources(REALM_ID, USER_ID);
        Map<Long, IamEffectiveRoleResolver.RoleSource> byRole = sources.stream()
                .collect(Collectors.toMap(source -> source.role().getId(), Function.identity()));

        assertEquals(2, sources.size());
        assertTrue(byRole.get(DIRECT_ROLE_ID).direct());
        assertEquals(List.of(5L, 7L), byRole.get(DIRECT_ROLE_ID).groupIds());
        assertFalse(byRole.get(GROUP_ROLE_ID).direct());
        assertEquals(List.of(7L), byRole.get(GROUP_ROLE_ID).groupIds());
        assertEquals(List.of(), byRole.get(GROUP_ROLE_ID).positionIds());
        assertFalse(byRole.containsKey(DISABLED_ROLE_ID));
    }

    @Test
    @DisplayName("任职岗位携带的角色并入有效角色并标注岗位来源")
    void includesPositionRolesWithSources() {
        when(userRoleMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        when(userGroupMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userGroup(5L, REALM_ID, POSITION_ID, true),
                // 同一人在另一个部门任同一岗位:岗位要去重,否则后面的批量查询会重复命中
                userGroup(6L, REALM_ID, POSITION_ID, false)));
        when(positionRoleMapper.selectByPositionIds(List.of(POSITION_ID))).thenReturn(List.of(
                positionRole(POSITION_ROLE_ID, POSITION_ID, REALM_ID),
                positionRole(19L, POSITION_ID, 2L)));
        when(roleMapper.selectByIds(any())).thenReturn(List.of(
                role(POSITION_ROLE_ID, REALM_ID, true),
                role(19L, 2L, true)));

        List<IamEffectiveRoleResolver.RoleSource> sources = resolver.resolveWithSources(REALM_ID, USER_ID);

        assertEquals(1, sources.size());
        assertEquals(POSITION_ROLE_ID, sources.get(0).role().getId());
        // 岗位来源不是直授,诊断界面要能说清"这个角色来自任职"
        assertFalse(sources.get(0).direct());
        assertEquals(List.of(POSITION_ID), sources.get(0).positionIds());
        assertEquals(List.of(), sources.get(0).groupIds());
    }

    @Test
    @DisplayName("角色主键去重并按域过滤用户组")
    void resolvesDistinctRoleIdsWithinRealm() {
        when(userRoleMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userRole(DIRECT_ROLE_ID, REALM_ID),
                userRole(DIRECT_ROLE_ID, REALM_ID)));
        when(userGroupMapper.selectByUserId(USER_ID)).thenReturn(List.of(
                userGroup(5L, REALM_ID),
                userGroup(8L, 2L)));
        when(groupRoleMapper.selectByGroupIds(List.of(5L))).thenReturn(List.of(
                groupRole(GROUP_ROLE_ID, 5L, REALM_ID)));
        when(roleMapper.selectByIds(any())).thenReturn(List.of(
                role(DIRECT_ROLE_ID, REALM_ID, true),
                role(GROUP_ROLE_ID, REALM_ID, true)));

        assertEquals(List.of(5L), resolver.resolveGroupIds(REALM_ID, USER_ID));
        assertEquals(List.of(DIRECT_ROLE_ID, GROUP_ROLE_ID), resolver.resolveRoleIds(REALM_ID, USER_ID));
    }

    private static UserRole userRole(Long roleId, Long realmId) {
        UserRole relation = new UserRole();
        relation.setRealmId(realmId);
        relation.setUserId(USER_ID);
        relation.setRoleId(roleId);
        return relation;
    }

    private static UserGroup userGroup(Long groupId, Long realmId) {
        return userGroup(groupId, realmId, null, false);
    }

    private static UserGroup userGroup(Long groupId, Long realmId, Long positionId, boolean primary) {
        UserGroup relation = new UserGroup();
        relation.setRealmId(realmId);
        relation.setUserId(USER_ID);
        relation.setGroupId(groupId);
        relation.setPositionId(positionId);
        relation.setIsPrimary(primary);
        return relation;
    }

    private static PositionRole positionRole(Long roleId, Long positionId, Long realmId) {
        PositionRole relation = new PositionRole();
        relation.setRealmId(realmId);
        relation.setPositionId(positionId);
        relation.setRoleId(roleId);
        return relation;
    }

    private static GroupRole groupRole(Long roleId, Long groupId, Long realmId) {
        GroupRole relation = new GroupRole();
        relation.setRealmId(realmId);
        relation.setGroupId(groupId);
        relation.setRoleId(roleId);
        return relation;
    }

    private static Role role(Long id, Long realmId, boolean active) {
        Role role = new Role();
        role.setId(id);
        role.setRealmId(realmId);
        role.setRoleCode("role-" + id);
        role.setRoleName("角色 " + id);
        role.setIsActive(active);
        return role;
    }
}
