package club.beenest.iam.authz.token;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 令牌类型校验:只接受访问令牌用于资源访问。
 * <p>平台同时签发 ID Token 与访问令牌,两者的声明结构接近,若不区分,ID Token 一旦被当作
 * Bearer 令牌重放即可换取接口权限。签发侧为两类令牌打 {@code token_type} 标记,
 * 此处拒绝以 {@code id} 标记的令牌。</p>
 * <p>缺失标记的令牌按访问令牌放行,用于平滑兼容升级前已签发的存量令牌;
 * 升级后签发的 ID Token 必然带标记,不会被误放行。</p>
 */
public class IamTokenTypeValidator implements OAuth2TokenValidator<Jwt> {

    /** 令牌类型声明名 */
    public static final String CLAIM_TOKEN_TYPE = "token_type";
    /** 访问令牌标记 */
    public static final String TYPE_ACCESS = "access";
    /** ID Token 标记 */
    public static final String TYPE_ID = "id";

    /**
     * 校验令牌类型与受众。
     *
     * @param token 待校验令牌
     * @return 校验结果;ID Token 或缺少受众时返回失败
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String type = token.getClaimAsString(CLAIM_TOKEN_TYPE);
        if (TYPE_ID.equalsIgnoreCase(type)) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "ID Token 不能作为访问令牌使用", null));
        }
        if (token.getAudience() == null || token.getAudience().isEmpty()) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "令牌缺少受众(aud)", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
