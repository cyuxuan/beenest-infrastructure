package club.beenest.iam.admin.dto;

import java.util.Map;

/**
 * 身份提供方更新请求。
 *
 * @param displayName 展示名
 * @param enabled     是否启用
 * @param config      提供方配置(整体覆盖)
 */
public record IdentityProviderUpdateRequest(
        String displayName,
        Boolean enabled,
        Map<String, String> config
) {
}
