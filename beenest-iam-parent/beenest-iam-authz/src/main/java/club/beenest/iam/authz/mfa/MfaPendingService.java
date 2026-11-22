package club.beenest.iam.authz.mfa;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * MFA 待验证票据服务。
 * <p>口令通过后、第二因子完成前的中间态:Redis 保存一次性票据(有效期 5 分钟),
 * 用户主键不出现在客户端;票据值为 {@code userId:sid:loginType} 三段。</p>
 */
@Service
public class MfaPendingService {

    /** 票据有效期(秒):需覆盖用户查看 authenticator 并输入的时间 */
    private static final Duration PENDING_TTL = Duration.ofMinutes(5);

    /** 票据值的分隔符(用户主键与认证方式之间)。 */
    private static final String SEPARATOR = ":";

    private final RedisUtils redisUtils;

    public MfaPendingService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 签发待验证票据。
     *
     * @param realmKey  身份域键
     * @param userId    已通过口令认证的用户主键
     * @param loginType 第一因素认证方式(LoginMethod 存储码)
     * @return 一次性票据
     */
    public String issue(String realmKey, Long userId, String loginType) {
        return issueForSession(realmKey, userId, null, loginType);
    }

    /**
     * 签发待验证票据,并把会话标识编入。
     * <p>会话级补验要把票据绑回具体会话,票据统一为三段;登录流程还没有会话,sid 段留空。</p>
     *
     * @param realmKey  身份域键
     * @param userId    已通过第一因素认证的用户主键
     * @param sid       会话标识;登录流程传 null
     * @param loginType 第一因素认证方式(LoginMethod 存储码);未知传 null
     * @return 一次性票据
     */
    public String issueForSession(String realmKey, Long userId, String sid, String loginType) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String value = userId
                + SEPARATOR + (sid == null ? "" : sid)
                + SEPARATOR + (loginType == null ? "" : loginType);
        redisUtils.set(String.format(IamRedisKeyConstants.MFA_PENDING, realmKey, token),
                value, PENDING_TTL);
        return token;
    }

    /**
     * 消费票据并返回用户主键;票据不存在或已过期返回 null。
     *
     * @param realmKey 身份域键
     * @param token    一次性票据
     * @return 用户主键;票据不存在或已过期返回 null
     */
    public Long consume(String realmKey, String token) {
        String value = consumeRaw(realmKey, token);
        if (value == null) {
            return null;
        }
        int index = value.indexOf(SEPARATOR);
        try {
            return Long.valueOf(index < 0 ? value : value.substring(0, index));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 消费票据并返回用户主键与认证方式。
     *
     * @param realmKey 身份域键
     * @param token    一次性票据
     * @return 票据内容;票据不存在或已过期返回 null
     */
    public MfaPending consumeTicket(String realmKey, String token) {
        String value = consumeRaw(realmKey, token);
        if (value == null) {
            return null;
        }
        // 统一三段格式:userId:sid:loginType(登录流程的 sid 段为空)
        String[] parts = value.split("\\" + SEPARATOR, 3);
        if (parts.length < 3) {
            return null;
        }
        try {
            // 登录流程不携带 sid,只取第 1 段与第 3 段;空串归一为 null
            return new MfaPending(Long.valueOf(parts[0]), parts[2].isEmpty() ? null : parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 消费会话级 MFA 票据。
     *
     * @param realmKey 身份域键
     * @param token    一次性票据
     * @return 票据内容;不存在或已过期返回 null
     */
    public MfaSessionPending consumeSession(String realmKey, String token) {
        String value = consumeRaw(realmKey, token);
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\" + SEPARATOR, 3);
        // 只认三段:两段值的第二段会被误当成 sid
        if (parts.length < 3) {
            return null;
        }
        try {
            // 与 consumeTicket 同一口径:空串归一为 null
            String loginType = parts[2].isEmpty() ? null : parts[2];
            return new MfaSessionPending(Long.valueOf(parts[0]), parts[1].isEmpty() ? null : parts[1], loginType);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 取票据原始串并删除票据(一次性)。
     *
     * @param realmKey 身份域键
     * @param token    一次性票据
     * @return 票据原始值;不存在或已过期返回 null
     */
    private String consumeRaw(String realmKey, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = String.format(IamRedisKeyConstants.MFA_PENDING, realmKey, token);
        String value = redisUtils.get(key);
        if (value == null) {
            return null;
        }
        redisUtils.delete(key);
        return value;
    }
}
