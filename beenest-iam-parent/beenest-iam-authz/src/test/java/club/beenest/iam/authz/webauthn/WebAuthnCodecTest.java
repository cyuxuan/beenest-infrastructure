package club.beenest.iam.authz.webauthn;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebAuthn 报文编解码测试。
 * <p>重点在两点:无填充的 Base64URL 能被浏览器原样接受;
 * 非法报文转成业务错误而不是 500——否则客户端提交错数据会被记成服务端故障。</p>
 */
class WebAuthnCodecTest {

    @Test
    @DisplayName("编码结果不带填充,且可原样解码")
    void roundTripWithoutPadding() {
        byte[] raw = WebAuthnCodec.newChallenge();
        String encoded = WebAuthnCodec.encode(raw);

        assertTrue(encoded.indexOf('=') < 0, "Base64URL 不应带填充");
        assertArrayEquals(raw, WebAuthnCodec.decode(encoded));
    }

    @Test
    @DisplayName("挑战长度为 32 字节,两次生成不相同")
    void challengesAreRandom() {
        byte[] first = WebAuthnCodec.newChallenge();
        byte[] second = WebAuthnCodec.newChallenge();

        assertEquals(32, first.length);
        assertEquals(32, second.length);
        assertTrue(!java.util.Arrays.equals(first, second), "两次挑战不应相同");
    }

    @Test
    @DisplayName("用户句柄长度为 16 字节")
    void userHandleLength() {
        assertEquals(16, WebAuthnCodec.newUserHandle().length);
    }

    @Test
    @DisplayName("空输入返回 null,由调用方决定语义")
    void blankInputReturnsNull() {
        assertNull(WebAuthnCodec.encode(null));
        assertNull(WebAuthnCodec.decode(null));
        assertNull(WebAuthnCodec.decode("   "));
    }

    @Test
    @DisplayName("非法编码转成业务错误,不是 500")
    void invalidInputThrowsBusinessException() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> WebAuthnCodec.decode("这不是 base64!!"));
        assertEquals(ErrorCode.WEBAUTHN_PAYLOAD_INVALID.getCode(), error.getCode());
    }

    @Test
    @DisplayName("兼容带填充的标准 Base64URL")
    void acceptsPaddedInput() {
        byte[] raw = {1, 2, 3, 4};
        String padded = Base64.getUrlEncoder().encodeToString(raw);

        assertArrayEquals(raw, WebAuthnCodec.decode(padded));
    }
}
