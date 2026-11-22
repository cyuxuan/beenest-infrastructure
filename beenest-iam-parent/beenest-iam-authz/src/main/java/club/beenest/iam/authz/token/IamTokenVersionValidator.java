package club.beenest.iam.authz.token;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 令牌版本校验器。
 * <p>access token 携带签发时的用户令牌版本号;用户执行全端下线后版本号递增,
 * 旧令牌在此处被拒绝。同时校验账号当前状态:账号被禁用或删除后,已签发令牌立即失效。
 * 令牌缺少用户标识(如客户端凭证令牌)时不做用户维度检查。</p>
 */
public class IamTokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    /** 账号状态:正常 */
    private static final int STATUS_NORMAL = 1;

    private final TokenStateCache tokenStateCache;

    public IamTokenVersionValidator(TokenStateCache tokenStateCache) {
        this.tokenStateCache = tokenStateCache;
    }

    /**
     * 校验令牌的账号状态与令牌版本。
     * <p>账号状态与版本取自 {@link TokenStateCache},不直接查库;账号不存在、禁用、锁定,
     * 或令牌版本低于当前版本(全端下线)时拒绝。令牌无 {@code uid}/{@code token_version}
     * 声明(客户端凭证)时跳过用户维度检查。</p>
     *
     * @param jwt 已通过签名与时间校验的令牌
     * @return 校验结果;失败原因以 invalid_token 错误返回
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String uidClaim = jwt.getClaimAsString("uid");
        Object versionClaim = jwt.getClaim("token_version");
        if (uidClaim == null || !(versionClaim instanceof Number tokenVersion)) {
            return OAuth2TokenValidatorResult.success();
        }
        long userId;
        try {
            userId = Long.parseLong(uidClaim);
        } catch (NumberFormatException e) {
            return failure("令牌中的用户标识非法");
        }
        int status = tokenStateCache.status(userId);
        if (status == -1) {
            return failure("令牌对应的账号不存在");
        }
        if (status != STATUS_NORMAL) {
            return failure("账号已禁用或锁定");
        }
        if (tokenStateCache.tokenVersion(userId) > tokenVersion.intValue()) {
            return failure("令牌已因全端下线失效");
        }
        return OAuth2TokenValidatorResult.success();
    }

    /**
     * 构造 invalid_token 校验失败结果。
     *
     * @param description 面向调用方的失败原因
     * @return 校验失败结果
     */
    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
