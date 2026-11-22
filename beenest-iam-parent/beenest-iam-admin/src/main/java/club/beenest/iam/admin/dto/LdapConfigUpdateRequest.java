package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * LDAP/AD 用户联合配置更新请求。
 *
 * @param enabled              联合开关:关闭时登录链路完全不接触 LDAP
 * @param serverUrl            目录地址(ldap:// 或 ldaps://)
 * @param bindDn               搜索账号 DN;留空表示匿名绑定(目录允许时)
 * @param bindPassword         bind 口令;留空表示不修改已存口令(密文不回显)
 * @param userBaseDn           用户搜索基 DN
 * @param userFilter           用户定位过滤器,{0} 为登录名占位
 * @param uuidAttribute        稳定外部标识属性
 * @param usernameAttribute    登录名属性
 * @param emailAttribute       邮箱属性
 * @param displayNameAttribute 显示名属性
 * @param connectTimeoutMs     连接超时(毫秒)
 * @param readTimeoutMs        读取超时(毫秒)
 */
public record LdapConfigUpdateRequest(
        @NotNull(message = "联合开关不能为空") Boolean enabled,
        @NotBlank(message = "目录地址不能为空") String serverUrl,
        String bindDn,
        String bindPassword,
        @NotBlank(message = "用户搜索基不能为空") String userBaseDn,
        String userFilter,
        String uuidAttribute,
        String usernameAttribute,
        String emailAttribute,
        String displayNameAttribute,
        @NotNull(message = "连接超时不能为空") @Min(100) @Max(30000) Integer connectTimeoutMs,
        @NotNull(message = "读取超时不能为空") @Min(100) @Max(60000) Integer readTimeoutMs
) {
}
