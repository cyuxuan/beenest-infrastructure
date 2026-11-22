package club.beenest.iam.authz.token;

import club.beenest.iam.authz.authorization.ApplicationVisibilityService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.session.IamClientSessionService;
import club.beenest.iam.authz.subject.IamSubjectResolver;
import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.PermissionProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import club.beenest.iam.persistence.entity.ApplicationService;
import club.beenest.iam.persistence.entity.Group;
import club.beenest.iam.persistence.entity.IdentityProvider;
import club.beenest.iam.persistence.entity.Position;
import club.beenest.iam.persistence.entity.UserFederatedIdentity;
import club.beenest.iam.persistence.entity.UserGroup;
import club.beenest.iam.persistence.manager.IamAppRoleMappingManager;
import club.beenest.iam.persistence.manager.IamApplicationServiceManager;
import club.beenest.iam.persistence.manager.IamGroupManager;
import club.beenest.iam.persistence.manager.IamPositionManager;
import club.beenest.iam.persistence.manager.IamProtocolMapperManager;
import club.beenest.iam.persistence.mapper.IdentityProviderMapper;
import club.beenest.iam.persistence.mapper.UserFederatedIdentityMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 令牌应用角色声明测试。
 * <p>app_roles 由授权模式决定:MAPPED 输出映射结果并并入直授的本应用角色,
 * IAM_MANAGED + SCOPED 只输出本应用角色;其余模式保持原令牌结构,
 * 避免存量应用收到意外声明。</p>
 */
class IamJwtTokenCustomizerTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 9L;
    private static final Long GROUP_ID = 5L;
    private static final Long ROLE_ID = 10L;
    private static final Long APP_ID = 7L;

    private final RoleProvider roleProvider = mock(RoleProvider.class);
    private final ClientProvider clientProvider = mock(ClientProvider.class);
    private final PermissionProvider permissionProvider = mock(PermissionProvider.class);
    private final IamGroupManager groupManager = mock(IamGroupManager.class);
    private final IamProtocolMapperManager protocolMapperManager = mock(IamProtocolMapperManager.class);
    private final IamPositionManager positionManager = mock(IamPositionManager.class);
    private final IamApplicationServiceManager appServiceManager = mock(IamApplicationServiceManager.class);
    private final IamAppRoleMappingManager appRoleMappingManager = mock(IamAppRoleMappingManager.class);
    private final IamClientSessionService clientSessionService = mock(IamClientSessionService.class);
    private final IamSubjectResolver subjectResolver = mock(IamSubjectResolver.class);
    private final UserProvider userProvider = mock(UserProvider.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);
    private final UserFederatedIdentityMapper federatedIdentityMapper = mock(UserFederatedIdentityMapper.class);
    private final IdentityProviderMapper identityProviderMapper = mock(IdentityProviderMapper.class);
    private final ApplicationVisibilityService visibilityService = mock(ApplicationVisibilityService.class);

    private final IamJwtTokenCustomizer customizer = new IamJwtTokenCustomizer(
            roleProvider, clientProvider, permissionProvider, groupManager, protocolMapperManager,
            positionManager, appServiceManager, appRoleMappingManager, clientSessionService,
            subjectResolver, userProvider, realmProvider, federatedIdentityMapper, identityProviderMapper,
            visibilityService);

    @Test
    @DisplayName("MAPPED + SCOPED 把直授的本应用角色并入 app_roles")
    void unionsDirectAppRolesIntoMappedScopedToken() {
        stubCommonClaims();
        when(roleProvider.findRolesByUserId(REALM_ID, USER_ID)).thenReturn(List.of(
                new RoleModel(ROLE_ID, REALM_ID, "PLATFORM_AUDITOR", "平台审计",
                        RoleModel.ContainerType.REALM, null, null, false),
                new RoleModel(15L, REALM_ID, "OA_ORDER_ADMIN", "OA 订单管理员",
                        RoleModel.ContainerType.APP, null, APP_ID, false),
                new RoleModel(16L, REALM_ID, "OTHER_APP_ADMIN", "其他应用管理员",
                        RoleModel.ContainerType.APP, null, 8L, false)));
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("MAPPED", "SCOPED"));
        when(appRoleMappingManager.resolveTargetRoleCodes(
                REALM_ID, APP_ID, List.of(GROUP_ID), List.of(ROLE_ID, 15L, 16L)))
                .thenReturn(List.of("REPORT_VIEWER"));
        when(permissionProvider.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID))
                .thenReturn(Set.of());

        JwtClaimsSet claims = customize("42");

        // 映射结果与直授角色去重排序;其他应用的 APP 角色不进入本应用声明
        assertEquals(List.of("OA_ORDER_ADMIN", "REPORT_VIEWER"), claims.getClaim("app_roles"));
    }

    @Test
    @DisplayName("MAPPED + LEGACY_GLOBAL 维持只输出映射结果")
    void keepsMappedRolesOnlyForLegacyProfile() {
        stubCommonClaims();
        when(roleProvider.findRolesByUserId(REALM_ID, USER_ID)).thenReturn(List.of(
                new RoleModel(15L, REALM_ID, "OA_ORDER_ADMIN", "OA 订单管理员",
                        RoleModel.ContainerType.APP, null, APP_ID, false)));
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("MAPPED"));
        when(appRoleMappingManager.resolveTargetRoleCodes(
                REALM_ID, APP_ID, List.of(GROUP_ID), List.of(15L)))
                .thenReturn(List.of("REPORT_VIEWER"));

        JwtClaimsSet claims = customize("42");

        assertEquals(List.of("REPORT_VIEWER"), claims.getClaim("app_roles"));
    }

    @Test
    @DisplayName("MAPPED 应用输出映射后的 app_roles")
    void emitsAppRolesForMappedApp() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("MAPPED"));
        when(appRoleMappingManager.resolveTargetRoleCodes(
                REALM_ID, APP_ID, List.of(GROUP_ID), List.of(ROLE_ID, 11L)))
                .thenReturn(List.of("EDITOR", "VIEWER"));

        JwtClaimsSet claims = customize("42");

        assertEquals(List.of("EDITOR", "VIEWER"), claims.getClaim("app_roles"));
        assertEquals("alice", claims.getClaim("username"));
    }

    @Test
    @DisplayName("IAM_MANAGED 应用不输出 app_roles,也不查询映射")
    void skipsAppRolesForIamManagedApp() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED"));

        JwtClaimsSet claims = customize("42");

        assertNull(claims.getClaim("app_roles"));
        verify(appRoleMappingManager, never()).resolveTargetRoleCodes(any(), any(), any(), any());
    }

    @Test
    @DisplayName("客户端未绑定应用时不输出 app_roles")
    void skipsAppRolesWhenClientUnbound() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(null);
        when(visibilityService.grantedApps(REALM_ID, USER_ID)).thenReturn(List.of());

        assertNull(customize("42").getClaim("app_roles"));
    }

    @Test
    @DisplayName("客户端主键非数字时跳过应用解析")
    void skipsNonNumericClientId() {
        stubCommonClaims();

        assertNull(customize("external-id").getClaim("app_roles"));
        verify(appServiceManager, never()).getBySsoClientPk(any(), any());
    }

    @Test
    @DisplayName("IAM_MANAGED + SCOPED 只输出本应用权限、当前客户端角色与应用角色")
    void scopesIamManagedTokenToApplication() {
        stubCommonClaims();
        when(roleProvider.findRolesByUserId(REALM_ID, USER_ID)).thenReturn(List.of(
                new RoleModel(ROLE_ID, REALM_ID, "PLATFORM_AUDITOR", "平台审计",
                        RoleModel.ContainerType.REALM, null, null, false),
                new RoleModel(11L, REALM_ID, "OA_ADMIN", "OA 管理员",
                        RoleModel.ContainerType.CLIENT, 42L, null, false),
                new RoleModel(12L, REALM_ID, "OTHER_CLIENT_ADMIN", "其他客户端管理员",
                        RoleModel.ContainerType.CLIENT, 99L, null, false),
                new RoleModel(13L, REALM_ID, "OA_APP_ADMIN", "OA 应用管理员",
                        RoleModel.ContainerType.APP, null, APP_ID, false),
                new RoleModel(14L, REALM_ID, "OTHER_APP_ADMIN", "其他应用管理员",
                        RoleModel.ContainerType.APP, null, 8L, false)));
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED", "SCOPED"));
        when(permissionProvider.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID))
                .thenReturn(Set.of("oa:view", "oa:export"));

        JwtClaimsSet claims = customize("42");

        assertEquals(List.of("oa:export", "oa:view"), claims.getClaim("permissions"));
        assertEquals(List.of("OA_ADMIN"), claims.getClaim("client_roles"));
        assertEquals(List.of("OA_APP_ADMIN"), claims.getClaim("app_roles"));
        assertEquals(List.of("PLATFORM_AUDITOR"), claims.getClaim("realm_roles"));
        assertEquals(List.of("finance"), claims.getClaim("groups"));
    }

    @Test
    @DisplayName("MAPPED + SCOPED 输出映射角色与当前应用权限")
    void emitsMappedRolesAndAppPermissionsForScopedApp() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("MAPPED", "SCOPED"));
        when(appRoleMappingManager.resolveTargetRoleCodes(
                REALM_ID, APP_ID, List.of(GROUP_ID), List.of(ROLE_ID, 11L)))
                .thenReturn(List.of("REPORT_VIEWER"));
        when(permissionProvider.computeAppPermissionCodes(REALM_ID, USER_ID, APP_ID))
                .thenReturn(Set.of("report:view"));

        JwtClaimsSet claims = customize("42");

        assertEquals(List.of("REPORT_VIEWER"), claims.getClaim("app_roles"));
        assertEquals(List.of("report:view"), claims.getClaim("permissions"));
        assertEquals(List.of("OA_ADMIN"), claims.getClaim("client_roles"));
    }

    @Test
    @DisplayName("APP_MANAGED + SCOPED 不输出权限与应用角色")
    void omitsAuthorizationClaimsForAppManagedApp() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("APP_MANAGED", "SCOPED"));

        JwtClaimsSet claims = customize("42");

        assertNull(claims.getClaim("permissions"));
        assertNull(claims.getClaim("app_roles"));
        assertEquals(List.of("OA_ADMIN"), claims.getClaim("client_roles"));
        assertEquals(List.of("PLATFORM_AUDITOR"), claims.getClaim("realm_roles"));
        verify(permissionProvider, never()).computeAppPermissionCodes(any(), any(), any());
    }

    @Test
    @DisplayName("未绑定应用的客户端只输出身份声明")
    void emitsIdentityOnlyForUnboundClient() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(null);
        when(visibilityService.grantedApps(REALM_ID, USER_ID)).thenReturn(List.of());

        JwtClaimsSet claims = customize("42");

        assertNull(claims.getClaim("permissions"));
        assertNull(claims.getClaim("realm_roles"));
        assertNull(claims.getClaim("client_roles"));
        assertNull(claims.getClaim("groups"));
        assertNull(claims.getClaim("app_roles"));
        assertEquals("alice", claims.getClaim("username"));
        verify(roleProvider, never()).findRolesByUserId(any(), any());
    }

    @Test
    @DisplayName("LEGACY_GLOBAL 保留全域扁平权限与角色,权限按库中现值计算")
    void keepsGlobalClaimsForLegacyApp() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED", "LEGACY_GLOBAL"));
        // 撤角色后旧刷新令牌换出的访问令牌不应再带原权限码:取库中现值而非登录快照
        when(permissionProvider.computePermissionCodes(REALM_ID, USER_ID))
                .thenReturn(Set.of("app-service:view"));

        JwtClaimsSet claims = customize("42");

        assertEquals(List.of("app-service:view"), claims.getClaim("permissions"));
        assertEquals(List.of("OA_ADMIN"), claims.getClaim("client_roles"));
        assertNull(claims.getClaim("app_roles"));
    }

    @Test
    @DisplayName("令牌输出主部门与任职岗位")
    void emitsPrimaryDepartmentAndPositions() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED", "SCOPED"));
        // 两条任职:财务部是兼职且不任职岗位,研发中心是主部门并任部门经理
        when(groupManager.listRelationsByUserId(USER_ID)).thenReturn(List.of(
                relation(GROUP_ID, null, false),
                relation(6L, 21L, true)));
        Group dept = new Group();
        dept.setId(6L);
        dept.setRealmId(REALM_ID);
        dept.setGroupCode("rd-center");
        dept.setGroupName("研发中心");
        when(groupManager.getById(6L)).thenReturn(dept);
        when(positionManager.listByIds(List.of(21L))).thenReturn(List.of(position(21L, REALM_ID, "dept-manager")));

        JwtClaimsSet claims = customize("42");

        // 主部门只有一个:兼职部门留在 groups 里,不进 dept,否则"属于哪个部门"就没有唯一答案
        assertEquals("rd-center", claims.getClaim("dept"));
        assertEquals("研发中心", claims.getClaim("dept_name"));
        assertEquals(List.of("dept-manager"), claims.getClaim("positions"));
    }

    @Test
    @DisplayName("跨域部门与岗位不进入令牌")
    void skipsCrossRealmOrganization() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED", "SCOPED"));
        when(groupManager.listRelationsByUserId(USER_ID)).thenReturn(List.of(relation(6L, 21L, true)));
        Group foreign = new Group();
        foreign.setId(6L);
        foreign.setRealmId(2L);
        foreign.setGroupCode("other-realm");
        foreign.setGroupName("别的域");
        when(groupManager.getById(6L)).thenReturn(foreign);
        when(positionManager.listByIds(List.of(21L))).thenReturn(List.of(position(21L, 2L, "foreign")));

        JwtClaimsSet claims = customize("42");

        assertNull(claims.getClaim("dept"));
        assertNull(claims.getClaim("dept_name"));
        assertNull(claims.getClaim("positions"));
    }

    @Test
    @DisplayName("令牌携带手机号与渠道绑定,供应用归并多端账号")
    void emitsPhoneAndLinkedIdentities() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED", "SCOPED"));
        UserFederatedIdentity link = new UserFederatedIdentity();
        link.setRealmId(REALM_ID);
        link.setUserId(USER_ID);
        link.setIdpId(7L);
        link.setExternalId("o-123");
        when(federatedIdentityMapper.selectByUserId(USER_ID)).thenReturn(List.of(link));
        IdentityProvider idp = new IdentityProvider();
        idp.setId(7L);
        idp.setRealmId(REALM_ID);
        idp.setProviderId("wechat-mini");
        when(identityProviderMapper.selectByRealmId(REALM_ID)).thenReturn(List.of(idp));

        JwtClaimsSet claims = customize("42");

        assertEquals("15000000000", claims.getClaim("phone_number"));
        assertEquals("alice@corp.example.com", claims.getClaim("email"));
        assertEquals(Map.of("wechat-mini", "o-123"), claims.getClaim("idp_identities"));
    }

    @Test
    @DisplayName("访问令牌受众改绑应用登记的受众")
    void overridesAudienceWithConfiguredApplicationValue() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L))
                .thenReturn(app("IAM_MANAGED", "SCOPED", "https://drone.example.com/api"));

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("https://drone.example.com/api"), claims.getAudience());
    }

    @Test
    @DisplayName("应用登记多个受众时全部写入 aud")
    void writesAllConfiguredAudiences() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L))
                .thenReturn(app("IAM_MANAGED", "SCOPED",
                        "https://a.example.com/api, https://b.example.com/api"));

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("https://a.example.com/api", "https://b.example.com/api"), claims.getAudience());
    }

    @Test
    @DisplayName("受众串只有分隔符时回落到按应用编码派生")
    void fallsBackToDerivedAudienceWhenConfiguredValueIsBlank() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L))
                .thenReturn(app("IAM_MANAGED", "SCOPED", " , "));

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("urn:iam:app:oa"), claims.getAudience());
    }

    @Test
    @DisplayName("应用未登记受众时按应用编码派生")
    void derivesAudienceFromAppCodeWhenNotConfigured() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(app("IAM_MANAGED", "SCOPED"));

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("urn:iam:app:oa"), claims.getAudience());
    }

    @Test
    @DisplayName("客户端未绑定应用时保留客户端 id 作为受众")
    void keepsClientIdAudienceWhenClientUnbound() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(null);
        when(visibilityService.grantedApps(REALM_ID, USER_ID)).thenReturn(List.of());

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("oa"), claims.getAudience());
    }

    @Test
    @DisplayName("ID Token 保留客户端 id 作为受众")
    void keepsClientIdAudienceForIdToken() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L))
                .thenReturn(app("IAM_MANAGED", "SCOPED", "https://drone.example.com/api"));

        JwtClaimsSet claims = customize("42", new OAuth2TokenType(OidcParameterNames.ID_TOKEN));

        assertEquals(List.of("oa"), claims.getAudience());
        verify(appServiceManager, never()).getBySsoClientPk(any(), any());
    }

    @Test
    @DisplayName("客户端未绑定但用户恰好被授权一个应用时,自动使用该应用受众")
    void fallsBackToGrantedAppWhenClientUnbound() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(null);
        ApplicationService grantedApp = app("MAPPED", "SCOPED", "urn:iam:app:drone");
        when(visibilityService.grantedApps(REALM_ID, USER_ID)).thenReturn(List.of(grantedApp));

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("urn:iam:app:drone"), claims.getAudience());
        assertEquals("APPLICATION", claims.getClaim("token_kind"));
    }

    @Test
    @DisplayName("客户端未绑定且用户被授权多个应用时,回退到平台令牌")
    void fallsBackToPlatformWhenMultipleAppsGranted() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(null);
        when(visibilityService.grantedApps(REALM_ID, USER_ID))
                .thenReturn(List.of(app("MAPPED", "SCOPED"), app("MAPPED", "SCOPED")));

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("oa"), claims.getAudience());
        assertEquals("PLATFORM", claims.getClaim("token_kind"));
    }

    @Test
    @DisplayName("客户端未绑定且用户无授权应用时,回退到平台令牌")
    void fallsBackToPlatformWhenNoAppsGranted() {
        stubCommonClaims();
        when(appServiceManager.getBySsoClientPk(REALM_ID, 42L)).thenReturn(null);
        when(visibilityService.grantedApps(REALM_ID, USER_ID)).thenReturn(List.of());

        JwtClaimsSet claims = customize("42", OAuth2TokenType.ACCESS_TOKEN);

        assertEquals(List.of("oa"), claims.getAudience());
        assertEquals("PLATFORM", claims.getClaim("token_kind"));
    }

    private void stubCommonClaims() {
        when(roleProvider.findRolesByUserId(REALM_ID, USER_ID)).thenReturn(List.of(
                new RoleModel(ROLE_ID, REALM_ID, "PLATFORM_AUDITOR", "平台审计",
                        RoleModel.ContainerType.REALM, null, null, false),
                new RoleModel(11L, REALM_ID, "OA_ADMIN", "OA 管理员",
                        RoleModel.ContainerType.CLIENT, 42L, null, false)));
        when(groupManager.listGroupIdsByUser(USER_ID)).thenReturn(List.of(GROUP_ID));
        when(groupManager.listByRealm(REALM_ID)).thenReturn(List.of(group()));
        when(protocolMapperManager.listEffective(REALM_ID, null)).thenReturn(List.of());
        when(subjectResolver.resolve(any(IamUserPrincipal.class), eq("oa"))).thenReturn("9");
        when(userProvider.findById(USER_ID)).thenReturn(Optional.of(new UserModel(
                USER_ID, REALM_ID, "alice", "Alice", "alice@corp.example.com", "15000000000",
                1, "INTERNAL", true, 3, Map.of())));
        when(federatedIdentityMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        when(identityProviderMapper.selectByRealmId(REALM_ID)).thenReturn(List.of());
    }

    private JwtClaimsSet customize(String clientDbId) {
        return customize(clientDbId, OAuth2TokenType.ACCESS_TOKEN);
    }

    private JwtClaimsSet customize(String clientDbId, OAuth2TokenType tokenType) {
        IamUserPrincipal user = new IamUserPrincipal(USER_ID, REALM_ID, "default", "alice", "Alice",
                3, List.of("app-service:view"), Map.of("dept", "研发部"));
        Authentication authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
        OAuth2Authorization authorization = mock(OAuth2Authorization.class);
        when(authorization.getAttribute(Principal.class.getName())).thenReturn(authentication);
        RegisteredClient client = RegisteredClient.withId(clientDbId)
                .clientId("oa")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://oa.example.com/callback")
                .build();
        JwtEncodingContext context = JwtEncodingContext.with(
                        JwsHeader.with(MacAlgorithm.HS256),
                        JwtClaimsSet.builder().issuer("https://iam.example.com")
                                // 与 SAS JwtGenerator 同序:进入定制器前 aud 已按客户端 id 落好
                                .audience(List.of("oa")))
                .registeredClient(client)
                .authorization(authorization)
                .tokenType(tokenType)
                .build();
        customizer.customize(context);
        return context.getClaims().build();
    }

    private static ApplicationService app(String authorizationMode) {
        return app(authorizationMode, "LEGACY_GLOBAL");
    }

    private static ApplicationService app(String authorizationMode, String tokenProfile) {
        return app(authorizationMode, tokenProfile, null);
    }

    private static ApplicationService app(String authorizationMode, String tokenProfile, String audience) {
        ApplicationService app = new ApplicationService();
        app.setId(APP_ID);
        app.setRealmId(REALM_ID);
        app.setAppCode("oa");
        app.setAudience(audience);
        app.setAppName("协同办公");
        app.setAuthorizationMode(authorizationMode);
        app.setTokenProfile(tokenProfile);
        return app;
    }

    private static UserGroup relation(Long groupId, Long positionId, boolean primary) {
        UserGroup relation = new UserGroup();
        relation.setRealmId(REALM_ID);
        relation.setUserId(USER_ID);
        relation.setGroupId(groupId);
        relation.setPositionId(positionId);
        relation.setIsPrimary(primary);
        return relation;
    }

    private static Position position(Long id, Long realmId, String code) {
        Position position = new Position();
        position.setId(id);
        position.setRealmId(realmId);
        position.setPositionCode(code);
        position.setPositionName("岗位 " + code);
        return position;
    }

    private static Group group() {
        Group group = new Group();
        group.setId(GROUP_ID);
        group.setRealmId(REALM_ID);
        group.setGroupCode("finance");
        group.setGroupName("财务部");
        return group;
    }
}
