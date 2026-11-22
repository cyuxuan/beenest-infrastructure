package club.beenest.iam.admin.service;

import club.beenest.iam.admin.dto.LdapConfigResponse;
import club.beenest.iam.admin.dto.LdapConfigUpdateRequest;

/**
 * LDAP/AD 用户联合配置管理服务,每域一条配置。
 * <p>bind 口令信封加密后落库,响应只返回是否已配置。</p>
 */
public interface LdapConfigService {

    /**
     * 查询当前域配置。
     *
     * @return 配置响应,未配置过时返回缺省值口径
     */
    LdapConfigResponse getConfig();

    /**
     * 更新当前域配置。
     * <p>bind 口令留空表示保持原值,非空时加密后覆盖。</p>
     *
     * @param request 配置更新参数
     * @return 更新后的配置
     */
    LdapConfigResponse updateConfig(LdapConfigUpdateRequest request);

    /**
     * 用表单值测试目录连接,配置未保存也可测。
     * <p>连接服务器地址与库中记录不同时,不采信存量 bind 口令。</p>
     *
     * @param request 表单配置
     * @return 测试结果,含面向运维的可读信息
     */
    ConnectionOutcome testConnection(LdapConfigUpdateRequest request);

    /**
     * 连接测试结果。
     *
     * @param success 是否连接成功
     * @param message 面向运维的可读信息
     */
    record ConnectionOutcome(boolean success, String message) {
    }
}
