package club.beenest.iam.core.model;

import java.util.Map;

/**
 * 身份提供方领域模型(含数据库主键,供绑定关联使用)。
 * <p>config 保存提供方特定配置,键名由对应 Provider 定义。</p>
 *
 * @param id          身份提供方主键(iam_identity_provider.id)
 * @param realmId     所属身份域
 * @param providerId  提供方类型(oidc/wechat/alipay/feishu)
 * @param alias       域内唯一别名
 * @param displayName 展示名
 * @param enabled     是否启用
 * @param config      提供方配置键值
 */
public record IdentityProviderModel(
        Long id,
        Long realmId,
        String providerId,
        String alias,
        String displayName,
        boolean enabled,
        Map<String, String> config
) {
    /**
     * 读取配置项。
     *
     * @param key 配置键
     * @return 配置值;无配置或键不存在时返回 null
     */
    public String get(String key) {
        return config == null ? null : config.get(key);
    }
}
