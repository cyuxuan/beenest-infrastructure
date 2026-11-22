package club.beenest.iam.federation.broker;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.federation.registry.IdentityProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 联邦运行时登录入口测试。
 * <p>运行时只做一件事:把客户端提交的凭据原样交给提供方,再把换回的外部身份落地成本地用户。
 * 「凭据是什么含义、怎么换成外部身份」由提供方自己实现,运行时一概不解释——
 * 这条边界一旦模糊,核心就会重新长出具体场景的知识。</p>
 */
class IdentityBrokerServiceLoginTest {

    /** 提供方收到的凭据上下文。 */
    private final AtomicReference<CallbackContext> captured = new AtomicReference<>();

    @Test
    @DisplayName("凭据原样送到提供方:提供方自己认键名")
    void credentialsReachProviderUnchanged() {
        IdentityBrokerService service = new IdentityBrokerService(
                realmProvider(), identityProviderStore(), registry(new CredentialFactory(captured)),
                linkService(), secretCrypto());

        service.completeLogin("default", "wx", new CallbackContext(
                null, null, "default", null, Map.of("jsCode", "the-code")));

        assertEquals("the-code", captured.get().credential("jsCode"),
                "提供方自定的凭据必须原样送达");
        assertEquals("default", captured.get().realmKey());
    }

    @Test
    @DisplayName("提供方未注册:明确报未接入,不静默失败")
    void unregisteredProviderIsRejected() {
        IdentityBrokerService service = new IdentityBrokerService(
                realmProvider(), identityProviderStore("other-type"), registry(new CredentialFactory(captured)),
                linkService(), secretCrypto());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.completeLogin("default", "wx", new CallbackContext(
                        null, null, "default", null, Map.of("jsCode", "the-code"))));
        assertTrue(error.getMessage().contains("未接入"), "错误信息要说明提供方类型未接入: " + error.getMessage());
    }

    // ===== 桩件 =====

    private UserFederationLinkService linkService() {
        return new UserFederationLinkService(null, null, null, null) {
            @Override
            public UserModel findOrCreateLinkedUser(BrokeredIdentityContext identity, Long realmId, Long idpId) {
                return new UserModel(7L, realmId, "wx-mini-o-1", "张三", null, null,
                        1, "EXTERNAL", false, 0, Map.of());
            }
        };
    }

    private static IdentityProviderRegistry registry(IdentityProviderFactory<?> factory) {
        return new IdentityProviderRegistry(List.of(factory));
    }

    private static RealmProvider realmProvider() {
        return new RealmProvider() {
            @Override
            public Optional<RealmModel> findByRealmKey(String realmKey) {
                return Optional.of(new RealmModel(1L, "default", "默认域", true, true,
                        3600, 2592000, 1800, 86400, SubjectMode.LOGIN_NAME, null));
            }

            @Override
            public Optional<RealmModel> findById(Long id) {
                return findByRealmKey("default");
            }

            @Override
            public List<RealmModel> listEnabled() {
                return List.of();
            }
        };
    }

    private static IdentityProviderStore identityProviderStore() {
        return identityProviderStore("wechat-mini");
    }

    private static IdentityProviderStore identityProviderStore(String providerId) {
        return new IdentityProviderStore() {
            @Override
            public Optional<IdentityProviderModel> findByIdp(Long realmId, String alias) {
                return Optional.of(new IdentityProviderModel(9L, realmId, providerId, alias,
                        "测试提供方", true, Map.of()));
            }

            @Override
            public List<IdentityProviderModel> listByRealm(Long realmId) {
                return List.of();
            }

            @Override
            public Optional<Long> findLinkedUserId(Long idpId, String externalId) {
                return Optional.empty();
            }

            @Override
            public void link(Long realmId, Long userId, Long idpId, String externalId, String externalUsername) {
            }

            @Override
            public List<club.beenest.iam.core.model.IdpMapperModel> listMappers(Long idpId) {
                return List.of();
            }

            @Override
            public int unlinkByIdp(Long idpId) {
                return 0;
            }
        };
    }

    private static IamSecretCrypto secretCrypto() {
        return new IamSecretCrypto("integration-test-master-secret");
    }

    /** 凭据类提供方:自己认凭据键名。 */
    private static final class CredentialFactory
            implements IdentityProviderFactory<IdentityProvider<IdentityProviderConfig>> {

        private final AtomicReference<CallbackContext> captured;

        CredentialFactory(AtomicReference<CallbackContext> captured) {
            this.captured = captured;
        }

        @Override
        public String getProviderId() {
            return "wechat-mini";
        }

        @Override
        public String getName() {
            return "微信小程序";
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return List.of();
        }

        @Override
        public IdentityProvider<IdentityProviderConfig> create(IdentityProviderConfig config) {
            return new IdentityProvider<>() {
                @Override
                public IdentityProviderConfig getConfig() {
                    return config;
                }

                @Override
                public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
                    captured.set(ctx);
                    return new BrokeredIdentityContext("o-1", "wechat-mini", "wx-mini");
                }
            };
        }
    }
}
