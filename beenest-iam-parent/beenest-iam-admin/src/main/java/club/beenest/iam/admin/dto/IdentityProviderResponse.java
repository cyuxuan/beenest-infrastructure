package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 身份提供方响应。
 * <p>config 中的敏感项(clientSecret 等)不透出,仅返回键名。</p>
 *
 * @param id          主键
 * @param alias       域内唯一别名
 * @param providerId  提供方类型(oidc/wechat/alipay/feishu)
 * @param displayName 展示名
 * @param enabled     是否启用
 * @param configKeys  配置键名列表(值不透出)
 * @param createdTime 创建时间
 */
public record IdentityProviderResponse(
        Long id,
        String alias,
        String providerId,
        String displayName,
        Boolean enabled,
        java.util.List<String> configKeys,
        LocalDateTime createdTime
) {
}
