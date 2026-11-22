package club.beenest.iam.core.spi.federation;

import java.util.Optional;

/**
 * 身份提供方 SPI。
 * <p>每个三方登录(微信/支付宝/飞书/小程序/通用 OIDC)实现一个 Provider,
 * 由对应的 Factory 按配置创建实例。</p>
 *
 * @param <C> 配置类型
 */
public interface IdentityProvider<C> {

    /**
     * 当前提供方配置。
     *
     * @return 创建实例时传入的配置
     */
    C getConfig();

    /**
     * 构建外部授权跳转地址。
     * <p>仅浏览器跳转类提供方使用,小程序等凭据类返回空串。</p>
     *
     * @param request 跳转请求,含回调地址、state 与附加参数
     * @return 外部授权地址;凭据类提供方返回空串
     */
    default String authorizationUrl(AuthorizationRequest request) {
        return "";
    }

    /**
     * 处理回调,换取外部身份。
     *
     * @param ctx 回调参数与调用方提交的凭据
     * @return 外部身份上下文
     */
    BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx);

    /**
     * 服务方(SP)元数据。
     * <p>仅需要登记到外部 IdP 的提供方(SAML 2.0)提供;不支持时返回空,
     * 平台据此不下发元数据端点。</p>
     *
     * @param acsUrl 本平台该提供方的断言消费地址(写入元数据)
     * @return 元数据 XML;不支持时为空
     */
    default Optional<String> serviceProviderMetadata(String acsUrl) {
        return Optional.empty();
    }
}
