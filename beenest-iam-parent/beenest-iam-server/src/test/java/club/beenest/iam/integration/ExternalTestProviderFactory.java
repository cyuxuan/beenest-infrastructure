package club.beenest.iam.integration;

import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;

import java.util.List;
import java.util.Map;

/**
 * 外置扩展包用的测试提供方工厂。
 * <p>该类会被测试打进一个临时 jar 丢进外置目录,由平台在启动时装载——
 * 用来验证"第三方只交付一个 jar"这条路径本身是通的。
 * 因此它必须能脱离测试类独立存在:无参构造、只依赖核心 SPI、不引用任何测试类。</p>
 */
public class ExternalTestProviderFactory
        implements IdentityProviderFactory<IdentityProvider<IdentityProviderConfig>> {

    /** 提供方类型标识。 */
    public static final String PROVIDER_ID = "external-test";

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public String getName() {
        return "外置测试提供方";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(new ProviderConfigProperty("clientId", "客户端 ID",
                ProviderConfigProperty.ConfigType.STRING, true, false, null, ""));
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
                return new BrokeredIdentityContext("external-1", PROVIDER_ID, config.alias());
            }
        };
    }

    /** 供装载器记录用的配置样例。 */
    public static Map<String, String> sampleConfig() {
        return Map.of("clientId", "external-client");
    }
}
