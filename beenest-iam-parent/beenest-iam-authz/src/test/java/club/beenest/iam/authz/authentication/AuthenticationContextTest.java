package club.beenest.iam.authz.authentication;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.authentication.step.AuthenticationContext;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.SubjectMode;
import club.beenest.iam.core.model.UserModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认证上下文的状态流转。
 * <p>上下文是步骤之间唯一的传值通道:字段默认值错了会让后续步骤读到脏状态
 * (例如登录方式默认成 LDAP,准入白名单就会误判),因此这里断言的是默认值与写入语义。</p>
 */
class AuthenticationContextTest {

    @Test
    @DisplayName("登录方式默认是 PASSWORD:未显式改写时不能被误当成目录认证")
    void loginTypeDefaultsToPassword() {
        AuthenticationContext context = new AuthenticationContext("alice", "secret", null);

        assertEquals(LoginMethod.PASSWORD.code(), context.getLoginType());
    }

    @Test
    @DisplayName("构造时取出来源 IP:无请求上下文时为 null,不能抛空指针")
    void clientIpTakenFromRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.7");

        AuthenticationContext withRequest = new AuthenticationContext("alice", "secret", request);
        AuthenticationContext withoutRequest = new AuthenticationContext("alice", "secret", null);

        assertEquals("10.0.0.7", withRequest.getClientIp());
        assertNull(withoutRequest.getClientIp());
    }

    @Test
    @DisplayName("凭据初始未校验:凭据步骤执行前不能被视为已通过")
    void credentialNotVerifiedInitially() {
        AuthenticationContext context = new AuthenticationContext("alice", "secret", null);

        assertFalse(context.isCredentialVerified());
        assertNull(context.getUser(), "凭据步骤执行前用户为空");
        assertNull(context.getRealm(), "域解析步骤执行前身份域为空");
        assertNull(context.getLoginPolicy(), "域解析步骤执行前登录策略为空");
    }

    @Test
    @DisplayName("前置步骤写入的域与策略可被后续步骤读到")
    void realmAndPolicyArePropagated() {
        RealmModel realm = new RealmModel(1L, "default", "默认域", true, true,
                300L, 3600L, 1800L, 28800L, SubjectMode.LOGIN_NAME, null);
        AuthenticationContext context = new AuthenticationContext("alice", "secret", null);

        context.setRealm(realm);

        assertEquals(realm, context.getRealm());
        assertEquals("default", context.getRealm().realmKey());
    }

    @Test
    @DisplayName("凭据步骤写入的用户与登录方式可被状态校验与二步验证门禁读到")
    void userAndLoginTypeArePropagated() {
        UserModel user = mock(UserModel.class);
        when(user.id()).thenReturn(42L);
        when(user.username()).thenReturn("alice");
        AuthenticationContext context = new AuthenticationContext("alice", "secret", null);

        context.setUser(user);
        context.setLoginType(LoginMethod.LDAP.code());
        context.setCredentialVerified(true);

        assertEquals(user, context.getUser());
        assertEquals(LoginMethod.LDAP.code(), context.getLoginType());
        assertTrue(context.isCredentialVerified());
    }

    @Test
    @DisplayName("凭据可为 null:非口令类登录不携带明文口令")
    void credentialsMayBeNull() {
        AuthenticationContext context = new AuthenticationContext("alice", null, null);

        assertNull(context.getCredentials());
        assertEquals("alice", context.getUsername());
    }
}
