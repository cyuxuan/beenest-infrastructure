package club.beenest.iam.admin.dto;

/**
 * LDAP 联合配置响应。
 * <p>不含 bind 口令,仅以 {@code bindPasswordConfigured} 表示是否已配置;
 * 更新时口令传空串表示保持原值。</p>
 *
 * @param enabled              联合开关
 * @param serverUrl            目录地址
 * @param bindDn               搜索账号 DN
 * @param bindPasswordConfigured 是否已配置 bind 口令
 * @param userBaseDn           用户搜索基 DN
 * @param userFilter           用户定位过滤器
 * @param uuidAttribute        稳定外部标识属性
 * @param usernameAttribute    登录名属性
 * @param emailAttribute       邮箱属性
 * @param displayNameAttribute 显示名属性
 * @param connectTimeoutMs     连接超时(毫秒)
 * @param readTimeoutMs        读取超时(毫秒)
 */
public record LdapConfigResponse(
        boolean enabled,
        String serverUrl,
        String bindDn,
        boolean bindPasswordConfigured,
        String userBaseDn,
        String userFilter,
        String uuidAttribute,
        String usernameAttribute,
        String emailAttribute,
        String displayNameAttribute,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
