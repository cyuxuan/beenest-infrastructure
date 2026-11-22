package club.beenest.iam.authz.ldap;

/**
 * 目录不可达异常:连接失败、超时、TLS 握手失败等基础设施类错误。
 * <p>与口令错误严格区分:后者是用户侧事实,前者是平台侧故障;审计日志分开统计,
 * 对外响应不做区分。</p>
 */
public class LdapUnavailableException extends RuntimeException {

    /**
     * 构造目录不可达异常。
     *
     * @param message 异常消息
     * @param cause   底层目录异常
     */
    public LdapUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
