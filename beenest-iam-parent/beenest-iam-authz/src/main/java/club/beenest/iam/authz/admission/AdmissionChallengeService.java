package club.beenest.iam.authz.admission;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 准入挑战票据服务。
 * <p>票据按会话标识 sid 索引:挑战仅在已有会话时产生(无会话时准入判定直接放行),
 * sid 必存在,登录路径据 sid 即可查到待处理挑战,表单无需传递票据参数。</p>
 * <p>票据一次性:补验成功后必须消费,同一票据不可重复用于不同登录方式。</p>
 */
@Service
public class AdmissionChallengeService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionChallengeService.class);

    /** 票据有效期(5 分钟):需覆盖用户查看挑战页并完成认证的时间。 */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private final RedisUtils redisUtils;

    public AdmissionChallengeService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 签发挑战票据并写入 sid 与 token 两条索引。
     *
     * @param realmKey 身份域标识
     * @param sid      会话标识
     * @param clientId 目标客户端
     * @param policy   目标客户端的准入策略
     * @param returnTo 补验成功后的回跳地址
     * @return 签发后的挑战票据
     * @throws BusinessException 策略不可用、缺少 sid 或回跳地址非法
     */
    public AdmissionChallenge issue(String realmKey, String sid, String clientId,
                                    ClientAdmissionPolicy policy, String returnTo) {
        // denyAll 下 allowedMethods 归一为空集合,而 AdmissionChallenge.allows()
        // 将空集合解释为放行全部;签发出去等于把限制变成 unrestricted。
        // 调用方应先判 denyAll 并直接返回 403,这里是兜底
        if (policy == null || policy.denyAll()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "客户端准入策略不可用,无法发起验证");
        }
        // 票据必须挂在会话上,挑战仅在已有会话时产生;无 sid 的票据会让任意会话
        // 凭 token 采用,跨站回跳时 sid 为 null 是另一种形态(见 resolve)
        if (sid == null || sid.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少会话标识,无法发起验证");
        }
        // 回跳地址限定为本域授权端点前缀;任意地址会把挑战页变成开放重定向的跳板
        if (!isSafeReturnTo(returnTo, realmKey)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "回跳地址非法");
        }
        Set<String> codes = new LinkedHashSet<>();
        policy.allowedMethods().forEach(method -> codes.add(method.code()));
        String token = UUID.randomUUID().toString().replace("-", "");
        AdmissionChallenge challenge = new AdmissionChallenge(
                sid, clientId, codes, policy.requiresMfa(), returnTo, token, System.currentTimeMillis());
        // 两条索引同写:登录路径按 sid 定位,跨站联邦回跳按 token 定位(那时没有 Cookie)
        redisUtils.setObject(sidKey(realmKey, sid), challenge, CHALLENGE_TTL);
        redisUtils.setObject(tokenKey(realmKey, token), challenge, CHALLENGE_TTL);
        return challenge;
    }

    /**
     * 判定回跳地址是否为本域授权端点。
     * <p>要求以单个斜杠开头,协议相对地址("//evil.com")与反斜杠("/\evil.com")会被
     * 浏览器解析成外部站点;再限定路径以本域授权端点前缀开头,把可回跳范围收紧。</p>
     *
     * @param returnTo 待判定的回跳地址
     * @param realmKey 身份域标识
     * @return true 表示地址合法可回跳
     */
    public static boolean isSafeReturnTo(String returnTo, String realmKey) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        if (!returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.startsWith("/\\")) {
            return false;
        }
        return returnTo.startsWith("/realms/" + realmKey + "/oauth2/authorize");
    }

    /**
     * 定位挑战票据。
     * <p>显式令牌优先:跨站联邦走 HTTP-POST 回跳时浏览器不带会话 Cookie,sid 取不到,
     * 只能靠回跳携带的 token 定位;令牌索引与 sid 索引并存,两条路径定位方式不同。</p>
     *
     * @param realmKey       身份域标识
     * @param challengeToken 显式令牌,可为空
     * @param sid            会话标识,可为空
     * @return 命中的挑战票据,未命中返回 null
     */
    public AdmissionChallenge resolve(String realmKey, String challengeToken, String sid) {
        if (challengeToken != null && !challengeToken.isBlank()) {
            AdmissionChallenge byToken = redisUtils.getObject(
                    tokenKey(realmKey, challengeToken), AdmissionChallenge.class);
            if (byToken != null) {
                // sid 非空时令牌须与当前会话匹配,避免他人票据改写本会话状态;
                // 跨站回跳 sid 为 null,仅靠令牌定位,不受该约束影响
                if (sid != null && !sid.isBlank() && byToken.sid() != null && !byToken.sid().equals(sid)) {
                    log.warn("挑战令牌与会话不匹配,已忽略: realm={}, tokenSid={}, currentSid={}",
                            realmKey, byToken.sid(), sid);
                } else {
                    return byToken;
                }
            }
        }
        return resolveBySid(realmKey, sid);
    }

    /**
     * 按会话标识读取票据。
     *
     * @param realmKey 身份域标识
     * @param sid      会话标识
     * @return 命中的票据;sid 为空或未命中时返回 null
     */
    public AdmissionChallenge resolveBySid(String realmKey, String sid) {
        if (sid == null || sid.isBlank()) {
            return null;
        }
        return redisUtils.getObject(sidKey(realmKey, sid), AdmissionChallenge.class);
    }

    /**
     * 消费票据:sid 与 token 两条索引一并删除。
     *
     * @param realmKey  身份域标识
     * @param challenge 待消费的票据;为 null 时忽略
     */
    public void consume(String realmKey, AdmissionChallenge challenge) {
        if (challenge == null) {
            return;
        }
        if (challenge.sid() != null) {
            redisUtils.delete(sidKey(realmKey, challenge.sid()));
        }
        if (challenge.challengeToken() != null) {
            redisUtils.delete(tokenKey(realmKey, challenge.challengeToken()));
        }
    }

    /**
     * sid 索引键。
     *
     * @param realmKey 身份域标识
     * @param sid      会话标识
     * @return Redis 键
     */
    private static String sidKey(String realmKey, String sid) {
        return String.format(IamRedisKeyConstants.ADMISSION_CHALLENGE_SID, realmKey, sid);
    }

    /**
     * token 索引键。
     *
     * @param realmKey 身份域标识
     * @param token    票据令牌
     * @return Redis 键
     */
    private static String tokenKey(String realmKey, String token) {
        return String.format(IamRedisKeyConstants.ADMISSION_CHALLENGE_TOKEN, realmKey, token);
    }
}
