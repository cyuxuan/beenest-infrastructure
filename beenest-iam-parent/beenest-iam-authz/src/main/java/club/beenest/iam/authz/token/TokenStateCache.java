package club.beenest.iam.authz.token;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.provider.UserProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户令牌状态缓存:缓存账号状态与令牌版本,供验签路径免查库。
 * <p>缓存键为 {@code iam:tokenstate:{userId}},值形如 {@code status:tokenVersion};
 * TTL 10 分钟仅作兜底,账号禁用、删除、全端下线等变更需调用 {@link #invalidate(Long)} 立即生效。</p>
 * <p>账号不存在时状态与版本均按 -1 缓存。</p>
 */
@Component
public class TokenStateCache {

    /** 缓存有效期:仅作为兜底,正常路径依赖显式失效 */
    private static final Duration TTL = Duration.ofMinutes(10);
    /** 状态未知(账号不存在)时使用的状态值 */
    private static final int STATUS_UNKNOWN = -1;
    /** 账号状态:正常 */
    private static final int STATUS_NORMAL = 1;

    private final UserProvider userProvider;
    private final RedisUtils redisUtils;

    public TokenStateCache(UserProvider userProvider, RedisUtils redisUtils) {
        this.userProvider = userProvider;
        this.redisUtils = redisUtils;
    }

    /**
     * 读取账号状态与令牌版本。
     *
     * @param userId 用户主键
     * @return 账号状态;账号不存在返回 {@link #STATUS_UNKNOWN}
     */
    public int status(Long userId) {
        return load(userId).status;
    }

    /**
     * 读取令牌版本。
     *
     * @param userId 用户主键
     * @return 当前令牌版本;账号不存在返回 -1
     */
    public int tokenVersion(Long userId) {
        return load(userId).tokenVersion;
    }

    /**
     * 账号状态是否允许继续访问。
     *
     * @param status {@link #status(Long)} 返回的账号状态
     * @return true 表示状态为正常(1);禁用、锁定、删除、账号不存在均为 false
     */
    public boolean isNormal(int status) {
        return status == STATUS_NORMAL;
    }

    /**
     * 失效指定用户的状态缓存,下次读取回源数据库。
     * <p>账号状态变更、令牌版本抬高(全端下线)后必须调用。</p>
     *
     * @param userId 用户主键;为 null 时不做任何事
     */
    public void invalidate(Long userId) {
        if (userId != null) {
            redisUtils.delete(String.format(IamRedisKeyConstants.TOKEN_STATE, userId));
        }
    }

    /**
     * 读取状态快照:命中缓存直接返回,未命中回源数据库并写缓存。
     *
     * @param userId 用户主键
     * @return 账号状态与令牌版本;账号不存在时为 {@code (-1, -1)}
     */
    private TokenState load(Long userId) {
        String key = String.format(IamRedisKeyConstants.TOKEN_STATE, userId);
        String cached = redisUtils.get(key);
        if (cached != null) {
            return parse(cached);
        }
        UserModel user = userProvider.findById(userId).orElse(null);
        TokenState state = user == null
                ? new TokenState(STATUS_UNKNOWN, -1)
                : new TokenState(user.status(), user.tokenVersion());
        redisUtils.set(key, state.status + ":" + state.tokenVersion, TTL);
        return state;
    }

    /**
     * 解析缓存值 {@code status:tokenVersion}。
     *
     * @param cached 缓存字符串
     * @return 解析结果;缺少分隔符或数字非法时返回 {@code (-1, -1)},按状态未知处理
     */
    private static TokenState parse(String cached) {
        int split = cached.indexOf(':');
        if (split < 0) {
            return new TokenState(STATUS_UNKNOWN, -1);
        }
        try {
            return new TokenState(Integer.parseInt(cached.substring(0, split)),
                    Integer.parseInt(cached.substring(split + 1)));
        } catch (NumberFormatException e) {
            return new TokenState(STATUS_UNKNOWN, -1);
        }
    }

    /**
     * 令牌状态快照。
     *
     * @param status       令牌状态,取值见本类状态常量,缓存缺失时为 STATUS_UNKNOWN
     * @param tokenVersion 令牌版本;-1 表示未知
     */
    private record TokenState(int status, int tokenVersion) {
    }
}
