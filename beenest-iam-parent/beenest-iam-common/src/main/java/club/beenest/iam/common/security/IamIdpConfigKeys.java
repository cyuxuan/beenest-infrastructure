package club.beenest.iam.common.security;

import java.util.Set;

/**
 * 身份提供方配置的约定键名与机密范围。
 * <p>管理端据此决定哪些配置值加密落库,broker 据此决定哪些值解密。</p>
 */
public final class IamIdpConfigKeys {

    /** 客户端密钥:泄露即可冒充本平台去外部 IdP 换票,必须加密 */
    public static final String CLIENT_SECRET = "clientSecret";

    /** 加密用途标识:与域签名私钥的用途区分,密文不可互相搬运 */
    public static final String PURPOSE = "iam-idp-config";

    /** 需要加密落库的配置键。 */
    private static final Set<String> SENSITIVE = Set.of(CLIENT_SECRET);

    /**
     * 机密键名后缀:扩展包新增带密钥的配置项时按此约定命名。
     */
    private static final String SECRET_SUFFIX = "secret";

    private IamIdpConfigKeys() {
    }

    /**
     * 判断配置键是否属于机密。
     * <p>除固定名单外,键名以 {@code secret} 结尾(忽略大小写)的也按机密处理。</p>
     *
     * @param key 配置键
     * @return true 表示需要加密落库
     */
    public static boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (SENSITIVE.contains(key)) {
            return true;
        }
        return key.toLowerCase(java.util.Locale.ROOT).endsWith(SECRET_SUFFIX);
    }
}
