package club.beenest.iam.authz.mfa;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * Base32 编解码(RFC 4648,字母表 A-Z2-7,不含填充)。
 * <p>TOTP 密钥的存储与传输格式,与 Google Authenticator 等标准实现互通;
 * 20 字节(160 位)密钥编码后为 32 字符。</p>
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    /**
     * RFC 4648 编码(不含 '=' 填充)。
     *
     * @param data 原始字节
     * @return Base32 字符串;空输入返回空串
     */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        // 必须用 long 累加:5 字节块共 40 位,int 会溢出导致高位丢失
        long buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(ALPHABET.charAt((int) ((buffer >>> bits) & 0x1F)));
            }
        }
        if (bits > 0) {
            // 尾部不足 5 位的组,左移补零后取 5 位
            sb.append(ALPHABET.charAt((int) ((buffer << (5 - bits)) & 0x1F)));
        }
        return sb.toString();
    }

    /**
     * RFC 4648 解码(容忍小写与 '=' 填充)。
     *
     * @param encoded Base32 字符串
     * @return 原始字节
     * @throws IllegalArgumentException 含非法字符时
     */
    public static byte[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new byte[0];
        }
        String clean = encoded.toUpperCase(Locale.ROOT).replace("=", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream((clean.length() * 5) / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("非法 Base32 字符: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
