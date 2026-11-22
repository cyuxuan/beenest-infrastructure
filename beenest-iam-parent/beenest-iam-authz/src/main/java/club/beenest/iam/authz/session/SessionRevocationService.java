package club.beenest.iam.authz.session;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 会话吊销标记。
 * <p>登出只删会话快照时,已签发的访问令牌在到期前仍然有效;访问令牌带 {@code sid} 声明,
 * 登出时把该会话的摘要写进吊销集合,验签环节据此拒绝该会话签出的令牌。</p>
 * <p>标记的存续时长覆盖令牌剩余有效期即可,过期后令牌本身也因 {@code exp} 失效。</p>
 */
@Service
public class SessionRevocationService {

    /** 会话已失效但令牌尚未到期时的兜底存续时长 */
    private static final Duration DEFAULT_TTL = Duration.ofHours(12);

    private final RedisUtils redisUtils;

    public SessionRevocationService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 标记会话已吊销。
     *
     * @param sid 会话标识;为空时不做任何处理
     * @param ttl 标记存续时长;为空或非正数时取兜底值
     */
    public void revoke(String sid, Duration ttl) {
        String sidHash = SessionIdHash.of(sid);
        if (sidHash == null) {
            return;
        }
        Duration effective = ttl == null || ttl.isZero() || ttl.isNegative() ? DEFAULT_TTL : ttl;
        redisUtils.set(String.format(IamRedisKeyConstants.TOKEN_BLOCKLIST_SID, sidHash), "1", effective);
    }

    /**
     * 判断会话是否已被吊销。
     *
     * @param sid 会话标识;为空时视为未吊销
     * @return true 表示该会话已登出或已被顶下线
     */
    public boolean isRevoked(String sid) {
        String sidHash = SessionIdHash.of(sid);
        if (sidHash == null) {
            return false;
        }
        return redisUtils.hasKey(String.format(IamRedisKeyConstants.TOKEN_BLOCKLIST_SID, sidHash));
    }

    /**
     * 判断会话摘要是否已被吊销。
     *
     * @param sidHash 会话标识摘要({@link SessionIdHash});为空时视为未吊销
     * @return true 表示该会话已登出
     */
    public boolean isRevokedHash(String sidHash) {
        if (sidHash == null || sidHash.isBlank()) {
            return false;
        }
        return redisUtils.hasKey(String.format(IamRedisKeyConstants.TOKEN_BLOCKLIST_SID, sidHash));
    }
}
