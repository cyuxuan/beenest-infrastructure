package club.beenest.payment.security;

import club.beenest.payment.common.utils.PhoneNumbers;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * IAM 令牌类型校验器。
 * <p>
 * IAM 在同一套签名密钥下签发访问令牌与 ID Token，两者结构相近但语义完全不同：
 * ID Token 只用于建立会话，不含 {@code permissions}，且平台解码器会按 {@code token_type}
 * 直接拒绝。支付服务作为纯资源服务器，必须显式拒绝非访问令牌，避免 ID Token 被当作
 * Bearer 令牌重放。
 *
 * <p>同时可按需强制 {@code token_kind=APPLICATION}，拒绝平台令牌（PLATFORM）与
 * 机器令牌（MACHINE）——它们不代表某个具体业务用户。</p>
 *
 * <p>最后强制要求令牌携带<b>可用手机号</b>：支付侧以手机号作为钱包 {@code customer_no}
 * （IAM 文档推荐的跨渠道归并锚点）。没有手机号就无法确定这是谁的钱包，必须在认证阶段直接
 * 拒绝，不能靠后续兜底猜测——否则会出现"能调通接口但钱包对不上"的隐性错账。</p>
 *
 * @since 2026-10-29
 */
public class IamAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    /** 令牌类型声明：access / id */
    private static final String TOKEN_TYPE_CLAIM = "token_type";

    /** 令牌来源声明：PLATFORM / APPLICATION / MACHINE */
    private static final String TOKEN_KIND_CLAIM = "token_kind";

    /** 手机号声明候选，与 AuthUtils 的取值口径保持一致 */
    private static final List<String> PHONE_CLAIMS = List.of("phone_number", "phone");

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String APPLICATION_TOKEN_KIND = "APPLICATION";

    private final boolean requireApplicationToken;

    /**
     * 构造令牌类型校验器。
     *
     * @param requireApplicationToken 是否强制要求令牌来源为 APPLICATION
     */
    public IamAccessTokenValidator(boolean requireApplicationToken) {
        this.requireApplicationToken = requireApplicationToken;
    }

    /**
     * 校验令牌类型、来源与手机号。
     *
     * @param jwt 待校验令牌
     * @return 校验结果
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        // 1. token_type 存在时必须为 access（ID Token 一律拒绝）
        String tokenType = jwt.getClaimAsString(TOKEN_TYPE_CLAIM);
        if (tokenType != null && !ACCESS_TOKEN_TYPE.equalsIgnoreCase(tokenType)) {
            return failure("不接受非访问令牌: token_type=" + tokenType);
        }

        // 2. 按需强制应用令牌：平台令牌 / 机器令牌没有具体业务用户语义
        if (requireApplicationToken) {
            String tokenKind = jwt.getClaimAsString(TOKEN_KIND_CLAIM);
            if (!APPLICATION_TOKEN_KIND.equals(tokenKind)) {
                return failure("不接受非应用令牌: token_kind=" + tokenKind);
            }
        }

        // 3. 必须有可用手机号：钱包按手机号建档，缺了它无法确定"这是谁"
        if (!hasUsablePhone(jwt)) {
            return failure("令牌缺少可用手机号声明: phone_number");
        }

        return OAuth2TokenValidatorResult.success();
    }

    /**
     * 判断令牌是否携带可用手机号。
     *
     * @param jwt 访问令牌
     * @return true 表示存在可归一化的手机号
     */
    private boolean hasUsablePhone(Jwt jwt) {
        for (String claim : PHONE_CLAIMS) {
            if (PhoneNumbers.isValid(jwt.getClaimAsString(claim))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造失败结果。
     *
     * @param description 失败描述
     * @return 失败结果
     */
    private OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
