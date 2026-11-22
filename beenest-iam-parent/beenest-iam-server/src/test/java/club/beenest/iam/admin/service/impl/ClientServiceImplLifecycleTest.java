package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ClientDtoConverter;
import club.beenest.iam.admin.dto.ClientResponse;
import club.beenest.iam.admin.dto.ClientTypeChangeRequest;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.converter.ClientConverter;
import club.beenest.iam.persistence.entity.Client;
import club.beenest.iam.persistence.entity.ClientReferenceCount;
import club.beenest.iam.persistence.manager.IamClientManager;
import club.beenest.iam.persistence.manager.IamClientScopeManager;
import club.beenest.iam.persistence.manager.IamUserManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 客户端删除与类型变更的校验测试。
 * <p>两条路径都在守"配置历史不能变成安全边界"这条线:删除时不允许留下悬空引用,
 * 改类型时不允许已投入使用的客户端跨类型继续持有授权。</p>
 */
class ClientServiceImplLifecycleTest {

    private static final Long REALM_ID = 1L;
    private static final Long CLIENT_ID = 100L;

    private final IamClientManager clientManager = mock(IamClientManager.class);
    private final IamClientScopeManager clientScopeManager = mock(IamClientScopeManager.class);
    private final ClientConverter clientConverter = new ClientConverter();
    private final ClientDtoConverter dtoConverter = new ClientDtoConverter(clientConverter);
    private final IamUserManager userManager = mock(IamUserManager.class);
    private final LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);

    private ClientServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClientServiceImpl(clientManager, clientScopeManager, dtoConverter, clientConverter,
                userManager, catalog);
        IamUserPrincipal operator = new IamUserPrincipal(
                2L, REALM_ID, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("无下游引用时删除客户端,并清掉作用域分配")
    void deleteWithoutReferencesCleansScopeMappings() {
        when(clientManager.getById(CLIENT_ID)).thenReturn(client("CONFIDENTIAL", null));
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(new ClientReferenceCount());

        assertDoesNotThrow(() -> service.delete(CLIENT_ID));

        verify(clientScopeManager).removeClientScopes(CLIENT_ID);
        verify(clientManager).remove(CLIENT_ID);
    }

    @Test
    @DisplayName("作用域分配不再阻塞删除:客户端自己的配置不算下游引用")
    void scopeMappingsDoNotBlockDelete() {
        when(clientManager.getById(CLIENT_ID)).thenReturn(client("CONFIDENTIAL", null));
        ClientReferenceCount references = new ClientReferenceCount();
        references.setConsentCount(0L);
        references.setSessionCount(0L);
        references.setRoleCount(0L);
        references.setAppCount(0L);
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(references);

        assertDoesNotThrow(() -> service.delete(CLIENT_ID));

        verify(clientScopeManager).removeClientScopes(CLIENT_ID);
        verify(clientManager).remove(CLIENT_ID);
    }

    @Test
    @DisplayName("有下游引用时拒绝删除,并逐类给出条数")
    void deleteWithReferencesReportsBreakdown() {
        when(clientManager.getById(CLIENT_ID)).thenReturn(client("CONFIDENTIAL", null));
        ClientReferenceCount references = new ClientReferenceCount();
        references.setConsentCount(1L);
        references.setSessionCount(2L);
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(references);

        BusinessException error = assertThrows(BusinessException.class, () -> service.delete(CLIENT_ID));

        assertTrue(error.getMessage().contains("授权同意 1 条"));
        assertTrue(error.getMessage().contains("在线会话 2 条"));
        verify(clientManager, never()).remove(anyLong());
    }

    @Test
    @DisplayName("同类型变更幂等,不重发密钥")
    void changeTypeToSameTypeIsNoop() {
        when(clientManager.getById(CLIENT_ID)).thenReturn(client("PUBLIC", null));

        ClientResponse response = service.changeType(CLIENT_ID, new ClientTypeChangeRequest("PUBLIC"));

        assertNull(response.clientSecret());
        verify(clientManager, never()).changeType(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("已投入使用的客户端拒绝变更类型")
    void changeTypeRejectedWhenClientAlreadyUsed() {
        when(clientManager.getById(CLIENT_ID)).thenReturn(client("CONFIDENTIAL", null));
        ClientReferenceCount references = new ClientReferenceCount();
        references.setEverSessionCount(3L);
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(references);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeType(CLIENT_ID, new ClientTypeChangeRequest("PUBLIC")));

        assertTrue(error.getMessage().contains("已投入使用"));
        verify(clientManager, never()).changeType(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("带凭据模式的客户端不能降级为公开客户端")
    void changeTypeToPublicRejectedWithClientCredentials() {
        when(clientManager.getById(CLIENT_ID))
                .thenReturn(client("CONFIDENTIAL", "authorization_code,client_credentials"));
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(new ClientReferenceCount());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeType(CLIENT_ID, new ClientTypeChangeRequest("PUBLIC")));

        assertTrue(error.getMessage().contains("client_credentials"));
        verify(clientManager, never()).changeType(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("升级为机密客户端时签发新密钥并回显一次")
    void changeTypeToConfidentialIssuesSecret() {
        Client before = client("PUBLIC", null);
        before.setRequirePkce(true);
        // 首次读取用于判定当前类型,回读用于组装响应
        when(clientManager.getById(CLIENT_ID)).thenReturn(before, client("CONFIDENTIAL", null));
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(new ClientReferenceCount());
        when(clientManager.changeType(CLIENT_ID, "CONFIDENTIAL", true)).thenReturn("plain-secret");

        ClientResponse response = service.changeType(CLIENT_ID, new ClientTypeChangeRequest("CONFIDENTIAL"));

        assertNotNull(response);
        assertEquals("plain-secret", response.clientSecret());
    }

    @Test
    @DisplayName("降级为公开客户端时清空密钥并强制 PKCE")
    void changeTypeToPublicForcesPkce() {
        Client before = client("CONFIDENTIAL", "authorization_code");
        before.setRequirePkce(false);
        when(clientManager.getById(CLIENT_ID)).thenReturn(before, client("PUBLIC", null));
        when(clientManager.countReferences(CLIENT_ID)).thenReturn(new ClientReferenceCount());
        when(clientManager.changeType(eq(CLIENT_ID), eq("PUBLIC"), eq(true))).thenReturn(null);

        ClientResponse response = service.changeType(CLIENT_ID, new ClientTypeChangeRequest("PUBLIC"));

        assertNull(response.clientSecret());
        verify(clientManager).changeType(CLIENT_ID, "PUBLIC", true);
    }

    @Test
    @DisplayName("跨域客户端按不存在处理")
    void changeTypeRejectsForeignRealmClient() {
        Client foreign = client("CONFIDENTIAL", null);
        foreign.setRealmId(9L);
        when(clientManager.getById(CLIENT_ID)).thenReturn(foreign);

        assertThrows(BusinessException.class,
                () -> service.changeType(CLIENT_ID, new ClientTypeChangeRequest("PUBLIC")));
        verify(clientManager, never()).changeType(anyLong(), anyString(), any());
    }

    /**
     * 构造本域客户端。
     *
     * @param clientType 客户端类型
     * @param grantTypes 授权类型,逗号分隔,可为 null
     * @return 客户端实体
     */
    private static Client client(String clientType, String grantTypes) {
        Client client = new Client();
        client.setId(CLIENT_ID);
        client.setRealmId(REALM_ID);
        client.setClientKey("portal");
        client.setClientName("门户");
        client.setClientType(clientType);
        client.setGrantTypes(grantTypes);
        client.setRequirePkce(true);
        client.setEnabled(true);
        return client;
    }
}
