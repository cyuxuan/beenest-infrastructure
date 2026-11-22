package club.beenest.iam.authz.webauthn;

import club.beenest.iam.persistence.entity.WebAuthnCredential;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebAuthn 选项构造测试。
 * <p>这些字段名由规范固定,拼错时浏览器只报"参数无效",排查成本很高;
 * 用例把关键字段的形态钉住,避免后续改动悄悄破坏前端调用。</p>
 */
class WebAuthnOptionsFactoryTest {

    private final WebAuthnOptionsFactory factory = new WebAuthnOptionsFactory(properties());

    @Test
    @DisplayName("注册选项包含挑战、依赖方、用户与算法参数")
    void registrationOptionsCarryRequiredFields() {
        byte[] challenge = WebAuthnCodec.newChallenge();
        byte[] userHandle = WebAuthnCodec.newUserHandle();

        Map<String, Object> options = factory.registrationOptions(
                challenge, userHandle, "alice", null, List.of());

        assertEquals(WebAuthnCodec.encode(challenge), options.get("challenge"));
        assertEquals("id.example.com", ((Map<?, ?>) options.get("rp")).get("id"));
        Map<?, ?> user = (Map<?, ?>) options.get("user");
        assertEquals(WebAuthnCodec.encode(userHandle), user.get("id"));
        // 展示名缺失时回落登录名:认证器列表里不能出现空白条目
        assertEquals("alice", user.get("displayName"));
        assertNotNull(options.get("pubKeyCredParams"));
        assertEquals("required",
                ((Map<?, ?>) options.get("authenticatorSelection")).get("userVerification"));
    }

    @Test
    @DisplayName("已绑定凭证进入 excludeCredentials,避免同一把钥匙重复绑定")
    void registrationExcludesExistingCredentials() {
        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setCredentialId("abc123");
        credential.setTransports("internal,hybrid");

        Map<String, Object> options = factory.registrationOptions(
                WebAuthnCodec.newChallenge(), WebAuthnCodec.newUserHandle(),
                "alice", "Alice", List.of(credential));

        List<?> excluded = (List<?>) options.get("excludeCredentials");
        assertEquals(1, excluded.size());
        Map<?, ?> descriptor = (Map<?, ?>) excluded.get(0);
        assertEquals("abc123", descriptor.get("id"));
        assertEquals(List.of("internal", "hybrid"), descriptor.get("transports"));
    }

    @Test
    @DisplayName("无凭证时不下发 allowCredentials:那是无用户名登录的信号")
    void authenticationOmitsAllowCredentialsWhenEmpty() {
        Map<String, Object> options = factory.authenticationOptions(WebAuthnCodec.newChallenge(), List.of());

        assertEquals("id.example.com", options.get("rpId"));
        assertFalse(options.containsKey("allowCredentials"));
    }

    @Test
    @DisplayName("有凭证时下发 allowCredentials")
    void authenticationCarriesAllowCredentials() {
        WebAuthnCredential credential = new WebAuthnCredential();
        credential.setCredentialId("abc123");

        Map<String, Object> options = factory.authenticationOptions(
                WebAuthnCodec.newChallenge(), List.of(credential));

        List<?> allowed = (List<?>) options.get("allowCredentials");
        assertEquals(1, allowed.size());
        assertEquals("abc123", ((Map<?, ?>) allowed.get(0)).get("id"));
    }

    @Test
    @DisplayName("关闭无用户名登录时不再要求常驻密钥")
    void residentKeyDiscouragedWhenUsernamelessDisabled() {
        WebAuthnProperties noUsernameless = properties();
        noUsernameless.setAllowUsernameless(false);
        WebAuthnOptionsFactory strictFactory = new WebAuthnOptionsFactory(noUsernameless);

        Map<String, Object> options = strictFactory.registrationOptions(
                WebAuthnCodec.newChallenge(), WebAuthnCodec.newUserHandle(),
                "alice", null, List.of());

        assertEquals("discouraged",
                ((Map<?, ?>) options.get("authenticatorSelection")).get("residentKey"));
    }

    @Test
    @DisplayName("关闭用户验证时降级为 preferred")
    void userVerificationDowngraded() {
        WebAuthnProperties noUv = properties();
        noUv.setUserVerificationRequired(false);
        WebAuthnOptionsFactory looseFactory = new WebAuthnOptionsFactory(noUv);

        Map<String, Object> options = looseFactory.authenticationOptions(
                WebAuthnCodec.newChallenge(), List.of());

        assertEquals("preferred", options.get("userVerification"));
    }

    @Test
    @DisplayName("传输方式拆分:容忍空白与空串")
    void splitTransports() {
        assertTrue(WebAuthnOptionsFactory.splitTransports(null).isEmpty());
        assertTrue(WebAuthnOptionsFactory.splitTransports("  ").isEmpty());
        assertEquals(List.of("usb", "nfc"), WebAuthnOptionsFactory.splitTransports("usb, nfc,"));
    }

    private static WebAuthnProperties properties() {
        WebAuthnProperties properties = new WebAuthnProperties();
        properties.setEnabled(true);
        properties.setRpId("id.example.com");
        properties.setOrigin("https://id.example.com");
        return properties;
    }
}
