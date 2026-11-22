package club.beenest.iam.authz.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 会话标识摘要。
 * <p>OIDC 的 {@code sid} 声明存放会话 id 的 SHA-256 摘要(Base64URL,无填充):
 * 摘要可在应用侧定位下线的会话,且无法反推出会话 id,令牌外泄不连带暴露会话票据。</p>
 * <p>算法必须与授权服务器实现保持一致(参见 Spring Authorization Server 的
 * {@code createHash}),保证同一次登出广播的 sid 匹配。</p>
 */
public final class SessionIdHash {

    private SessionIdHash() {
    }

    /**
     * 计算会话标识摘要。
     *
     * @param sessionId 容器会话标识
     * @return Base64URL(无填充)编码的 SHA-256 摘要;入参为空时返回 null
     */
    public static String of(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sessionId.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法,缺失说明运行环境已损坏
            throw new IllegalStateException("无法计算会话标识摘要", e);
        }
    }
}
