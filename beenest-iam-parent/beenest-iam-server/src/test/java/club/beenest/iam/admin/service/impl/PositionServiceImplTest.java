package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.RoleDtoConverter;
import club.beenest.iam.admin.dto.PositionCreateRequest;
import club.beenest.iam.admin.dto.PositionRolesRequest;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamRoleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 岗位服务测试。
 * <p>岗位是角色到人的批量指派通道:一旦能把别域的角色挂到本域岗位上,
 * 权限就越过域边界了。这里锁定编码唯一、域归属与跨域角色的拦截。</p>
 */
class PositionServiceImplTest {

    private static final Long REALM_ID = 1L;
    private static final Long OPERATOR_ID = 2L;
    private static final Long POSITION_ID = 30L;
    private static final Long ROLE_ID = 40L;

    private final IamPositionManager positionManager = mock(IamPositionManager.class);
    private final IamRoleManager roleManager = mock(IamRoleManager.class);
    private final RoleDtoConverter roleDtoConverter = mock(RoleDtoConverter.class);

    private final PositionServiceImpl service = new PositionServiceImpl(
            positionManager, roleManager, roleDtoConverter);

    @BeforeEach
    void setUp() {
        IamUserPrincipal operator = new IamUserPrincipal(
                OPERATOR_ID, REALM_ID, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("岗位编码域内重复时拒绝创建")
    void rejectsDuplicateCode() {
        PositionCreateRequest request = new PositionCreateRequest("dept-manager", "部门经理", "M2", 1);
        when(positionManager.getByCode(REALM_ID, "dept-manager")).thenReturn(position(POSITION_ID, REALM_ID));

        BusinessException thrown = assertThrows(BusinessException.class, () -> service.create(request));
        assertEquals("岗位编码已存在: dept-manager", thrown.getMessage());
    }

    @Test
    @DisplayName("跨域岗位不可见")
    void rejectsPositionFromAnotherRealm() {
        when(positionManager.getById(POSITION_ID)).thenReturn(position(POSITION_ID, 2L));

        assertThrows(BusinessException.class, () -> service.get(POSITION_ID));
    }

    @Test
    @DisplayName("岗位不得挂载别域的角色")
    void rejectsCrossRealmRole() {
        when(positionManager.getById(POSITION_ID)).thenReturn(position(POSITION_ID, REALM_ID));
        when(roleManager.getById(ROLE_ID)).thenReturn(role(ROLE_ID, 2L));

        assertThrows(BusinessException.class,
                () -> service.assignRoles(POSITION_ID, new PositionRolesRequest(List.of(ROLE_ID))));
    }

    private static Position position(Long id, Long realmId) {
        Position position = new Position();
        position.setId(id);
        position.setRealmId(realmId);
        position.setPositionCode("dept-manager");
        position.setPositionName("部门经理");
        return position;
    }

    private static Role role(Long id, Long realmId) {
        Role role = new Role();
        role.setId(id);
        role.setRealmId(realmId);
        role.setRoleCode("role-" + id);
        role.setRoleName("角色 " + id);
        return role;
    }
}
