package club.beenest.iam.authz.saml;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.RoleModel;
import club.beenest.iam.core.model.SamlServiceProviderModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.core.spi.provider.RoleProvider;
import club.beenest.iam.core.spi.provider.SamlServiceProviderStore;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SAML 身份提供方编排测试。
 * <p>钉住三件事:未登记或已停用的 SP 一律拒绝、同一个请求 ID 只能签发一次、
 * 断言里的消费地址与 NameID 格式以请求为准、缺失时回落登记项。</p>
 */
class SamlIdpServiceTest {

    private static final Long REALM_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final String REALM_KEY = "nestid";
    private static final String SP_ENTITY_ID = "https://sp.example.com/metadata";
    private static final String ACS = "https://sp.example.com/acs";
    private static final String IDP_ENTITY_ID = "https://iam.example.com/realms/nestid/saml/metadata";

    private RealmProvider realmProvider;
    private SamlServiceProviderStore serviceProviderStore;
    private SamlIdpSigningService signingService;
    private UserProvider userProvider;
    private RoleProvider roleProvider;
    private RedisUtils redisUtils;
    private SamlIdpService service;

    @BeforeEach
    void setUp() {
        realmProvider = mock(RealmProvider.class);
        serviceProviderStore = mock(SamlServiceProviderStore.class);
        signingService = mock(SamlIdpSigningService.class);
        userProvider = mock(UserProvider.class);
        roleProvider = mock(RoleProvider.class);
        redisUtils = mock(RedisUtils.class);
        // 属性映射是纯函数,用真实实例
        service = new SamlIdpService(realmProvider, serviceProviderStore, signingService,
                new SamlIdpAttributeMapper(), userProvider, roleProvider, redisUtils);
        // 断言装配要读用户与角色,默认给一个可用的用户与两个角色
        when(userProvider.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(roleProvider.findRolesByUserId(REALM_ID, USER_ID))
                .thenReturn(List.of(role("ADMIN"), role("USER")));
    }

    @Test
    void requireServiceProviderRejectsUnknownEntity() {
        when(serviceProviderStore.findByEntityId(REALM_ID, SP_ENTITY_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.requireServiceProvider(REALM_ID, SP_ENTITY_ID));
    }

    @Test
    void requireServiceProviderRejectsDisabledProvider() {
        when(serviceProviderStore.findByEntityId(REALM_ID, SP_ENTITY_ID))
                .thenReturn(Optional.of(provider(false, Map.of())));

        assertThrows(BusinessException.class, () -> service.requireServiceProvider(REALM_ID, SP_ENTITY_ID));
    }

    @Test
    void requireServiceProviderReturnsEnabledProvider() {
        when(serviceProviderStore.findByEntityId(REALM_ID, SP_ENTITY_ID))
                .thenReturn(Optional.of(provider(true, Map.of())));

        assertEquals(SP_ENTITY_ID, service.requireServiceProvider(REALM_ID, SP_ENTITY_ID).entityId());
    }

    @Test
    void consumeRequestIdRejectsReplay() {
        when(redisUtils.setIfAbsent(anyString(), anyString(), any())).thenReturn(true, false);

        service.consumeRequestId(REALM_KEY, "_req-1");
        // 第二次同一个请求 ID:必须拒绝,否则同一个断言可以被反复索取
        assertThrows(BusinessException.class, () -> service.consumeRequestId(REALM_KEY, "_req-1"));
    }

    @Test
    void assertionSpecPrefersValuesFromRequest() {
        SamlIdpAuthnRequest request = new SamlIdpAuthnRequest("_req-1", SP_ENTITY_ID,
                "https://iam.example.com/realms/nestid/saml/sso", "https://sp.example.com/other-acs",
                "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress", false, Instant.now(), "relay", false);

        SamlIdpAssertionSpec spec = service.assertionSpec(realm(), provider(true, Map.of()), request,
                principal(), "sid-1", IDP_ENTITY_ID);

        assertEquals("https://sp.example.com/other-acs", spec.acsUrl());
        assertEquals("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress", spec.nameIdFormat());
        assertEquals("_req-1", spec.inResponseTo());
        assertEquals("sid-1", spec.sessionIndex());
        assertEquals(SP_ENTITY_ID, spec.audience());
        assertEquals(IDP_ENTITY_ID, spec.issuer());
        assertEquals("alice", spec.nameId());
    }

    @Test
    void assertionSpecFallsBackToRegistration() {
        SamlIdpAssertionSpec spec = service.assertionSpec(realm(), provider(true, Map.of()), null,
                principal(), "sid-1", IDP_ENTITY_ID);

        assertEquals(ACS, spec.acsUrl());
        assertEquals("urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified", spec.nameIdFormat());
        // IdP 发起时没有对应的请求 ID
        assertNull(spec.inResponseTo());
    }

    @Test
    void assertionSpecCarriesMappedAttributes() {
        SamlIdpAssertionSpec spec = service.assertionSpec(realm(),
                provider(true, Map.of("mail", "email", "memberOf", "roles")), null,
                principal(), "sid-1", IDP_ENTITY_ID);

        assertEquals(List.of("alice@corp.example.com"), spec.attributes().get("mail"));
        assertEquals(List.of("ADMIN", "USER"), spec.attributes().get("memberOf"));
        // 默认三项不在显式映射里,不应出现
        assertFalse(spec.attributes().containsKey("username"));
    }

    @Test
    void findUserIdByNameIdIgnoresBlankNameId() {
        assertTrue(service.findUserIdByNameId(REALM_ID, "  ").isEmpty());
        assertTrue(service.findUserIdByNameId(REALM_ID, null).isEmpty());
    }

    @Test
    void findUserIdByNameIdResolvesLocalUser() {
        when(userProvider.findByUsername(REALM_ID, "alice")).thenReturn(Optional.of(user()));

        assertEquals(Optional.of(USER_ID), service.findUserIdByNameId(REALM_ID, "alice"));
    }

    @Test
    void requireRealmRejectsUnknownRealm() {
        when(realmProvider.findByRealmKey("missing")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.requireRealm("missing"));
    }

    // ------------------------------------------------------------------ 夹具

    private static RealmModel realm() {
        return new RealmModel(REALM_ID, REALM_KEY, "蜂巢身份", true, false,
                300L, 1800L, 1800L, 28800L, null, null);
    }

    private static SamlServiceProviderModel provider(boolean enabled, Map<String, String> mapping) {
        return new SamlServiceProviderModel(11L, REALM_ID, "sp-a", SP_ENTITY_ID, "示例应用", ACS, "",
                "urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified", true, false, "", mapping, enabled);
    }

    private static IamUserPrincipal principal() {
        return new IamUserPrincipal(USER_ID, REALM_ID, REALM_KEY, "alice", "爱丽丝", 0,
                List.of(), Map.of(), IamUserPrincipal.EPOCH_UNKNOWN, "PASSWORD");
    }

    private static UserModel user() {
        return new UserModel(USER_ID, REALM_ID, "alice", "爱丽丝", "alice@corp.example.com", null,
                1, "INTERNAL", true, 0, Map.of("dept", "研发部"));
    }

    private static RoleModel role(String code) {
        return new RoleModel(null, REALM_ID, code, code, RoleModel.ContainerType.REALM, null, null, false);
    }
}
