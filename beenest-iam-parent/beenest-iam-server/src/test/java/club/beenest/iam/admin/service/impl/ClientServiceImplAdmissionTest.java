package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ClientDtoConverter;
import club.beenest.iam.admin.dto.ClientCreateRequest;
import club.beenest.iam.admin.dto.ClientUpdateRequest;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.converter.ClientConverter;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.User;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamClientScopeManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户端准入策略的保存校验测试。
 * <p>白名单与域级可用方式无交集时必须拒绝保存:
 * 这种配置在运行期表现为"永远登不进去",但配置期完全看不出来。
 * 与其留到客户接入时才发现,不如在保存时挡下。</p>
 */
class ClientServiceImplAdmissionTest {

    private static final Long REALM_ID = 1L;

    private final IamClientManager clientManager = mock(IamClientManager.class);
    private final IamClientScopeManager clientScopeManager = mock(IamClientScopeManager.class);
    private final ClientConverter clientConverter = new ClientConverter();
    private final ClientDtoConverter dtoConverter = new ClientDtoConverter(clientConverter);
    private final LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
    private final IamUserManager userManager = mock(IamUserManager.class);

    private ClientServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClientServiceImpl(clientManager, clientScopeManager, dtoConverter, clientConverter,
                userManager, catalog);
        IamUserPrincipal operator = new IamUserPrincipal(
                2L, REALM_ID, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
        when(catalog.availableInRealm(REALM_ID, "default")).thenReturn(Set.of(LoginMethod.PASSWORD));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("白名单与域级可用方式无交集时拒绝创建")
    void rejectsWhitelistWithNoAvailableMethod() {
        ClientCreateRequest request = createRequest(List.of("SMS"));

        BusinessException thrown = assertThrows(BusinessException.class, () -> service.create(request));
        assertTrue(thrown.getMessage().contains("本域"));
    }

    @Test
    @DisplayName("白名单只有部分方式可用时放行")
    void allowsWhitelistWithAtLeastOneAvailableMethod() {
        // 运营常先配好客户端白名单、后开通道。只要交集非空,这个客户端眼下就有路可走,
        // 逐项拒绝会把这条合法的配置顺序堵死
        ClientCreateRequest request = createRequest(List.of("PASSWORD", "WECHAT"));

        assertDoesNotThrow(() -> service.create(request));
    }

    @Test
    @DisplayName("空白元素视为未配置,不当成未知登录方式")
    void ignoresNullAndBlankEntries() {
        // 写侧归一化本来就会丢弃空值,校验侧若报"未知的登录方式: null"就与入库口径打架
        ClientCreateRequest request = createRequest(Arrays.asList(null, " ", "PASSWORD"));

        assertDoesNotThrow(() -> service.create(request));
    }

    @Test
    @DisplayName("全是空白元素时等同未配白名单")
    void treatsAllBlankWhitelistAsUnrestricted() {
        ClientCreateRequest request = createRequest(Arrays.asList(null, " "));

        assertDoesNotThrow(() -> service.create(request));
    }

    @Test
    @DisplayName("白名单含未知码时拒绝创建")
    void rejectsUnknownLoginType() {
        // 词表之外的码绝不能静默入库:入库后它永远匹配不上任何登录方式,
        // 表现为"这个客户端就是登不进去",而配置页看起来一切正常
        ClientCreateRequest request = createRequest(List.of("TOTP"));

        BusinessException thrown = assertThrows(BusinessException.class, () -> service.create(request));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("未知的登录方式"));
    }

