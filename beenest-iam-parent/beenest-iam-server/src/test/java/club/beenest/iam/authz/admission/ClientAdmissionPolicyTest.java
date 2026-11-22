package club.beenest.iam.authz.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端准入策略判定测试。
 * <p>白名单为空表示"不限制",与"白名单为空集合"是两件事:
 * 前者放行一切,后者放行无物。判反了就是全站拒绝登录。</p>
 */
class ClientAdmissionPolicyTest {

    @Test
    @DisplayName("白名单为空表示不限制,任何方式都放行")
    void unrestrictedAdmitsEverything() {
        ClientAdmissionPolicy policy = ClientAdmissionPolicy.unrestricted();

        assertTrue(policy.admits("PASSWORD"));
        assertTrue(policy.admits("SMS"));
        assertTrue(policy.admits(null));
    }

    @Test
    @DisplayName("白名单命中则放行")
    void admitsListedMethod() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(
                Set.of(LoginMethod.SMS, LoginMethod.PASSWORD), false);

        assertTrue(policy.admits("SMS"));
        assertTrue(policy.admits("PASSWORD"));
        assertFalse(policy.admits("WECHAT"));
    }

    @Test
    @DisplayName("登录方式未知时按不满足处理")
    void unknownMethodNotAdmitted() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(
                Set.of(LoginMethod.PASSWORD), false);

        assertFalse(policy.admits(null));
        assertFalse(policy.admits("UNKNOWN"));
    }

    @Test
    @DisplayName("配置损坏态拒绝一切,不降级为不限制")
    void denyAllAdmitsNothing() {
        // "空集合 = 不限制"与"拒绝一切"是相反的两件事,不能混
        ClientAdmissionPolicy policy = ClientAdmissionPolicy.denyAllPolicy();

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
        assertFalse(policy.admits("SMS"));
        assertFalse(policy.admits(null));
    }

    @Test
    @DisplayName("denyAll 策略下白名单被归一为空,不留自相矛盾的配置")
    void denyAllNormalizesNonEmptyWhitelist() {
        // denyAll 之下白名单没有意义。若构造时不归一,就会出现"既拒绝一切、
        // 又存着一份白名单"的自相矛盾状态;将来有人按白名单反推配置会读出错觉
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(
                Set.of(LoginMethod.SMS), false, true);

        assertTrue(policy.denyAll());
        assertTrue(policy.allowedMethodCodes().isEmpty());
        assertFalse(policy.admits("SMS"));
    }
}
