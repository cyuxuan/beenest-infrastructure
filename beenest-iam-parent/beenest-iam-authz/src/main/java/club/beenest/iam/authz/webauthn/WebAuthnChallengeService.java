package club.beenest.iam.authz.webauthn;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Passkey 挑战票据服务。
 * <p>认证器签名时把挑战算进 clientDataJSON,断言与签发它的会话绑定,旧签名在新会话中
 * 对不上挑战。挑战的三条约束:</p>
 * <ul>
 *   <li>由服务端生成,不接受客户端提供的值;</li>
 *   <li>一次性消费,校验读取后即删除,同一签名不可重复提交;</li>
 *   <li>带有效期,过期后不可再用。</li>
 * </ul>
 * <p>注册与登录分开存:注册发生在已登录状态,按用户索引;登录时平台还不知道
 * 是谁,只能按挑战值反查绑定的账号。</p>
 */
@Service
public class WebAuthnChallengeService {

    /** 未绑定账号时的占位值(无用户名登录) */
    private static final String UNBOUND = "";

    private final RedisUtils redisUtils;
    private final WebAuthnProperties properties;

    /**
     * 构造器注入。
     *
     * @param redisUtils Redis 工具
     * @param properties Passkey 配置(取挑战有效期)
     */
    public WebAuthnChallengeService(RedisUtils redisUtils, WebAuthnProperties properties) {
        this.redisUtils = redisUtils;
        this.properties = properties;
    }

    /**
     * 签发注册挑战。
     * <p>同一用户重复调用会覆盖上一次:注册意图以最新一次为准,
     * 用户连点两次"添加"不该留下两个可用挑战。</p>
     *
     * @param realmKey 身份域标识
     * @param userId   用户主键
     * @return 挑战原文
     */
    public byte[] issueRegistration(String realmKey, Long userId) {
        byte[] challenge = WebAuthnCodec.newChallenge();
        redisUtils.set(registrationKey(realmKey, userId),
                WebAuthnCodec.encode(challenge), ttl());
        return challenge;
    }

    /**
     * 消费注册挑战。
     *
     * @param realmKey 身份域标识
     * @param userId   用户主键
     * @return 挑战原文;未发起过注册或已过期时返回 null
     */
    public byte[] consumeRegistration(String realmKey, Long userId) {
        String stored = redisUtils.getAndDelete(registrationKey(realmKey, userId));
        return WebAuthnCodec.decode(stored);
    }

    /**
     * 签发登录挑战。
     *
     * @param realmKey    身份域标识
     * @param boundUserId 绑定的用户主键;为 null 表示无用户名登录(不绑定账号)
     * @return 挑战原文
     */
    public byte[] issueAuthentication(String realmKey, Long boundUserId) {
        byte[] challenge = WebAuthnCodec.newChallenge();
        String value = boundUserId == null ? UNBOUND : String.valueOf(boundUserId);
        redisUtils.set(authenticationKey(realmKey, challenge), value, ttl());
        return challenge;
    }

    /**
     * 消费登录挑战,返回该挑战绑定的用户主键。
     *
     * @param realmKey  身份域标识
     * @param challenge 挑战原文(Base64URL,由客户端回传)
     * @return 绑定信息;挑战不存在或已过期时返回 null
     */
    public AuthenticationChallenge consumeAuthentication(String realmKey, String challenge) {
        byte[] raw = WebAuthnCodec.decode(challenge);
        if (raw == null) {
            return null;
        }
        String value = redisUtils.getAndDelete(authenticationKey(realmKey, raw));
        if (value == null) {
            return null;
        }
        Long boundUserId = value.isBlank() ? null : Long.valueOf(value);
        return new AuthenticationChallenge(raw, boundUserId);
    }

    /**
     * 登录挑战的消费结果。
     *
     * @param challenge   挑战原文(交给协议库校验)
     * @param boundUserId 绑定的用户主键;null 表示这次挑战未绑定账号
     */
    public record AuthenticationChallenge(byte[] challenge, Long boundUserId) {
    }

    /**
     * 注册挑战键。
     *
     * @param realmKey 身份域标识
     * @param userId   用户主键
     * @return Redis 键,域与用户两级隔离
     */
    private static String registrationKey(String realmKey, Long userId) {
        return String.format(IamRedisKeyConstants.WEBAUTHN_REG_CHALLENGE, realmKey, userId);
    }

    /**
     * 登录挑战键。
     *
     * @param realmKey  身份域标识
     * @param challenge 挑战原文
     * @return Redis 键,登录链路按挑战值反查绑定的账号
     */
    private static String authenticationKey(String realmKey, byte[] challenge) {
        return String.format(IamRedisKeyConstants.WEBAUTHN_AUTH_CHALLENGE, realmKey,
                WebAuthnCodec.encode(challenge));
    }

    /**
     * 挑战有效期,配置值低于 30 秒时按 30 秒生效。
     *
     * @return 票据存活时长
     */
    private Duration ttl() {
        return Duration.ofSeconds(Math.max(30, properties.getChallengeTtlSeconds()));
    }
}
