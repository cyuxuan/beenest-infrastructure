package club.beenest.iam.authz.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 准入判定测试。
 * <p>判定规则是整个特性的核心,每条分支都要能钉住:
 * 尤其"登录方式未知"(存量会话)必须按不满足处理——
 * 读不到方式就放行,等于给所有升级前的在线会话开了后门。</p>
 */
class AdmissionDecisionTest {

    @Test
    @DisplayName("白名单为空且不强制 MFA 时放行")
    void allowsWhenUnrestricted() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                ClientAdmissionPolicy.unrestricted(), "PASSWORD", false);

        assertTrue(decision.allowed());
        assertFalse(decision.requiresMfa());
    }

    @Test
    @DisplayName("会话方式命中白名单时放行")
    void allowsMatchingMethod() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.SMS, LoginMethod.PASSWORD), false),
                "SMS", false);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("会话方式不在白名单时要求补验")
    void challengesOnMismatch() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false),
                "PASSWORD", false);

        assertFalse(decision.allowed());
        assertEquals(Set.of("SMS"), decision.allowedLoginTypes());
    }

    @Test
    @DisplayName("存量会话的登录方式未知时要求补验,不因读不到方式而放行")
    void challengesWhenLoginTypeUnknown() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.PASSWORD), false),
                null, false);

        assertFalse(decision.allowed());
        assertEquals(Set.of("PASSWORD"), decision.allowedLoginTypes());
    }

    @Test
    @DisplayName("客户端强制 MFA 且会话未完成二步验证时要求 MFA 补验")
    void challengesMfa() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.PASSWORD), true),
                "PASSWORD", false);

        assertFalse(decision.allowed());
        assertTrue(decision.requiresMfa());
    }

    @Test
    @DisplayName("会话已完成二步验证时不再要求 MFA")
    void allowsWhenMfaAlreadyDone() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.PASSWORD), true),
                "PASSWORD", true);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("配置损坏态直接拒绝,不退回放行")
    void deniesWhenPolicyBroken() {
        // denyAll 之下白名单被归一为空。若判定先看"白名单为空就放行",
        // 这条本该生效的限制会被静默解除——必须最先判 denyAll
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                ClientAdmissionPolicy.denyAllPolicy(), "PASSWORD", true);

        assertFalse(decision.allowed());
        assertFalse(decision.requiresMfa());
        assertTrue(decision.allowedLoginTypes().isEmpty());
    }
}
