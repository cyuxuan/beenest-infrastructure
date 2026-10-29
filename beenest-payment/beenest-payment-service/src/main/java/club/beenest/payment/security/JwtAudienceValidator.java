package club.beenest.payment.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * JWT 受众（aud）校验器。
 * <p>
 * IAM 访问令牌的 {@code aud} 指向"令牌要交给哪个后端"，同一个后端的多个入口登记同一个受众。
 * 只校验签名与 issuer 不足以阻止"同域内其他应用的令牌"调用本服务，因此必须校验受众。
 *
 * @since 2026-10-29
 */
public class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    /**
     * 构造受众校验器。
     *
     * @param expectedAudience 期望的受众值，如 {@code urn:iam:app:payment}
     */
    public JwtAudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    /**
     * 校验令牌受众是否包含期望值。
     *
     * @param jwt 待校验令牌
     * @return 校验结果
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> audiences = jwt.getAudience();
        if (audiences != null && StringUtils.hasText(expectedAudience)
                && audiences.contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "令牌受众不匹配，期望 " + expectedAudience,
                null));
    }
}
