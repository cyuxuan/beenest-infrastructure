package club.beenest.iam.authz.principal;

import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

/**
 * 当前登录用户的读取入口。
 * <p>平台支持两种凭据:浏览器会话与 Bearer 令牌。前者直接持有用户快照,
 * 后者持 JWT,此处统一还原为同一 {@link IamUserPrincipal},
 * 使下游业务代码不必区分调用方是用 Cookie 还是令牌接入。</p>
 */
public final class CurrentUserHolder {

    /** 用户主键声明 */
    private static final String CLAIM_UID = "uid";
    /** 域主键声明 */
    private static final String CLAIM_REALM_ID = "realm_id";
    /** 域标识声明 */
    private static final String CLAIM_REALM = "realm";
    /** 用户名声明 */
    private static final String CLAIM_USERNAME = "username";
    /** 昵称声明 */
    private static final String CLAIM_NICKNAME = "nickname";
    /** 令牌版本声明 */
    private static final String CLAIM_TOKEN_VERSION = "token_version";
    /** 权限码声明 */
    private static final String CLAIM_PERMISSIONS = "permissions";
    /** 权限代次未知(令牌路径) */
    private static final long EPOCH_UNKNOWN = -1L;

    private CurrentUserHolder() {
    }

    /**
     * 读取当前登录主体。
     *
     * @return 登录主体;未登录或凭据类型不认识返回 null
     */
    public static IamUserPrincipal optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof IamUserPrincipal user) {
            return user;
        }
        if (principal instanceof Jwt jwt) {
            return fromJwt(jwt);
        }
        return null;
    }

    /**
     * 读取当前登录主体。
     *
     * @return 登录主体
     * @throws BusinessException 未登录时抛出未认证错误
     */
    public static IamUserPrincipal require() {
        IamUserPrincipal principal = optional();
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    /**
     * 由 Bearer 令牌的 JWT 声明还原用户快照。
     * <p>令牌路径的权限在签发时现算,无权限代次概念,代次按未知处理。</p>
     *
     * @param jwt 已验签的 JWT
     * @return 用户主体快照
     */
    private static IamUserPrincipal fromJwt(Jwt jwt) {
        return new IamUserPrincipal(
                jwt.getClaim(CLAIM_UID),
                jwt.getClaim(CLAIM_REALM_ID),
                jwt.getClaimAsString(CLAIM_REALM),
                jwt.getClaimAsString(CLAIM_USERNAME),
                jwt.getClaimAsString(CLAIM_NICKNAME),
                claimAsInt(jwt, CLAIM_TOKEN_VERSION),
                claimAsStringList(jwt, CLAIM_PERMISSIONS),
                Map.of(),
                EPOCH_UNKNOWN);
    }

    /**
     * 读取整型声明。
     *
     * @param jwt  JWT
     * @param name 声明名
     * @return 声明值;缺失或非法按 0 处理
     */
    private static int claimAsInt(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 读取字符串列表声明。
     *
     * @param jwt  JWT
     * @param name 声明名
     * @return 声明值;缺失返回空列表
     */
    private static List<String> claimAsStringList(Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values == null ? List.of() : values;
    }
}
