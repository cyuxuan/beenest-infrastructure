package club.beenest.iam.authz.rest.dto;

/**
 * 三方登录入口。
 * <p>登录页据此渲染第三方登录按钮;不含任何敏感配置
 * (客户端密钥、端点地址等一律不下发到浏览器)。</p>
 *
 * @param alias     提供方别名
 * @param displayName 展示名
 * @param providerId 提供方类型(oidc/wechat/...)
 * @param loginUrl  发起登录的地址
 * @param browserLogin 该类型是否支持浏览器跳转登录(凭据类提供方为 false,登录页不渲染按钮)
 */
public record IdentityProviderEntry(
        String alias,
        String displayName,
        String providerId,
        String loginUrl,
        boolean browserLogin
) {
}
