package club.beenest.iam.federation.broker;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部身份落地测试。
 * <p>解析顺序是这条链路的核心:已有绑定优先,其次按提供方担保的手机号归并,最后才建新号。
 * 顺序错了会出现同一人多个账号,或者悄悄接管别人的账号,所以每个分支单独钉住。</p>
 */
class UserFederationLinkServiceTest {

    private static final Long REALM = 1L;
    private static final Long IDP = 7L;
    private static final Long USER_ID = 42L;
    private static final String EXTERNAL_ID = "o-abc";
    private static final String PHONE = "13800138000";

    private UserProvider userProvider;
    private IdentityProviderStore identityProviderStore;
    private UserFederationLinkService service;

    @BeforeEach
    void setUp() {
        userProvider = mock(UserProvider.class);
        identityProviderStore = mock(IdentityProviderStore.class);
        RoleProvider roleProvider = mock(RoleProvider.class);
        IdpMappingService mappingService = mock(IdpMappingService.class);
        when(mappingService.apply(any(), any()))
                .thenReturn(new IdpMappingService.MappingResult(Map.of(), List.of()));
        when(identityProviderStore.listMappers(IDP)).thenReturn(List.of());
        service = new UserFederationLinkService(userProvider, identityProviderStore, roleProvider, mappingService);
    }

    @Test
    @DisplayName("绑定命中且本地无手机号:补写已验证手机号")
    void backfillsPhoneOnLinkedUser() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.of(USER_ID));
        when(userProvider.findById(USER_ID))
                .thenReturn(Optional.of(user(null)))
                .thenReturn(Optional.of(user(PHONE)));

        UserModel result = service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP);

        verify(userProvider).updateVerifiedPhone(USER_ID, PHONE);
        assertEquals(PHONE, result.phone(), "返回的账号应带上补写的手机号");
        verify(userProvider, never()).createFederatedUser(anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("绑定命中但本地已有不同手机号:保留本地值")
    void keepsLocalPhoneOnLinkedUser() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.of(USER_ID));
        when(userProvider.findById(USER_ID)).thenReturn(Optional.of(user("13900139000")));

        UserModel result = service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP);

        verify(userProvider, never()).updateVerifiedPhone(anyLong(), anyString());
        assertEquals("13900139000", result.phone());
    }

    @Test
    @DisplayName("无绑定时按已验证手机号归并既有账号")
    void mergesExistingAccountByVerifiedPhone() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(userProvider.findByPhone(REALM, PHONE)).thenReturn(Optional.of(user(PHONE)));

        UserModel result = service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP);

        assertEquals(USER_ID, result.id());
        verify(identityProviderStore).link(REALM, USER_ID, IDP, EXTERNAL_ID, "微信用户");
        verify(userProvider, never()).createFederatedUser(anyLong(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("手机号未命中:按提供方担保的手机号建号并绑定")
    void createsAccountWithVerifiedPhone() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(userProvider.findByPhone(REALM, PHONE)).thenReturn(Optional.empty());
        when(userProvider.createFederatedUser(REALM, "wx-mini-o-abc", null, "微信用户", PHONE, Map.of()))
                .thenReturn(user(PHONE));

        UserModel result = service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP);

        assertEquals(USER_ID, result.id());
        verify(identityProviderStore).link(REALM, USER_ID, IDP, EXTERNAL_ID, "微信用户");
    }

    @Test
    @DisplayName("无已验证手机号:只按外部标识建号,不查手机号")
    void createsWithoutPhone() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(userProvider.createFederatedUser(REALM, "wx-mini-o-abc", null, "微信用户", null, Map.of()))
                .thenReturn(user(null));

        service.findOrCreateLinkedUser(identity(null), REALM, IDP);

        verify(userProvider, never()).findByPhone(anyLong(), anyString());
    }

    @Test
    @DisplayName("并发建号撞手机号唯一约束:归并到先到账号")
    void mergesWhenConcurrentCreateHitsUniqueIndex() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(userProvider.findByPhone(REALM, PHONE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(user(PHONE)));
        when(userProvider.createFederatedUser(anyLong(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"));

        UserModel result = service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP);

        assertEquals(USER_ID, result.id());
        verify(identityProviderStore).link(REALM, USER_ID, IDP, EXTERNAL_ID, "微信用户");
    }

    @Test
    @DisplayName("手机号账号已绑定其它外部身份:拒绝,不悄悄顶掉既有绑定")
    void rejectsConflictingBinding() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(99L));
        when(userProvider.findByPhone(REALM, PHONE)).thenReturn(Optional.of(user(PHONE)));
        doThrow(new DuplicateKeyException("duplicate"))
                .when(identityProviderStore).link(anyLong(), anyLong(), anyLong(), anyString(), any());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP));

        assertTrue(error.getMessage().contains("该外部身份已绑定其它账号"),
                "冲突提示应指明已被其它账号占用: " + error.getMessage());
    }

    @Test
    @DisplayName("建号撞登录名且无其它线索:提示先处理同名账号")
    void reportsUsernameConflict() {
        when(identityProviderStore.findLinkedUserId(IDP, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(userProvider.findByPhone(REALM, PHONE)).thenReturn(Optional.empty());
        when(userProvider.createFederatedUser(anyLong(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.findOrCreateLinkedUser(identity(PHONE), REALM, IDP));

        assertTrue(error.getMessage().contains("登录名已存在"),
                "冲突提示应指向同名账号: " + error.getMessage());
    }

    /**
     * 构造外部身份上下文。
     *
     * @param verifiedPhone 提供方担保的手机号,可为 null
     * @return 外部身份
     */
    private static BrokeredIdentityContext identity(String verifiedPhone) {
        BrokeredIdentityContext identity = new BrokeredIdentityContext(EXTERNAL_ID, "wechat-mini", "wx-mini");
        identity.setUsername("微信用户");
        identity.setNickname("微信用户");
        identity.setVerifiedPhone(verifiedPhone);
        return identity;
    }

    /**
     * 构造本地账号快照。
     *
     * @param phone 手机号,可为 null
     * @return 账号快照
     */
    private static UserModel user(String phone) {
        return new UserModel(USER_ID, REALM, "wx-mini-" + EXTERNAL_ID, "微信用户", null, phone,
                1, "EXTERNAL", false, 0, Map.of());
    }
}
