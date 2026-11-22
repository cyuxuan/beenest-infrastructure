package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Role;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamEffectiveRoleResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 应用可见性判定测试。
 * <p>门户列表与授权端点共用这套规则:三条规则(全员、权限码、授权记录)的判定
 * 与停用、跨域的否决必须一致,否则会出现"门户看不到、协议端点却能进"的缺口。</p>
 */
class ApplicationVisibilityServiceTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 2L;
    private static final Long APP_ID = 10L;
    private static final Long ROLE_ID = 20L;
    private static final Long GROUP_ID = 30L;

    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamEffectiveRoleResolver effectiveRoleResolver = mock(IamEffectiveRoleResolver.class);
    private final ApplicationVisibilityService service =
            new ApplicationVisibilityService(appServiceManager, effectiveRoleResolver);

    @Test
    @DisplayName("全员可见且启用的应用放行")
    void allowsPublicEnabledApp() {
        assertThat(service.canAccess(principal(List.of()), app("PUBLIC", null))).isTrue();
    }

    @Test
    @DisplayName("停用的应用一律拒绝")
    void rejectsDisabledApp() {
        ApplicationService app = app("PUBLIC", null);
        app.setEnabled(Boolean.FALSE);
        assertThat(service.canAccess(principal(List.of()), app)).isFalse();
    }

    @Test
    @DisplayName("跨域应用拒绝:授权端点按域解析,不能拿本域身份访问别域应用")
    void rejectsAppOfAnotherRealm() {
        ApplicationService app = app("PUBLIC", null);
        app.setRealmId(REALM_ID + 1);
        assertThat(service.canAccess(principal(List.of()), app)).isFalse();
    }

    @Test
    @DisplayName("按权限码可见:权限集命中的放行")
    void allowsAppWhenAuthorityPresent() {
        assertThat(service.canAccess(principal(List.of("app:erp:use")), app("PERMISSION", "app:erp:use")))
                .isTrue();
    }

    @Test
    @DisplayName("按权限码可见:权限集未命中拒绝")
    void rejectsAppWhenAuthorityMissing() {
        assertThat(service.canAccess(principal(List.of("app:crm:use")), app("PERMISSION", "app:erp:use")))
                .isFalse();
    }

    @Test
    @DisplayName("按权限码可见但未配置权限码时拒绝:不完整的配置不能静默放开")
    void rejectsPermissionAppWithoutCode() {
        assertThat(service.canAccess(principal(List.of()), app("PERMISSION", null))).isFalse();
    }

    @Test
    @DisplayName("按角色可见:有效角色被授权时放行")
    void allowsAppGrantedToRole() {
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(realmRole()));
        when(appServiceManager.listGrantedAppServiceIds("ROLE", List.of(ROLE_ID))).thenReturn(Set.of(APP_ID));

        assertThat(service.canAccess(principal(List.of()), app("ROLE", null))).isTrue();
    }

    @Test
    @DisplayName("持有应用角色即可进入该应用:角色本身就是应用级授权")
    void allowsAppWhenHoldingItsAppRole() {
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(appRole(APP_ID)));
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());

        assertThat(service.canAccess(principal(List.of()), app("ROLE", null))).isTrue();
    }

    @Test
    @DisplayName("应用角色只对所属应用生效,不影响其他应用")
    void appRoleDoesNotUnlockOtherApps() {
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());
        ApplicationService other = app("ROLE", null);
        other.setId(APP_ID + 1);
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(appRole(APP_ID)));
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());

        assertThat(service.canAccess(principal(List.of()), other)).isFalse();
        assertThat(service.canAccessByUserId(REALM_ID, USER_ID, other)).isFalse();
    }

    @Test
    @DisplayName("应用角色进入令牌受众回退:授权应用列表包含角色所属应用")
    void appRoleContributesToGrantedApps() {
        ApplicationService owned = app("ROLE", null);
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(appRole(APP_ID)));
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());
        when(appServiceManager.listByIds(List.of(APP_ID))).thenReturn(List.of(owned));

        assertThat(service.grantedApps(REALM_ID, USER_ID)).containsExactly(owned);
    }

    @Test
    @DisplayName("按角色可见:没有任何授权记录时拒绝")
    void rejectsAppWithoutGrant() {
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(realmRole()));
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());

        assertThat(service.canAccess(principal(List.of()), app("ROLE", null))).isFalse();
    }

    @Test
    @DisplayName("用户直授、角色继承与组继承的授权取并集")
    void mergesGrantSources() {
        when(appServiceManager.listGrantedAppServiceIds("USER", List.of(USER_ID))).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(realmRole()));
        when(appServiceManager.listGrantedAppServiceIds("ROLE", List.of(ROLE_ID))).thenReturn(Set.of());
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of(GROUP_ID));
        when(appServiceManager.listGrantedAppServiceIds("GROUP", List.of(GROUP_ID))).thenReturn(Set.of(APP_ID));

        assertThat(service.canAccess(principal(List.of()), app("ROLE", null))).isTrue();
    }

    @Test
    @DisplayName("可见应用列表只保留启用且可见的条目")
    void listsOnlyVisibleEnabledApps() {
        ApplicationService visible = app("PUBLIC", null);
        ApplicationService disabled = app("PUBLIC", null);
        disabled.setId(APP_ID + 1);
        disabled.setEnabled(Boolean.FALSE);
        ApplicationService hidden = app("PERMISSION", "app:erp:use");
        hidden.setId(APP_ID + 2);
        when(appServiceManager.listByRealmId(REALM_ID)).thenReturn(List.of(visible, disabled, hidden));
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());

        assertThat(service.visibleApps(principal(List.of()))).containsExactly(visible);
    }

    @Test
    @DisplayName("客户端主键解析失败时按未绑定处理,不阻断授权流程")
    void treatsUnparsableClientIdAsUnbound() {
        RegisteredClient client = mock(RegisteredClient.class);
        when(client.getId()).thenReturn("not-a-number");

        assertThat(service.boundApplication(REALM_ID, client)).isNull();
    }

    @Test
    @DisplayName("按客户端主键查绑定应用")
    void resolvesBoundApplication() {
        RegisteredClient client = mock(RegisteredClient.class);
        when(client.getId()).thenReturn("77");
        ApplicationService bound = app("PUBLIC", null);
        when(appServiceManager.getBySsoClientPk(REALM_ID, 77L)).thenReturn(bound);

        assertThat(service.boundApplication(REALM_ID, client)).isSameAs(bound);
        assertThat(service.boundApplication(null, client)).isNull();
    }

    @Test
    @DisplayName("按用户重算:直授移除后仍可通过角色进入时放行")
    void keepsAccessByUserIdWhenRoleGrantRemains() {
        when(appServiceManager.listGrantedAppServiceIds("USER", List.of(USER_ID))).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(realmRole()));
        when(appServiceManager.listGrantedAppServiceIds("ROLE", List.of(ROLE_ID))).thenReturn(Set.of(APP_ID));

        assertThat(service.canAccessByUserId(REALM_ID, USER_ID, app("ROLE", null))).isTrue();
    }

    @Test
    @DisplayName("按用户重算:没有任何授权时拒绝,刷新令牌不能续期")
    void rejectsByUserIdWhenGrantRemoved() {
        when(appServiceManager.listGrantedAppServiceIds("USER", List.of(USER_ID))).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of());
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());

        assertThat(service.canAccessByUserId(REALM_ID, USER_ID, app("ROLE", null))).isFalse();
    }

    @Test
    @DisplayName("按用户重算不改变全员可见与按权限码可见的结论")
    void keepsPublicAndPermissionVisibilityByUserId() {
        assertThat(service.canAccessByUserId(REALM_ID, USER_ID, app("PUBLIC", null))).isTrue();
        assertThat(service.canAccessByUserId(REALM_ID, USER_ID, app("PERMISSION", "app:erp:use"))).isTrue();
    }

    @Test
    @DisplayName("授权应用列表只返回经直授/角色/组获得的应用")
    void listsGrantedAppsOnly() {
        ApplicationService granted = app("ROLE", null);
        when(appServiceManager.listGrantedAppServiceIds("USER", List.of(USER_ID))).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of(realmRole()));
        when(appServiceManager.listGrantedAppServiceIds("ROLE", List.of(ROLE_ID))).thenReturn(Set.of(APP_ID));
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());
        when(appServiceManager.listByIds(List.of(APP_ID))).thenReturn(List.of(granted));

        assertThat(service.grantedApps(REALM_ID, USER_ID)).containsExactly(granted);
    }

    @Test
    @DisplayName("授权应用列表过滤停用应用")
    void filtersDisabledGrantedApps() {
        ApplicationService disabled = app("ROLE", null);
        disabled.setEnabled(Boolean.FALSE);
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of(APP_ID));
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of());
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());
        when(appServiceManager.listByIds(List.of(APP_ID))).thenReturn(List.of(disabled));

        assertThat(service.grantedApps(REALM_ID, USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("无授权记录时返回空列表")
    void returnsEmptyWhenNoGrants() {
        when(appServiceManager.listGrantedAppServiceIds(anyString(), anyList())).thenReturn(Set.of());
        when(effectiveRoleResolver.resolve(REALM_ID, USER_ID)).thenReturn(List.of());
        when(effectiveRoleResolver.resolveGroupIds(REALM_ID, USER_ID)).thenReturn(List.of());

        assertThat(service.grantedApps(REALM_ID, USER_ID)).isEmpty();
    }

    /** 构造应用服务条目。 */
    private static ApplicationService app(String visibility, String requiredPermissionCode) {
        ApplicationService app = new ApplicationService();
        app.setId(APP_ID);
        app.setRealmId(REALM_ID);
        app.setAppCode("erp");
        app.setVisibility(visibility);
        app.setRequiredPermissionCode(requiredPermissionCode);
        app.setEnabled(Boolean.TRUE);
        return app;
    }

    /** 构造登录主体。 */
    private static IamUserPrincipal principal(List<String> authorities) {
        return new IamUserPrincipal(USER_ID, REALM_ID, "default", "zhang", "张三", 0,
                authorities, Map.of(), 1L, "PASSWORD");
    }

    /** 构造域级角色(不挂应用)。 */
    private static Role realmRole() {
        return role(null);
    }

    /** 构造应用角色,携带所属应用主键。 */
    private static Role appRole(Long appServiceId) {
        return role(appServiceId);
    }

    /** 构造有效角色实体。 */
    private static Role role(Long appServiceId) {
        Role role = new Role();
        role.setId(ROLE_ID);
        role.setRealmId(REALM_ID);
        role.setAppServiceId(appServiceId);
        role.setContainerType(appServiceId == null ? "REALM" : "APP");
        return role;
    }
}
