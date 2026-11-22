package club.beenest.iam.authz.ldap;

import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.persistence.entity.LdapConfig;

/**
 * 持久化 LDAP 配置到网关运行时视图的转换。
 * <p>认证编排与管理端全量同步共用:口令解密用途标识只有一处定义,
 * 避免写入与读取两侧的用途串不一致导致解不开密文。</p>
 */
public final class LdapSettingsFactory {

    /** bind 口令信封加密的用途标识(写入侧与管理侧共用) */
    public static final String PASSWORD_PURPOSE = "iam-ldap-bind-password";

    /** 缺省连接超时(毫秒) */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    /** 缺省读取超时(毫秒) */
    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;

    private LdapSettingsFactory() {
    }

    /**
     * 构建运行时设置,解密密文口令。
     *
     * @param realmId      身份域主键,口令解密作用域
     * @param config       持久化配置
     * @param secretCrypto 信封加密工具
     * @return 运行时设置;必填项缺失或地址协议非法返回 null
     */
    public static LdapSettings from(Long realmId, LdapConfig config, IamSecretCrypto secretCrypto) {
        if (config == null || isBlank(config.getServerUrl()) || isBlank(config.getUserBaseDn())) {
            return null;
        }
        String bindPassword = secretCrypto.decrypt(
                PASSWORD_PURPOSE, String.valueOf(realmId), config.getBindPassword());
        try {
            return new LdapSettings(
                    config.getServerUrl(),
                    isBlank(config.getBindDn()) ? null : config.getBindDn(),
                    isBlank(bindPassword) ? null : bindPassword,
                    config.getUserBaseDn(),
                    config.getConnectTimeoutMs() == null ? DEFAULT_CONNECT_TIMEOUT_MS : config.getConnectTimeoutMs(),
                    config.getReadTimeoutMs() == null ? DEFAULT_READ_TIMEOUT_MS : config.getReadTimeoutMs());
        } catch (IllegalArgumentException e) {
            // 地址协议非法:调用方按配置不可用处理,不把原始地址回显到异常消息
            return null;
        }
    }

    /**
     * 字符串是否为 null 或空白。
     *
     * @param value 待判定字符串
     * @return true 表示为 null 或全空白
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
