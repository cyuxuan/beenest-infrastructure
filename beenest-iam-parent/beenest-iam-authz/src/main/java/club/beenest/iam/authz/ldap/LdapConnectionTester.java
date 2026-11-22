package club.beenest.iam.authz.ldap;

import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.persistence.entity.LdapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * LDAP 连接测试:管理端保存前验证目录可达、搜索账号可用、搜索基可读。
 * <p>与认证编排共用同一个网关,测试通过即认证链路前两步(定位用户 DN)在真实目录
 * 上成立;第三步口令绑定由真实登录验证。</p>
 */
@Component
public class LdapConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(LdapConnectionTester.class);

    private final LdapGateway ldapGateway;
    private final IamSecretCrypto secretCrypto;

    public LdapConnectionTester(LdapGateway ldapGateway, IamSecretCrypto secretCrypto) {
        this.ldapGateway = ldapGateway;
        this.secretCrypto = secretCrypto;
    }

    /**
     * 测试连接。
     *
     * @param realmId         域主键,解密口令密文的作用域
     * @param candidate       表单配置,尚未落库
     * @param rawBindPassword 表单填写的 bind 口令,为空表示沿用存量密文,
     *                        仅在地址与已保存配置一致时才允许传入
     * @return 测试结果,message 为面向运维的可读原因
     */
    public ConnectionTestResult test(Long realmId, LdapConfig candidate, String rawBindPassword) {
        if (isBlank(candidate.getServerUrl()) || isBlank(candidate.getUserBaseDn())) {
            return ConnectionTestResult.fail("目录地址与用户搜索基为必填项");
        }
        // 口令来源:表单优先;表单留空时才沿用存量密文。地址已变更却沿用存量密文
        // 等于把本域凭据发往任意主机,直接拒绝
        String bindPassword;
        if (!isBlank(rawBindPassword)) {
            bindPassword = rawBindPassword;
        } else if (isBlank(candidate.getBindPassword())) {
            return ConnectionTestResult.fail("目录地址已变更,请重新填写 bind 口令后再测试");
        } else {
            bindPassword = secretCrypto.decrypt(
                    LdapSettingsFactory.PASSWORD_PURPOSE, String.valueOf(realmId), candidate.getBindPassword());
        }
        LdapSettings settings;
        try {
            settings = new LdapSettings(
                    candidate.getServerUrl(),
                    isBlank(candidate.getBindDn()) ? null : candidate.getBindDn(),
                    isBlank(bindPassword) ? null : bindPassword,
                    candidate.getUserBaseDn(),
                    candidate.getConnectTimeoutMs() == null ? 3000 : candidate.getConnectTimeoutMs(),
                    candidate.getReadTimeoutMs() == null ? 5000 : candidate.getReadTimeoutMs());
        } catch (IllegalArgumentException e) {
            return ConnectionTestResult.fail(e.getMessage());
        }
        try {
            // 以搜索基自身做一次存在性检索,同时验证 bind 账号对该 base 有读权限
            Optional<LdapGateway.LdapEntry> entry = ldapGateway.search(settings, "(objectClass=*)");
            String dn = entry.map(LdapGateway.LdapEntry::dn).orElse(candidate.getUserBaseDn());
            return ConnectionTestResult.ok("连接成功,搜索账号可读取用户搜索基", dn);
        } catch (LdapUnavailableException e) {
            log.info("LDAP 连接测试失败: realmId={}, reason={}", realmId, e.getMessage());
            return ConnectionTestResult.fail(friendlyMessage(e.getMessage()));
        }
    }

    /**
     * 目录错误信息脱敏为运维可读文案,不携带口令与完整 DN。
     *
     * @param raw 目录原始错误消息
     * @return 可读原因文案
     */
    private static String friendlyMessage(String raw) {
        if (raw == null) {
            return "连接失败,请检查目录地址与网络";
        }
        if (raw.contains("认证失败") || raw.toLowerCase().contains("authenticat")) {
            return "搜索账号认证失败,请检查 bind DN 与口令";
        }
        if (raw.contains("未知主机") || raw.toLowerCase().contains("unknownhost")) {
            return "无法解析目录地址,请检查主机名";
        }
        if (raw.contains("超时") || raw.toLowerCase().contains("timeout")) {
            return "连接超时,请检查地址、端口与防火墙";
        }
        return "连接失败:" + raw;
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

    /**
     * 连接测试结果。
     *
     * @param success  是否通过
     * @param message  面向运维的可读原因
     * @param sampleDn 检索到的样例 DN,失败时为 null
     */
    public record ConnectionTestResult(boolean success, String message, String sampleDn) {

        /**
         * 构造成功结果。
         *
         * @param message  可读原因
         * @param sampleDn 样例 DN
         * @return success 为 true 的结果
         */
        static ConnectionTestResult ok(String message, String sampleDn) {
            return new ConnectionTestResult(true, message, sampleDn);
        }

        /**
         * 构造失败结果。
         *
         * @param message 可读原因
         * @return success 为 false、sampleDn 为 null 的结果
         */
        static ConnectionTestResult fail(String message) {
            return new ConnectionTestResult(false, message, null);
        }
    }
}
