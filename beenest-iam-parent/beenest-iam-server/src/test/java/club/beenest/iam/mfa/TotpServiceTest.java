package club.beenest.iam.mfa;

import club.beenest.iam.authz.mfa.Base32;
import club.beenest.iam.authz.mfa.TotpService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOTP 服务单元测试。
 * <p>使用 RFC 6238 附录 B 官方测试向量(SHA-1,30 秒步长):
 * 密钥为 ASCII "12345678901234567890",Base32 编码后参与计算;
 * 官方向量为 8 位,本实现取 6 位(取官方值末 6 位)。
 * 所有向量均经独立参考实现(Python hmac)复核,非凭记忆抄录。</p>
 */
class TotpServiceTest {

    /** RFC 6238 测试密钥(Base32 of "12345678901234567890") */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("RFC 6238 向量:6 位动态口令逐时隙一致")
    void rfc6238Vectors() {
        // 计数器 = floor(T / 30);官方 8 位值末 6 位即本实现输出
        assertEquals("287082", TotpService.generateCode(RFC_SECRET, 1));            // T=59
        assertEquals("081804", TotpService.generateCode(RFC_SECRET, 0x23523EC));   // T=1111111109
        assertEquals("050471", TotpService.generateCode(RFC_SECRET, 0x23523ED));   // T=1111111111
        assertEquals("005924", TotpService.generateCode(RFC_SECRET, 41152263));    // T=1234567890
        assertEquals("279037", TotpService.generateCode(RFC_SECRET, 66666666));    // T=2000000000
        assertEquals("353130", TotpService.generateCode(RFC_SECRET, 666666666L));  // T=20000000000
    }

    @Test
    @DisplayName("Base32 编解码往返一致")
    void base32RoundTrip() {
        String secret = TotpService.generateSecret();
        // 生成的密钥应是 32 字符标准 Base32 字母表(RFC 4648,无填充)
        assertTrue(secret.matches("[A-Z2-7]{32}"), "密钥格式不符: " + secret);
        // 生成的密钥应可被 verify 接受(用当前时隙码自校验)
        String code = TotpService.generateCode(secret, System.currentTimeMillis() / 1000 / 30);
        assertTrue(TotpService.verify(secret, code));
    }

    @Test
    @DisplayName("Base32 编码符合 RFC 4648 已知答案")
    void base32KnownAnswers() {
        byte[] ascii = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        // 已知答案:历史上 int 累加器溢出会丢失高位(产出 GAZDGNBVGA3TQOJQ 之类错串)
        assertEquals(RFC_SECRET, Base32.encode(ascii));
        assertArrayEquals(ascii, Base32.decode(RFC_SECRET));
        // 各种长度(1/2/3/4/5 字节边界)往返一致
        for (int len = 1; len <= 5; len++) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) {
                data[i] = (byte) (i * 37 + 11);
            }
            assertArrayEquals(data, Base32.decode(Base32.encode(data)), "长度 " + len + " 往返失败");
        }
        // 空输入与非法字符
        assertEquals("", Base32.encode(new byte[0]));
        assertEquals("", Base32.encode(null));
        assertArrayEquals(new byte[0], Base32.decode(""));
        assertArrayEquals(new byte[0], Base32.decode(null));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("AB!CD"));
        // 容忍小写与填充符
        assertArrayEquals(ascii, Base32.decode(RFC_SECRET.toLowerCase()));
        assertArrayEquals(ascii, Base32.decode(RFC_SECRET + "======"));
    }

    @Test
    @DisplayName("校验容忍 ±1 时隙漂移,拒绝超窗码")
    void verifyWindowDrift() {
        long now = System.currentTimeMillis() / 1000 / 30;
        assertTrue(TotpService.verify(RFC_SECRET, TotpService.generateCode(RFC_SECRET, now)));
        assertTrue(TotpService.verify(RFC_SECRET, TotpService.generateCode(RFC_SECRET, now - 1)));
        assertTrue(TotpService.verify(RFC_SECRET, TotpService.generateCode(RFC_SECRET, now + 1)));
        assertFalse(TotpService.verify(RFC_SECRET, TotpService.generateCode(RFC_SECRET, now - 3)));
    }

    @Test
    @DisplayName("非法输入一律拒绝")
    void invalidInputsRejected() {
        assertFalse(TotpService.verify(RFC_SECRET, null));
        assertFalse(TotpService.verify(RFC_SECRET, ""));
        assertFalse(TotpService.verify(RFC_SECRET, "12345"));   // 位数不足
        assertFalse(TotpService.verify(RFC_SECRET, "12345a"));  // 含非数字
        assertFalse(TotpService.verify(null, "123456"));
        assertFalse(TotpService.verify("", "123456"));
        // 非法 Base32 密钥不应抛出未受检异常之外的行为(verify 返回 false)
        assertFalse(TotpService.verify("!!!", "123456"));
    }

    @Test
    @DisplayName("provisioning URI 符合 otpauth 格式")
    void provisioningUriFormat() {
        String uri = TotpService.provisioningUri(RFC_SECRET, "zhangsan", "beenest-iam");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=" + RFC_SECRET));
        assertTrue(uri.contains("issuer=beenest-iam"));
        assertTrue(uri.contains("algorithm=SHA1"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
    }

    @Test
    @DisplayName("恢复码:10 个、格式统一、互不重复")
    void recoveryCodes() {
        List<String> codes = TotpService.generateRecoveryCodes();
        assertEquals(10, codes.size());
        assertEquals(10, codes.stream().distinct().count());
        for (String code : codes) {
            assertTrue(code.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"), "格式不符: " + code);
        }
    }

    @Test
    @DisplayName("每次生成的密钥互不相同")
    void secretsAreUnique() {
        assertNotEquals(TotpService.generateSecret(), TotpService.generateSecret());
    }
}
