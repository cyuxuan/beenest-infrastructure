package club.beenest.iam.federation.registry;

import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderLoginMode;
import club.beenest.iam.core.spi.federation.ProviderTypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供方类型注册表测试。
 * <p>类型元数据是热插拔的入口:管理端据此渲染类型下拉与配置表单。
 * 能力位错了,控制台就会把小程序类提供方当成可跳转项渲染出来,
 * 用户点下去只会得到一个必然失败的入口。</p>
 */
class IdentityProviderRegistryTest {

    @Test
    @DisplayName("类型元数据按 providerId 汇总,含登录形态与配置项声明")
    void listProviderTypesExposesMetadata() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(
                new StubFactory("oidc", Set.of(ProviderLoginMode.REDIRECT)),
                new StubFactory("wechat-mini", Set.of(ProviderLoginMode.CREDENTIAL))));

        List<ProviderTypeInfo> types = registry.listProviderTypes();

        assertEquals(2, types.size());
        ProviderTypeInfo oidc = types.stream()
                .filter(t -> "oidc".equals(t.providerId())).findFirst().orElseThrow();
        assertTrue(oidc.loginModes().contains(ProviderLoginMode.REDIRECT),
                "浏览器跳转类提供方应当声明跳转形态");
        assertEquals("测试 oidc", oidc.name());
        assertEquals(1, oidc.configProperties().size());

        ProviderTypeInfo mini = types.stream()
                .filter(t -> "wechat-mini".equals(t.providerId())).findFirst().orElseThrow();
        assertFalse(mini.loginModes().contains(ProviderLoginMode.REDIRECT),
                "凭据类提供方不能出现在浏览器跳转入口里");
        assertTrue(mini.loginModes().contains(ProviderLoginMode.CREDENTIAL));
    }

    @Test
    @DisplayName("自定义授权类型:按授权类型能找回工厂,且不与标准类型重名")
    void grantTypeResolvesFactory() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(
                new StubFactory("wechat-mini", Set.of(ProviderLoginMode.CREDENTIAL),
                        "urn:beenest:iam:grant-type:wechat-mini")));

        assertTrue(registry.findByGrantType("urn:beenest:iam:grant-type:wechat-mini").isPresent(),
                "令牌端点要能按授权类型找到对应的提供方");
        assertTrue(registry.findByGrantType("urn:beenest:iam:grant-type:unknown").isEmpty());
        assertTrue(registry.findByGrantType(null).isEmpty());
    }

    @Test
    @DisplayName("自定义授权类型与标准类型重名:直接拒绝注册,不留后门")
    void standardGrantTypeIsRejected() {
        assertThrows(IllegalStateException.class, () -> new IdentityProviderRegistry(List.of(
                new StubFactory("hijack", Set.of(ProviderLoginMode.CREDENTIAL), "authorization_code"))),
                "占用标准授权类型等于给平台留后门,必须在注册时挡住");
    }

    @Test
    @DisplayName("注册被拒时:不能先把同名类型顶掉再抛异常")
    void rejectedRegistrationKeepsOriginalFactory() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(
                new StubFactory("oidc", Set.of(ProviderLoginMode.REDIRECT))));

        assertThrows(IllegalStateException.class, () -> registry.register(
                new StubFactory("oidc", Set.of(ProviderLoginMode.CREDENTIAL), "authorization_code")),
                "占用标准授权类型的注册必须被拒绝");

        IdentityProviderFactory<?> kept = registry.getFactory("oidc").orElseThrow();
        assertTrue(kept.getLoginModes().contains(ProviderLoginMode.REDIRECT),
                "被拒绝的注册不能顶掉原有的内置实现:异常被装载器吞掉后,平台会带着一个陌生实现继续跑");
    }

    @Test
    @DisplayName("未注册的类型不在元数据里")
    void unregisteredTypeAbsent() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(
                new StubFactory("oidc", Set.of(ProviderLoginMode.REDIRECT))));

        assertTrue(registry.listProviderTypes().stream()
                .noneMatch(t -> "wechat-web".equals(t.providerId())));
    }

    /** 桩工厂:只实现元数据相关的几个方法。 */
    private static final class StubFactory
            implements IdentityProviderFactory<IdentityProvider<IdentityProviderConfig>> {

        private final String providerId;
        private final Set<ProviderLoginMode> loginModes;
        private final String grantType;

        StubFactory(String providerId, Set<ProviderLoginMode> loginModes) {
            this(providerId, loginModes, null);
        }

        StubFactory(String providerId, Set<ProviderLoginMode> loginModes, String grantType) {
            this.providerId = providerId;
            this.loginModes = loginModes;
            this.grantType = grantType;
        }

        @Override
        public String getProviderId() {
            return providerId;
        }

        @Override
        public String getName() {
            return "测试 " + providerId;
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return List.of(new ProviderConfigProperty("clientId", "客户端 ID",
                    ProviderConfigProperty.ConfigType.STRING, true, false, null, ""));
        }

        @Override
        public Set<ProviderLoginMode> getLoginModes() {
            return loginModes;
        }

        @Override
        public String getGrantType() {
            return grantType;
        }

        @Override
        public IdentityProvider<IdentityProviderConfig> create(IdentityProviderConfig config) {
            throw new UnsupportedOperationException("测试不需要真实实例");
        }
    }
}
