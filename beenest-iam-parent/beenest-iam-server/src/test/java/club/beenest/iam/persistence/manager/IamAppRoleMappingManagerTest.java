package club.beenest.iam.persistence.manager;

import club.beenest.iam.persistence.entity.AppRoleMapping;
import club.beenest.iam.persistence.mapper.AppRoleMappingMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 应用角色映射解析测试。
 * <p>令牌只应输出用户命中的启用映射:组与域角色均可命中,
 * 跨域映射与非成员来源必须忽略,重复目标角色去重且顺序稳定。</p>
 */
class IamAppRoleMappingManagerTest {

    private static final Long REALM_ID = 1L;
    private static final Long APP_ID = 7L;

    private final AppRoleMappingMapper mappingMapper = mock(AppRoleMappingMapper.class);
    private final IamAppRoleMappingManager manager = new IamAppRoleMappingManager(mappingMapper);

    @Test
    @DisplayName("组与域角色命中后输出目标角色码,去重并排序")
    void resolvesMappedRolesForGroupsAndRealmRoles() {
        when(mappingMapper.selectEnabledByAppServiceId(APP_ID)).thenReturn(List.of(
                mapping(REALM_ID, "GROUP", 5L, "VIEWER"),
                mapping(REALM_ID, "GROUP", 5L, "VIEWER"),
                mapping(REALM_ID, "REALM_ROLE", 10L, "EDITOR"),
                mapping(REALM_ID, "GROUP", 99L, "AUDITOR")));

        List<String> roles = manager.resolveTargetRoleCodes(REALM_ID, APP_ID, List.of(5L), List.of(10L));

        assertEquals(List.of("EDITOR", "VIEWER"), roles, "未命中的来源不能出现在令牌中");
    }

    @Test
    @DisplayName("跨域映射不参与计算")
    void ignoresMappingsFromOtherRealm() {
        when(mappingMapper.selectEnabledByAppServiceId(APP_ID)).thenReturn(List.of(
                mapping(2L, "GROUP", 5L, "CROSS_REALM")));

        List<String> roles = manager.resolveTargetRoleCodes(REALM_ID, APP_ID, List.of(5L), List.of());

        assertTrue(roles.isEmpty());
    }

    @Test
    @DisplayName("无有效组或角色时返回空集合")
    void returnsEmptyWhenNoSourceMatches() {
        when(mappingMapper.selectEnabledByAppServiceId(APP_ID)).thenReturn(List.of(
                mapping(REALM_ID, "GROUP", 5L, "VIEWER")));

        assertTrue(manager.resolveTargetRoleCodes(REALM_ID, APP_ID, List.of(), List.of()).isEmpty());
        assertTrue(manager.resolveTargetRoleCodes(REALM_ID, APP_ID, null, null).isEmpty());
    }

    @Test
    @DisplayName("未知来源类型被忽略")
    void ignoresUnknownSourceType() {
        when(mappingMapper.selectEnabledByAppServiceId(APP_ID)).thenReturn(List.of(
                mapping(REALM_ID, "CLIENT", 5L, "IGNORED")));

        assertTrue(manager.resolveTargetRoleCodes(REALM_ID, APP_ID, List.of(5L), List.of(5L)).isEmpty());
    }

    private static AppRoleMapping mapping(Long realmId, String sourceType, Long sourceId, String targetRoleCode) {
        AppRoleMapping mapping = new AppRoleMapping();
        mapping.setRealmId(realmId);
        mapping.setAppServiceId(APP_ID);
        mapping.setSourceType(sourceType);
        mapping.setSourceId(sourceId);
        mapping.setTargetRoleCode(targetRoleCode);
        mapping.setEnabled(true);
        return mapping;
    }
}
