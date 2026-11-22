package club.beenest.iam.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机密配置键判定测试。
 * <p>判定规则若只认死名单,新扩展包引入带密钥的配置项时就必须回核心加一条,
 * 热插拔就成了空话。这里钉住"以 Secret 结尾即机密"的约定。</p>
 */
class IamIdpConfigKeysTest {

    @Test
    @DisplayName("以 Secret 结尾的配置键一律按机密处理")
    void secretSuffixIsSensitive() {
        assertTrue(IamIdpConfigKeys.isSensitive("clientSecret"));
        assertTrue(IamIdpConfigKeys.isSensitive("appSecret"));
        assertTrue(IamIdpConfigKeys.isSensitive("apiSecret"));
        assertTrue(IamIdpConfigKeys.isSensitive("appsecret"));
    }

    @Test
    @DisplayName("普通配置键不算机密")
    void ordinaryKeysAreNotSensitive() {
        assertFalse(IamIdpConfigKeys.isSensitive("clientId"));
        assertFalse(IamIdpConfigKeys.isSensitive("authorizationUrl"));
        assertFalse(IamIdpConfigKeys.isSensitive(null));
        assertFalse(IamIdpConfigKeys.isSensitive(""));
        assertFalse(IamIdpConfigKeys.isSensitive("   "));
    }
}
