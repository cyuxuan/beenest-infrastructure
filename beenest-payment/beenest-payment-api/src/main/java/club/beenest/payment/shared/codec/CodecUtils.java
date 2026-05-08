package club.beenest.payment.shared.codec;

/**
 * 编解码工具类。
 * <p>
 * 提供高性能的字节数组转十六进制字符串方法，
 * 使用静态查找表替代 {@code String.format}，性能提升 10-100 倍。
 */
public final class CodecUtils {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private CodecUtils() {
    }

    /**
     * 将字节数组转换为小写十六进制字符串。
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX_CHARS[v >>> 4];
            chars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(chars);
    }
}
