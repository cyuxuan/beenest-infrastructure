package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty.ConfigType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 微信开放平台扫码登录提供方工厂。
 * <p>以 Spring Bean 形式注册时随应用上下文生效;打成独立 jar 放进外置扩展包目录时,
 * 由装载器按服务声明创建。两条注册路径都要求工厂具备无参构造,JSON 工具因此自带实例。</p>
 */
@Component
public class WeChatWebFactory implements IdentityProviderFactory<WeChatWebProvider> {

    /** JSON 工具:外置装载没有应用上下文,不依赖 Spring 注入。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderId() {
        return "wechat-web";
    }

    @Override
    public String getName() {
        return "微信扫码登录";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty("clientId", "开放平台 AppID", ConfigType.STRING, true, false, null,
                        "微信开放平台「网站应用」的 AppID"),
                new ProviderConfigProperty("clientSecret", "开放平台 AppSecret", ConfigType.PASSWORD, true, true, null,
                        "微信开放平台「网站应用」的 AppSecret,加密落库且不回显"),
                new ProviderConfigProperty("scope", "授权范围", ConfigType.STRING, false, false, "snsapi_login",
                        "snsapi_login 才会返回昵称头像"),
                new ProviderConfigProperty("authorizationUrl", "扫码授权端点", ConfigType.URL, false, false,
                        "https://open.weixin.qq.com/connect/qrconnect", "微信扫码授权地址"),
                new ProviderConfigProperty("tokenUrl", "令牌端点", ConfigType.URL, false, false,
                        "https://api.weixin.qq.com/sns/oauth2/access_token", "授权码换访问令牌的地址"),
                new ProviderConfigProperty("userInfoUrl", "用户信息端点", ConfigType.URL, false, false,
                        "https://api.weixin.qq.com/sns/userinfo", "读取昵称头像的地址")
        );
    }

    @Override
    public WeChatWebProvider create(IdentityProviderConfig config) {
        return new WeChatWebProvider(config, objectMapper);
    }
}
