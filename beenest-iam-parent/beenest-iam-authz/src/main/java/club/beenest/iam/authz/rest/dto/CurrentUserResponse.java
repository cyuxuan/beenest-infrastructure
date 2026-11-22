package club.beenest.iam.authz.rest.dto;

import club.beenest.iam.authz.principal.IamUserPrincipal;

import java.util.List;
import java.util.Map;

/**
 * 当前用户信息(登录成功与 {@code /api/v1/auth/me} 共用同一结构)。
 * <p>前端凭 {@code authorities} 控制按钮/菜单的可见性;扩展属性直接透出给业务侧。</p>
 *
 * @param userId      用户主键
 * @param realmId     身份域主键
 * @param realmKey    身份域标识
 * @param username    登录名
 * @param nickname    昵称
 * @param authorities 权限码集合
 * @param attributes  扩展属性
 * @param mfaChallenge MFA 质询信息;仅口令通过但需第二步行时非空,
 *                     前端凭 mfaToken 跳转二步验证页
 */
public record CurrentUserResponse(
        Long userId,
        Long realmId,
        String realmKey,
        String username,
        String nickname,
        List<String> authorities,
        Map<String, String> attributes,
        MfaChallengeResponse mfaChallenge
) {

    /**
     * 由认证主体构建响应(非 MFA 质询场景)。
     *
     * @param principal 当前登录用户主体
     * @return 含用户身份、域与权限的响应
     */
    public static CurrentUserResponse from(IamUserPrincipal principal) {
        return new CurrentUserResponse(principal.getUserId(), principal.getRealmId(), principal.getRealmKey(),
                principal.getUsername(), principal.getNickname(), principal.getAuthorities(),
                principal.getAttributes(), null);
    }

    /**
     * MFA 质询响应:仅携带票据信息,用户字段留空。
     * <p>登录接口返回此结构时,前端据此跳转第二步验证页面。</p>
     *
     * @param challenge MFA 质询票据
     * @return 用户字段为空、仅含质询信息的响应
     */
    public static CurrentUserResponse mfaChallenge(MfaChallengeResponse challenge) {
        return new CurrentUserResponse(null, null, challenge.realmKey(), null, null,
                List.of(), Map.of(), challenge);
    }
}
