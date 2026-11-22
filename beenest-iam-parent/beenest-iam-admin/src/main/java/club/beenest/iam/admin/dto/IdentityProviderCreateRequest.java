package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 身份提供方创建请求。
 *
 * @param alias       域内唯一别名(如 wechat-corp)
 * @param providerId  提供方类型(oidc/wechat/alipay/feishu)
 * @param displayName 展示名
 * @param enabled     是否启用
 * @param config      提供方配置(clientId/clientSecret/issuerUri 等)
 */
public record IdentityProviderCreateRequest(
        @NotBlank(message = "别名不能为空") String alias,
        @NotBlank(message = "提供方类型不能为空") String providerId,
        @NotBlank(message = "展示名不能为空") String displayName,
        Boolean enabled,
        Map<String, String> config
) {
}
