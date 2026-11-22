package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty.ConfigType;
import club.beenest.iam.core.spi.federation.ProviderLoginMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 微信小程序提供方工厂。
 * <p>以 Spring Bean 形式注册时随应用上下文生效;打成独立 jar 放进外置扩展包目录时,
 * 由装载器按服务声明创建。两条注册路径都要求工厂具备无参构造,JSON 工具因此自带实例。</p>
 */
@Component
public class WeChatMiniProgramFactory implements IdentityProviderFactory<WeChatMiniProgramProvider> {

    /** 本提供方在标准令牌端点上的自定义授权类型。 */
    public static final String GRANT_TYPE = "urn:beenest:iam:grant-type:wechat-mini";

    /** JSON 工具:外置装载没有应用上下文,不依赖 Spring 注入。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getProviderId() {
        return "wechat-mini";
    }

    @Override
    public String getName() {
        return "微信小程序";
    }

    @Override
    public Set<ProviderLoginMode> getLoginModes() {
        // 小程序没有浏览器跳转地址:只能由客户端直传凭据
        return Set.of(ProviderLoginMode.CREDENTIAL);
    }

    @Override
    public String getGrantType() {
        // 客户端凭该授权类型携带 jsCode 访问令牌端点,端点再分派回本提供方换身份
        return GRANT_TYPE;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty("clientId", "小程序 AppID", ConfigType.STRING, true, false, null,
                        "微信小程序后台的 AppID"),
                new ProviderConfigProperty("clientSecret", "小程序 AppSecret", ConfigType.PASSWORD, true, true, null,
                        "微信小程序后台的 AppSecret,加密落库且不回显"),
                new ProviderConfigProperty("apiBaseUrl", "接口基址", ConfigType.URL, false, false,
                        "https://api.weixin.qq.com", "微信接口基址,私有部署或测试时覆盖"),
                new ProviderConfigProperty("phoneLoginRequired", "必须授权手机号", ConfigType.BOOLEAN, false, false,
                        "true", "开启后登录必须同时提交手机号授权 code;关闭则退回仅按 openid 建号")
        );
    }

    @Override
    public WeChatMiniProgramProvider create(IdentityProviderConfig config) {
        return new WeChatMiniProgramProvider(config, objectMapper);
    }
}
