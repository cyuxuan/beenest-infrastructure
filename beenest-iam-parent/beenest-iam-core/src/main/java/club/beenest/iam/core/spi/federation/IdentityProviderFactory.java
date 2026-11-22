package club.beenest.iam.core.spi.federation;

import java.util.List;
import java.util.Set;

/**
 * 身份提供方工厂 SPI。
 * <p>每个提供方类型一个工厂,注册到 {@code IdentityProviderRegistry} 后,
 * 按提供方类型(providerId)与别名创建 Provider 实例。</p>
 *
 * @param <T> Provider 类型
 */
public interface IdentityProviderFactory<T extends IdentityProvider<?>> {

    /**
     * 提供方类型标识。
     *
     * @return 类型标识,如 wechat / alipay / feishu / oidc
     */
    String getProviderId();

    /**
     * 提供方展示名。
     *
     * @return 中文展示名
     */
    String getName();

    /**
     * 声明该提供方支持的配置项。
     *
     * @return 配置项声明列表,供管理界面渲染与保存前校验
     */
    List<ProviderConfigProperty> getConfigProperties();

    /**
     * 声明该类型支持的登录形态。
     * <p>跳转类出现在登录页按钮中,凭据类出现在标准令牌端点的自定义授权类型上。</p>
     *
     * @return 登录形态集合,默认仅 REDIRECT
     */
    default Set<ProviderLoginMode> getLoginModes() {
        return Set.of(ProviderLoginMode.REDIRECT);
    }

    /**
     * 声明该类型在标准令牌端点上使用的自定义授权类型。
     * <p>凭据类提供方用它指定换取身份的授权类型;跳转类返回 null。</p>
     *
     * @return 授权类型 URI;跳转类提供方返回 null。取值须为绝对 URI,
     * 且不得与标准授权类型重名
     */
    default String getGrantType() {
        return null;
    }

    /**
     * 按配置创建 Provider 实例。
     *
     * @param config 提供方配置
     * @return 提供方实例
     */
    T create(IdentityProviderConfig config);
}
