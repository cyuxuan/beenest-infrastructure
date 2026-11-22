package club.beenest.iam.authz.token;

import club.beenest.iam.authz.session.SessionRevocationService;
import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.util.RedisUtils;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 令牌黑名单校验器。
 * <p>签名与过期只能证明令牌未被篡改且在有效期内,不能表达已吊销。两类吊销都查 Redis:
 * 按 {@code jti} 命中的单令牌吊销(管理端吊销授权),按 {@code sid} 命中的整会话吊销
 * (用户登出、会话被顶下线)。</p>
 * <p>黑名单键 TTL 与令牌剩余存活期一致,过期后由 exp 校验拒绝,条目不常驻;
 * 查询走 Redis,与验签本身同为每请求一次 Redis 操作。</p>
 * <p>令牌无 jti 声明时按 jti 维度放行:无标识即无从吊销,此类令牌不应由本平台签发。</p>
 */
public class IamTokenBlocklistValidator implements OAuth2TokenValidator<Jwt> {

    /** 令牌中的会话摘要声明 */
    public static final String CLAIM_SID = "sid";

    private final RedisUtils redisUtils;
    private final SessionRevocationService revocationService;

    public IamTokenBlocklistValidator(RedisUtils redisUtils, SessionRevocationService revocationService) {
        this.redisUtils = redisUtils;
        this.revocationService = revocationService;
    }

    /**
     * 按 jti 与会话摘要查询吊销状态。
     *
     * @param jwt 已通过签名与时间校验的令牌
     * @return 校验结果;jti 或 sid 命中时以 invalid_token 错误返回已吊销
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();
        if (jti != null && !jti.isBlank()
                && redisUtils.hasKey(String.format(IamRedisKeyConstants.TOKEN_BLOCKLIST_JTI, jti))) {
            return revoked();
        }
        // 会话级吊销:令牌签出后用户已登出或被顶下线
        if (revocationService.isRevokedHash(jwt.getClaimAsString(CLAIM_SID))) {
            return revoked();
        }
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * 已吊销的统一错误。
     *
     * @return 校验失败结果
     */
    private static OAuth2TokenValidatorResult revoked() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "令牌已被吊销", null));
    }
}
