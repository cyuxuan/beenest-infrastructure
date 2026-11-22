package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新客户端请求。
 * <p>客户端标识与类型不可修改;字段为 null 表示不更新该项。</p>
 *
 * @param clientName      客户端名称
 * @param redirectUris    允许的回调地址
 * @param postLogoutRedirectUris 登出后允许回跳的地址(传空数组表示清空)
 * @param backchannelLogoutUri   OIDC 后通道登出接收地址(传空串表示取消广播)
 * @param reuseRefreshTokens     刷新令牌是否复用
 * @param subjectMode     OIDC sub 取值策略:LOGIN_NAME / USER_ID(传空串表示继承所属域)
 * @param scopes          允许的 scope
 * @param requirePkce     是否强制 PKCE
 * @param requireConsent  是否要求用户授权同意
 * @param accessTokenTtl  access token 覆盖有效期(秒)
 * @param refreshTokenTtl refresh token 覆盖有效期(秒)
 * @param enabled         是否启用
 * @param description     说明
 * @param allowedLoginTypes 允许的第一因素(传空数组表示清空白名单,即不限制;null 表示不更新)
 * @param requireMfa      是否强制二步验证
 * @param grantTypes      允许的授权类型(传空数组表示回落到"授权码 + 刷新")
 * @param serviceAccountUserId client_credentials 的主体用户(服务账号)主键
 */
public record ClientUpdateRequest(
        @Size(max = 200, message = "客户端名称长度不能超过 200")
        String clientName,

        List<String> redirectUris,

        List<String> postLogoutRedirectUris,

        @Size(max = 500, message = "后通道登出地址长度不能超过 500")
        String backchannelLogoutUri,

        Boolean reuseRefreshTokens,

        @Pattern(regexp = "LOGIN_NAME|USER_ID|", message = "主体标识策略只能是 LOGIN_NAME 或 USER_ID", flags = Pattern.Flag.CASE_INSENSITIVE)
        String subjectMode,

        List<String> scopes,

        Boolean requirePkce,

        Boolean requireConsent,

        @Positive(message = "access token 有效期必须为正整数")
        Long accessTokenTtl,

        @Positive(message = "refresh token 有效期必须为正整数")
        Long refreshTokenTtl,

        Boolean enabled,

        @Size(max = 500, message = "说明长度不能超过 500")
        String description,

        List<@Pattern(regexp = "PASSWORD|LDAP|SMS|WECHAT|OIDC|SAML",
                message = "登录方式只能是 PASSWORD / LDAP / SMS / WECHAT / OIDC / SAML") String> allowedLoginTypes,

        Boolean requireMfa,

        List<@Pattern(regexp = "authorization_code|refresh_token|client_credentials",
                message = "授权类型只能是 authorization_code / refresh_token / client_credentials") String> grantTypes,

        @Positive(message = "服务账号用户主键必须为正整数")
        Long serviceAccountUserId
) {
}
