package club.beenest.iam.authz.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TOTP 动态口令(RFC 6238)纯实现。
 * <p>HMAC-SHA-1 动态截断,6 位码、30 秒步长,兼容 Google Authenticator 等标准认证器;
 * 校验窗口 ±1 步,容忍 30 秒级时钟漂移。一次性码防重放在上层完成。</p>
 */
public final class TotpService {

    /** 默认算法 */
    private static final String DEFAULT_ALGORITHM = "HmacSHA1";
    /** 默认步长(秒) */
    public static final int DEFAULT_PERIOD = 30;
    /** 默认位数 */
    public static final int DEFAULT_DIGITS = 6;
    /** 密钥字节数(160 位,RFC 4226 推荐) */
    private static final int SECRET_BYTES = 20;
    /** 恢复码个数 */
    private static final int RECOVERY_CODE_COUNT = 10;
    /** 恢复码长度(不含分隔符) */
    private static final int RECOVERY_CODE_LENGTH = 10;
    /** 恢复码字母表(去掉易混淆字符) */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpService() {
    }

    /**
     * 生成 Base32 编码的 TOTP 密钥。
     *
     * @return 160 位随机密钥的 Base32 文本
     */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    /**
     * 生成 otpauth provisioning URI,供 authenticator 扫码添加。
     *
     * @param secret  Base32 密钥
     * @param account 账号(通常为登录名)
     * @param issuer  签发方展示名(通常为产品名)
     * @return otpauth://totp/ 形式的扫码地址
     */
    public static String provisioningUri(String secret, String account, String issuer) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DEFAULT_DIGITS + "&period=" + DEFAULT_PERIOD;
    }

    /**
     * 校验动态口令(窗口 ±1)。
     * <p>认证路径上的全函数:任何非法输入(空串/非数字/非法 Base32 密钥)
     * 一律返回 false,绝不向外抛异常。</p>
     *
     * @param secret Base32 密钥
     * @param code   用户输入的 6 位数字码
     * @return 校验是否通过;非法输入一律返回 false
     */
    public static boolean verify(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null) {
            return false;
        }
        String normalized = code.trim();
        if (!normalized.matches("\\d{" + DEFAULT_DIGITS + "}")) {
            return false;
        }
        long counter = System.currentTimeMillis() / 1000L / DEFAULT_PERIOD;
        for (int i = -1; i <= 1; i++) {
            String expected;
            try {
                expected = generateCode(secret, counter + i);
            } catch (RuntimeException e) {
                // 密钥损坏/非法 Base32:失败关闭
                return false;
            }
            // 定长时间比较,防时序侧信道
            if (constantTimeEquals(expected, normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按计数器生成动态口令。
     *
     * @param secret  Base32 密钥
     * @param counter 时间计数器(当前秒数除以步长)
     * @return 6 位数字码
     * @throws IllegalStateException 密钥非法 Base32 或算法不可用
     */
    public static String generateCode(String secret, long counter) {
        try {
            byte[] key = Base32.decode(secret);
            Mac mac = Mac.getInstance(DEFAULT_ALGORITHM);
            mac.init(new SecretKeySpec(key, DEFAULT_ALGORITHM));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int modulus = 1;
            for (int i = 0; i < DEFAULT_DIGITS; i++) {
                modulus *= 10;
            }
            return String.format(Locale.ROOT, "%0" + DEFAULT_DIGITS + "d", binary % modulus);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP 计算失败", e);
        }
    }

    /**
     * 生成一组一次性恢复码。
     *
     * @return 10 个恢复码明文,平台只存哈希
     */
    public static List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            codes.add(generateRecoveryCode());
        }
        return codes;
    }

    /**
     * 生成单个恢复码,形如 XXXXX-XXXXX,字母表去掉易混淆字符。
     *
     * @return 恢复码明文
     */
    public static String generateRecoveryCode() {
        byte[] bytes = new byte[RECOVERY_CODE_LENGTH];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(RECOVERY_CODE_LENGTH + 1);
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            // 字母表去掉 0/O/1/I 等易混淆字符
            char c = RECOVERY_ALPHABET.charAt(Math.floorMod(bytes[i], RECOVERY_ALPHABET.length()));
            sb.append(c);
        }
        return sb.substring(0, 5) + "-" + sb.substring(5);
    }

    /**
     * 定长时间字符串比较,避免逐字符短路带来的时序差异。
     *
     * @param a 比较对象 a
     * @param b 比较对象 b
     * @return 长度相同且逐字符相等时返回 true
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * UTF-8 百分号编码,用于 provisioning URI 的字段拼接。
     *
     * @param value 待编码文本
     * @return 编码结果;编码失败时返回原文
     */
    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
