package club.beenest.iam.authz.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 联邦 state 载荷格式测试。
 * <p>载荷从三段扩到四段:{@code realmKey|alias|challengeToken|target}。
 * 联邦回跳时浏览器未必带得回会话 Cookie(SAML 走跨站 POST),挑战令牌是那时唯一的定位线索。</p>
 * <p>令牌放在**第三段**而不是末段:目标地址是唯一可能含分隔符的部分,
 * 让它占据最后一段(split 有上限),地址里出现 "|" 也不会把令牌切碎。</p>
 * <p>老格式(三段)必须仍能解析,否则上线即打断存量三方登录。</p>
 */
class FederationChallengeContextTest {

    @Test
    @DisplayName("四段载荷往返不丢挑战令牌,目标地址含分隔符也不受影响")
    void roundTripsChallengeToken() {
        String encoded = new FederationStatePayload("default", "wx",
                "tok-123", "/portal?tab=a|b").encode();

        FederationStatePayload decoded = FederationStatePayload.decode(encoded);

        assertEquals("default", decoded.realmKey());
        assertEquals("wx", decoded.alias());
        assertEquals("/portal?tab=a|b", decoded.target());
        assertEquals("tok-123", decoded.challengeToken());
    }

    @Test
    @DisplayName("三段老载荷仍可解析,挑战令牌为空")
    void parsesLegacyPayload() {
        FederationStatePayload decoded =
                FederationStatePayload.decode("default|wx|https://app.example.com/cb");

        assertEquals("https://app.example.com/cb", decoded.target());
        assertNull(decoded.challengeToken());
    }

    @Test
    @DisplayName("第三段为空串表示无挑战,解析为 null 而不是空串")
    void emptyTokenPartMeansNoChallenge() {
        // 拼接时无挑战会留一个空段;若这里返回 "" 而不是 null,
        // 回跳侧会拿空令牌去查票据,永远查不到
        FederationStatePayload decoded = FederationStatePayload.decode("default|wx||/portal");

        assertEquals("/portal", decoded.target());
        assertNull(decoded.challengeToken());
    }

    @Test
    @DisplayName("段数不足时拒绝解析")
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> FederationStatePayload.decode("default|wx"));
    }
}
