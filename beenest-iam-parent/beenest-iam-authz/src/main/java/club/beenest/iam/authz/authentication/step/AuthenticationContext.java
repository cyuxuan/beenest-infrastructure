package club.beenest.iam.authz.authentication.step;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.persistence.entity.LoginPolicy;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 认证上下文。
 * <p>一次认证调用对应一个实例,在步骤链中传递并逐步填充:前置步骤写入身份域与登录策略,
 * 凭据步骤写入已解析的用户与登录方式,后续步骤据此判定。非线程安全,禁止跨请求复用。</p>
 */
public class AuthenticationContext {

    /** 提交的登录名 */
    private final String username;
    /** 提交的凭据(明文口令);非口令类登录为 null */
    private final String credentials;
    /** 当前请求;异步调用等无请求上下文时为 null */
    private final HttpServletRequest request;
    /** 来源 IP,构造时从请求取出;无请求上下文时为 null */
    private final String clientIp;

    /** 已解析的身份域,由前置步骤写入 */
    private RealmModel realm;
    /** 域登录策略,由前置步骤写入 */
    private LoginPolicy loginPolicy;
    /** 已解析的用户,由凭据步骤写入;账号不存在时为 null */
    private UserModel user;
    /** 第一因素认证方式,默认口令;目录兜底成功时改为 LDAP */
    private String loginType = LoginMethod.PASSWORD.code();
    /** 凭据是否已通过校验(本地口令或目录 bind);为 true 时后续不再重复校验口令 */
    private boolean credentialVerified;

    /**
     * 创建认证上下文。
     *
     * @param username    提交的登录名,不可为 null
     * @param credentials 提交的凭据,非口令登录可为 null
     * @param request     当前请求,可为 null
     */
    public AuthenticationContext(String username, String credentials, HttpServletRequest request) {
        this.username = username;
        this.credentials = credentials;
        this.request = request;
        this.clientIp = request == null ? null : request.getRemoteAddr();
    }

    /**
     * 提交的登录名。
     *
     * @return 登录名,不为 null
     */
    public String getUsername() {
        return username;
    }

    /**
     * 提交的凭据。
     *
     * @return 明文口令;非口令类登录返回 null
     */
    public String getCredentials() {
        return credentials;
    }

    /**
     * 当前请求。
     *
     * @return 请求对象;无请求上下文时返回 null
     */
    public HttpServletRequest getRequest() {
        return request;
    }

    /**
     * 来源 IP。
     *
     * @return 客户端远程地址;无请求上下文时返回 null
     */
    public String getClientIp() {
        return clientIp;
    }

    /**
     * 已解析的身份域。
     *
     * @return 身份域;前置步骤执行前为 null
     */
    public RealmModel getRealm() {
        return realm;
    }

    /**
     * 写入已解析的身份域。
     *
     * @param realm 身份域
     */
    public void setRealm(RealmModel realm) {
        this.realm = realm;
    }

    /**
     * 域登录策略。
     *
     * @return 登录策略;前置步骤执行前为 null
     */
    public LoginPolicy getLoginPolicy() {
        return loginPolicy;
    }

    /**
     * 写入域登录策略。
     *
     * @param loginPolicy 登录策略
     */
    public void setLoginPolicy(LoginPolicy loginPolicy) {
        this.loginPolicy = loginPolicy;
    }

    /**
     * 已解析的用户。
     *
     * @return 用户;账号不存在或凭据步骤尚未执行时返回 null
     */
    public UserModel getUser() {
        return user;
    }

    /**
     * 写入已解析的用户。
     *
     * @param user 用户
     */
    public void setUser(UserModel user) {
        this.user = user;
    }

    /**
     * 第一因素认证方式。
     *
     * @return LoginMethod 存储码,默认 {@code PASSWORD}
     */
    public String getLoginType() {
        return loginType;
    }

    /**
     * 写入第一因素认证方式。
     *
     * @param loginType LoginMethod 存储码
     */
    public void setLoginType(String loginType) {
        this.loginType = loginType;
    }

    /**
     * 凭据是否已通过校验。
     *
     * @return true 表示口令或目录 bind 已校验通过
     */
    public boolean isCredentialVerified() {
        return credentialVerified;
    }

    /**
     * 标记凭据已通过校验。
     *
     * @param credentialVerified 是否已校验
     */
    public void setCredentialVerified(boolean credentialVerified) {
        this.credentialVerified = credentialVerified;
    }
}
