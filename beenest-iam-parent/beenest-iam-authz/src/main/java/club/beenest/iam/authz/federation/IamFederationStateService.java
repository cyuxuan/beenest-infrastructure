package club.beenest.iam.authz.federation;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 三方登录的 state 管理。
 * <p>state 是本平台与外部 IdP 之间的防伪凭据:发起授权时生成并落 Redis,
 * 回调时一次性消费并比对。缺了它,攻击者可以把自己的外部账号绑到受害者的
 * 本地账号上(登录 CSRF)。</p>
 * <p>state 一次性:GETDEL 原子取出,重放同一回调只会拿到空。</p>
 */
@Component
public class IamFederationStateService {

    /** state 有效期:授权跳转一般在一两分钟内完成,过长会让未使用的 state 长期驻留 */
    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    /** state 随机字节数 */
    private static final int STATE_BYTES = 24;

    private final SecureRandom secureRandom = new SecureRandom();
    private final RedisUtils redisUtils;

    public IamFederationStateService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 签发 state。
     *
     * @param payload 关联信息(域、提供方、发起时的回跳目标)
     * @return state 随机串
     */
    public String issue(String payload) {
        byte[] bytes = new byte[STATE_BYTES];
        secureRandom.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisUtils.set(String.format(IamRedisKeyConstants.FEDERATION_STATE, state), payload, STATE_TTL);
        return state;
    }

    /**
     * 消费 state(一次性)。
     *
     * @param state 回传的 state
     * @return 关联信息;state 不存在或已过期返回 null
     */
    public String consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return redisUtils.getAndDelete(String.format(IamRedisKeyConstants.FEDERATION_STATE, state));
    }
}
