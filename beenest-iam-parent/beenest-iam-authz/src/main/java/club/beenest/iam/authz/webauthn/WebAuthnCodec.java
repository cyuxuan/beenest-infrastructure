package club.beenest.iam.authz.webauthn;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * WebAuthn 报文编解码工具。
 * <p>浏览器侧的二进制字段一律以 Base64URL(无填充)传递,在此统一收口:
 * 解码失败转成 {@code WEBAUTHN_PAYLOAD_INVALID} 业务错误,
 * {@code IllegalArgumentException} 不向外传播;报文不合规属客户端输入问题,
 * 接口层收到的是 4xx 业务错误而非 500。</p>
 */
final class WebAuthnCodec {

    /** 挑战长度:32 字节,与规范建议一致 */
    private static final int CHALLENGE_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private WebAuthnCodec() {
    }

    /**
     * Base64URL 编码(无填充)。
     *
     * @param data 原始字节;为 null 时返回 null
     * @return 编码结果
     */
    static String encode(byte[] data) {
        return data == null ? null : ENCODER.encodeToString(data);
    }

    /**
     * Base64URL 解码。
     *
     * @param text 编码文本
     * @return 原始字节;入参为空时返回 null
     * @throws BusinessException 文本不是合法的 Base64URL
     */
    static byte[] decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DECODER.decode(text.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.WEBAUTHN_PAYLOAD_INVALID);
        }
    }

    /**
     * 生成一次性挑战。
     *
     * @return 32 字节随机值
     */
    static byte[] newChallenge() {
        byte[] challenge = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(challenge);
        return challenge;
    }

    /**
     * 生成用户句柄(无用户名登录时由认证器回传)。
     *
     * @return 16 字节随机值
     */
    static byte[] newUserHandle() {
        byte[] handle = new byte[16];
        RANDOM.nextBytes(handle);
        return handle;
    }
}
