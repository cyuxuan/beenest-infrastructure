package club.beenest.iam.provider.oidc;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty.ConfigType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用 OIDC 提供方工厂。
 * <p>以 Spring Bean 形式注册,联邦运行时按 classpath 自动发现;
 * 移除本模块依赖即下线该提供方,无需改动核心代码。</p>
 */
@Component
public class GenericOidcIdentityProviderFactory
        implements IdentityProviderFactory<GenericOidcIdentityProvider> {

    private final ObjectMapper objectMapper;

    public GenericOidcIdentityProviderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderId() {
        return "oidc";
    }

    @Override
    public String getName() {
        return "通用 OIDC";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty("clientId", "客户端 ID", ConfigType.STRING, true, false, null, "三方应用的 client_id"),
                new ProviderConfigProperty("clientSecret", "客户端密钥", ConfigType.PASSWORD, true, true, null, "三方应用的 client_secret"),
                new ProviderConfigProperty("authorizationUrl", "授权端点", ConfigType.URL, true, false, null, "如 https://idp.example.com/authorize"),
                new ProviderConfigProperty("tokenUrl", "令牌端点", ConfigType.URL, true, false, null, "如 https://idp.example.com/token"),
                new ProviderConfigProperty("userInfoUrl", "用户信息端点", ConfigType.URL, true, false, null, "如 https://idp.example.com/userinfo"),
                new ProviderConfigProperty("scope", "授权范围", ConfigType.STRING, false, false, "openid profile email", "请求的授权范围")
        );
    }

    @Override
    public GenericOidcIdentityProvider create(IdentityProviderConfig config) {
        return new GenericOidcIdentityProvider(config, objectMapper);
    }
}
