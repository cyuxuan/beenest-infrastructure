package club.beenest.iam.core.spi.federation;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;

import java.util.Map;

/**
 * 身份提供方(Identity Provider)配置。
 * <p>由 {@code iam_identity_provider} 行构建;各 Provider 实现类声明自己需要的配置键。</p>
 *
 * @param alias       别名(路由用,如 wechat / oidc-github)
 * @param providerId  提供方类型(wechat / alipay / feishu / oidc)
 * @param displayName 展示名
 * @param enabled     是否启用
 * @param config      提供方特定配置(clientId/secret/端点等)
 */
public record IdentityProviderConfig(
        String alias,
        String providerId,
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

    /**
     * 读取必填配置项。
     * <p>配置缺失时抛出业务异常,错误信息带上别名与配置键。</p>
     *
     * @param key 配置键
     * @return 配置值,非空白
     * @throws BusinessException 配置项为空时抛出
     */
    public String require(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT,
                    "身份提供方配置缺失: " + alias + "." + key);
        }
        return value;
    }
}