    @Test
    @DisplayName("更新时白名单与域级可用方式无交集同样拒绝")
    void updateRejectsWhitelistWithNoAvailableMethod() {
        when(clientManager.getById(10L)).thenReturn(client(10L));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.update(10L, updateRequest(List.of("SMS"))));
        assertTrue(thrown.getMessage().contains("本域"));
    }

    @Test
    @DisplayName("更新传空数组时白名单写成空串,不是 null")
    void updateClearsWhitelistWithEmptyArray() {
        // 空串才能覆盖掉库里的旧值;写 null 会被 mapper 的 <if test="... != null"> 跳过,
        // 运营清空白名单后旧限制仍会生效
        when(clientManager.getById(10L)).thenReturn(client(10L));

        service.update(10L, updateRequest(List.of()));

        ArgumentCaptor<Client> captor = ArgumentCaptor.forClass(Client.class);
        verify(clientManager).update(captor.capture());
        assertEquals("", captor.getValue().getAllowedLoginTypes());
    }

    @Test
    @DisplayName("更新时把服务账号指向他域用户被拒绝")
    void updateRejectsCrossRealmServiceAccount() {
        // 创建侧挡住了,更新侧若不挡:持有 client:update 的人可以把服务账号改成
        // 任一高权用户,再用 client_credentials 换到该用户权限全集的令牌
        when(clientManager.getById(10L)).thenReturn(client(10L));
        when(userManager.getById(99L)).thenReturn(user(99L, 2L));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.update(10L, updateRequestWithCredentials(
                        List.of("client_credentials"), 99L)));
        assertTrue(thrown.getMessage().contains("不属于当前身份域"));
    }

    @Test
    @DisplayName("更新时给公共客户端开凭据模式被拒绝")
    void updateRejectsClientCredentialsOnPublicClient() {
        when(clientManager.getById(10L)).thenReturn(client(10L, "PUBLIC"));
        when(userManager.getById(99L)).thenReturn(user(99L, REALM_ID));

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> service.update(10L, updateRequestWithCredentials(
                        List.of("client_credentials"), 99L)));
        assertTrue(thrown.getMessage().contains("仅支持机密客户端"));
    }

    @Test
    @DisplayName("更新时机密客户端绑定同域服务账号放行")
    void updateAllowsConfidentialServiceAccount() {
        when(clientManager.getById(10L)).thenReturn(client(10L));
        when(userManager.getById(99L)).thenReturn(user(99L, REALM_ID));

        assertDoesNotThrow(() -> service.update(10L, updateRequestWithCredentials(
                List.of("client_credentials"), 99L)));
    }

    /**
     * 构造新建请求。
     * <p>{@link ClientCreateRequest} 加两个准入分量后的分量顺序(共 18 个):
     * clientKey, clientName, clientType, redirectUris, postLogoutRedirectUris,
     * backchannelLogoutUri, reuseRefreshTokens, subjectMode, scopes, requirePkce,
     * requireConsent, accessTokenTtl, refreshTokenTtl, description,
     * allowedLoginTypes, requireMfa, grantTypes, serviceAccountUserId</p>
     */
    private static ClientCreateRequest createRequest(List<String> allowedLoginTypes) {
        return new ClientCreateRequest(
                "app", "应用", "CONFIDENTIAL", List.of("https://app.example.com/cb"),
                List.of(), null, null, null, List.of("openid"), null, null, null,
                null, null, allowedLoginTypes, null, null, null);
    }

    /**
     * 构造更新请求,只填准入白名单,其余项为 null 表示不更新。
     * <p>{@link ClientUpdateRequest} 的分量顺序(共 17 个):
     * clientName, redirectUris, postLogoutRedirectUris, backchannelLogoutUri,
     * reuseRefreshTokens, subjectMode, scopes, requirePkce, requireConsent,
     * accessTokenTtl, refreshTokenTtl, enabled, description,
     * allowedLoginTypes, requireMfa, grantTypes, serviceAccountUserId</p>
     */
    private static ClientUpdateRequest updateRequest(List<String> allowedLoginTypes) {
        return new ClientUpdateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                allowedLoginTypes, null, null, null);
    }

    /** 构造只带凭据模式相关字段的更新请求。 */
    private static ClientUpdateRequest updateRequestWithCredentials(List<String> grantTypes,
                                                                   Long serviceAccountUserId) {
        return new ClientUpdateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, grantTypes, serviceAccountUserId);
    }

    /** 当前域内的存量客户端。 */
    private static Client client(Long id) {
        return client(id, "CONFIDENTIAL");
    }

    /** 指定类型的存量客户端。 */
    private static Client client(Long id, String clientType) {
        Client client = new Client();
        client.setId(id);
        client.setRealmId(REALM_ID);
        client.setClientType(clientType);
        client.setAllowedLoginTypes("");
        return client;
    }

    /** 指定域的用户。 */
    private static User user(Long id, Long realmId) {
        User user = new User();
        user.setId(id);
        user.setRealmId(realmId);
        user.setUsername("svc-" + id);
        return user;
    }
}
