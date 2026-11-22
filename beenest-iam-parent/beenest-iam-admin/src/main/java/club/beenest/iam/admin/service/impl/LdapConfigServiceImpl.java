package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.dto.LdapConfigResponse;
import club.beenest.iam.admin.dto.LdapConfigUpdateRequest;
import club.beenest.iam.admin.service.LdapConfigService;
import club.beenest.iam.authz.ldap.LdapConnectionTester;
import club.beenest.iam.authz.ldap.LdapSettingsFactory;
import club.beenest.iam.authz.principal.CurrentUserHolder;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import org.springframework.stereotype.Service;

/**
 * LDAP 联合配置管理实现。
 * <p>bind 口令经 {@link IamSecretCrypto} 信封加密后落库,响应只返回是否已配置;
 * 更新时口令留空表示保持原值。</p>
 */
@Service
public class LdapConfigServiceImpl implements LdapConfigService {

    private final IamLdapConfigStore configStore;
    private final IamSecretCrypto secretCrypto;
    private final LdapConnectionTester connectionTester;

    public LdapConfigServiceImpl(IamLdapConfigStore configStore, IamSecretCrypto secretCrypto,
                                 LdapConnectionTester connectionTester) {
        this.configStore = configStore;
        this.secretCrypto = secretCrypto;
        this.connectionTester = connectionTester;
    }

    @Override
    public LdapConfigResponse getConfig() {
        var principal = CurrentUserHolder.require();
        LdapConfig config = configStore.ensureConfig(principal.getRealmId());
        return toResponse(config);
    }

    @Override
    public LdapConfigResponse updateConfig(LdapConfigUpdateRequest request) {
        var principal = CurrentUserHolder.require();
        Long realmId = principal.getRealmId();
        LdapConfig config = configStore.ensureConfig(realmId);
        config.setEnabled(Boolean.TRUE.equals(request.enabled()));
        config.setServerUrl(trimToNull(request.serverUrl()));
        validateServerUrl(config.getServerUrl());
        config.setBindDn(trimToEmpty(request.bindDn()));
        // 口令留空 = 不修改;非空 = 加密后覆盖(XML 更新语句对空串同样视为不修改,双保险)
        if (request.bindPassword() != null && !request.bindPassword().isEmpty()) {
            config.setBindPassword(secretCrypto.encrypt(LdapSettingsFactory.PASSWORD_PURPOSE,
                    String.valueOf(realmId), request.bindPassword()));
        }
        config.setUserBaseDn(trimToNull(request.userBaseDn()));
        config.setUserFilter(defaultIfBlank(request.userFilter(), "(sAMAccountName={0})"));
        config.setUuidAttribute(defaultIfBlank(request.uuidAttribute(), "objectGUID"));
        config.setUsernameAttribute(defaultIfBlank(request.usernameAttribute(), "sAMAccountName"));
        config.setEmailAttribute(defaultIfBlank(request.emailAttribute(), "mail"));
        config.setDisplayNameAttribute(defaultIfBlank(request.displayNameAttribute(), "displayName"));
        config.setConnectTimeoutMs(request.connectTimeoutMs());
        config.setReadTimeoutMs(request.readTimeoutMs());
        configStore.updateConfig(config, principal.getRealmKey());
        return toResponse(configStore.ensureConfig(realmId));
    }

    @Override
    public ConnectionOutcome testConnection(LdapConfigUpdateRequest request) {
        var principal = CurrentUserHolder.require();
        Long realmId = principal.getRealmId();
        LdapConfig stored = configStore.ensureConfig(realmId);
        LdapConfig candidate = new LdapConfig();
        candidate.setServerUrl(trimToEmpty(request.serverUrl()));
        // 测试连接会让服务端主动出网,地址必须限定为目录服务协议
        validateServerUrl(candidate.getServerUrl());
        candidate.setBindDn(trimToEmpty(request.bindDn()));
        candidate.setUserBaseDn(trimToEmpty(request.userBaseDn()));
        candidate.setConnectTimeoutMs(request.connectTimeoutMs());
        candidate.setReadTimeoutMs(request.readTimeoutMs());
        // 存量 bind 口令只用于已经保存过的地址,换地址时必须重新输入口令
        boolean sameServer = !candidate.getServerUrl().isEmpty()
                && candidate.getServerUrl().equalsIgnoreCase(trimToEmpty(stored.getServerUrl()));
        candidate.setBindPassword(sameServer ? stored.getBindPassword() : null);
        LdapConnectionTester.ConnectionTestResult result =
                connectionTester.test(realmId, candidate, request.bindPassword());
        return new ConnectionOutcome(result.success(), result.message());
    }

    /**
     * 校验目录服务地址:只接受 ldap(s) 协议,且不指向链路本地与云元数据地址。
     * <p>本方法不限制私网与回环地址——目录服务本来就部署在内网,也可能与身份服务同机;
     * 拦截的是链路本地段与元数据主机,这些地址上不存在合法的目录服务。</p>
     *
     * @param serverUrl 目录服务地址;为空表示未配置,直接放行
     * @throws BusinessException 协议不受支持或指向本机地址
     */
    private static void validateServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return;
        }
        String value = serverUrl.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("ldap://") && !lower.startsWith("ldaps://")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "目录服务地址必须以 ldap:// 或 ldaps:// 开头");
        }
        String host = hostOf(value);
        if (host == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "目录服务地址缺少主机名");
        }
        // 回环地址允许:目录服务与身份服务同机部署(含内嵌目录)是常见形态。
        // 拦的是链路本地段与云元数据主机——这两类地址上不存在目录服务
        if (host.startsWith("169.254.") || "metadata".equals(host)
                || "metadata.google.internal".equals(host)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "目录服务地址不允许指向链路本地或元数据地址");
        }
    }

    /**
     * 从 ldap(s) 地址中取主机名(不含端口)。
     *
     * @param serverUrl 目录服务地址
     * @return 主机名;无法解析时返回 null
     */
    private static String hostOf(String serverUrl) {
        try {
            java.net.URI uri = java.net.URI.create(serverUrl);
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * LDAP 配置转响应,口令只回传是否已配置。
     *
     * @param config LDAP 配置
     * @return LDAP 配置响应
     */
    private LdapConfigResponse toResponse(LdapConfig config) {
        return new LdapConfigResponse(
                Boolean.TRUE.equals(config.getEnabled()),
                nullToEmpty(config.getServerUrl()),
                nullToEmpty(config.getBindDn()),
                config.getBindPassword() != null && !config.getBindPassword().isEmpty(),
                nullToEmpty(config.getUserBaseDn()),
                nullToEmpty(config.getUserFilter()),
                nullToEmpty(config.getUuidAttribute()),
                nullToEmpty(config.getUsernameAttribute()),
                nullToEmpty(config.getEmailAttribute()),
                nullToEmpty(config.getDisplayNameAttribute()),
                config.getConnectTimeoutMs() == null ? 3000 : config.getConnectTimeoutMs(),
                config.getReadTimeoutMs() == null ? 5000 : config.getReadTimeoutMs());
    }

    /**
     * 去除首尾空白,空白值返回 null。
     *
     * @param value 原始值
     * @return 去空白后的值,null 或空白时为 null
     */
    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 去除首尾空白,null 返回空串。
     *
     * @param value 原始值
     * @return 去空白后的值
     */
    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * null 转空串。
     *
     * @param value 原始值
     * @return 原始值或空串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 空白值取默认值。
     *
     * @param value    原始值
     * @param fallback 默认值
     * @return 去空白后的原始值或默认值
     */
    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
