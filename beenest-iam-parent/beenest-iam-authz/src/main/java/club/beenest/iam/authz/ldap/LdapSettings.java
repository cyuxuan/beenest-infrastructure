package club.beenest.iam.authz.ldap;

import java.util.Locale;

/**
 * LDAP 网关运行时配置视图,口令为明文口径。
 * <p>由 {@code LdapUserAuthenticator} 从持久化配置构建:bind 口令在此处解密为明文,
 * 仅存在于本次认证调用的栈上,不落缓存与日志。</p>
 * <p>地址只接受 ldap/ldaps 协议:该值作为 JNDI 的 {@code PROVIDER_URL} 交给 JDK 解析,
 * JNDI 支持不止 LDAP,放任其它协议会让配置入口变成任意协议客户端(rmi/iiop 历史上
 * 可远程加载字节码)。校验在构造器而非调用方完成,认证链路与连接测试两个入口共用。</p>
 *
 * @param serverUrl        目录服务地址,必须以 ldap:// 或 ldaps:// 开头
 * @param bindDn           搜索账号 DN,为空表示匿名
 * @param bindPassword     搜索账号口令明文
 * @param userBaseDn       用户搜索基 DN
 * @param connectTimeoutMs 连接超时(毫秒)
 * @param readTimeoutMs    读取超时(毫秒)
 */
public record LdapSettings(String serverUrl, String bindDn, String bindPassword,
                           String userBaseDn, int connectTimeoutMs, int readTimeoutMs) {

    /** 允许的目录协议前缀 */
    private static final String SCHEME_LDAP = "ldap://";
    private static final String SCHEME_LDAPS = "ldaps://";

    /** 构造并校验协议前缀,归一化后大小写与首尾空格不敏感。 */
    public LdapSettings {
        String normalized = serverUrl == null ? "" : serverUrl.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith(SCHEME_LDAP) && !normalized.startsWith(SCHEME_LDAPS)) {
            // 不带原始值进异常消息:该值可能由攻击者构造,回显会把探测结果带回去
            throw new IllegalArgumentException("LDAP 目录地址必须以 ldap:// 或 ldaps:// 开头");
        }
    }
}
