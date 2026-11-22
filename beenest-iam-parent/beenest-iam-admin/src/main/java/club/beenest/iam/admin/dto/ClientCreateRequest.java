package club.beenest.iam.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建客户端请求。
 * <p>公共客户端(SPA、移动端)不产生密钥并强制 PKCE;
 * 机密客户端由平台生成密钥,明文仅在创建响应中返回一次。</p>
 *
 * @param clientKey       客户端标识(client_id)
 * @param clientName      客户端名称
 * @param clientType      类型:PUBLIC / CONFIDENTIAL
 * @param redirectUris    允许的回调地址
 * @param postLogoutRedirectUris 登出后允许回跳的地址(为空表示只能回 IAM 登录页)
 * @param backchannelLogoutUri   OIDC 后通道登出接收地址(为空表示不参与单点登出广播)
 * @param reuseRefreshTokens     刷新令牌是否复用(为空按复用处理)
 * @param subjectMode     OIDC sub 取值策略:LOGIN_NAME / USER_ID(为空继承所属域)
 * @param scopes          允许的 scope
 * @param requirePkce     是否强制 PKCE
 * @param requireConsent  是否要求用户授权同意(第三方接入开启,内部系统留空)
 * @param accessTokenTtl  access token 覆盖有效期(秒,可空)
 * @param refreshTokenTtl refresh token 覆盖有效期(秒,可空)
 * @param description     说明
 * @param allowedLoginTypes 允许的第一因素(为空表示不限制)
 * @param requireMfa      是否强制二步验证
 * @param grantTypes      允许的授权类型:authorization_code / refresh_token / client_credentials
 * @param serviceAccountUserId client_credentials 的主体用户(服务账号)主键
 */
public record ClientCreateRequest(
        @NotBlank(message = "客户端标识不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_.-]{2,100}$", message = "客户端标识只能包含字母、数字、下划线、点、中划线,长度 2-100")
        String clientKey,

        @NotBlank(message = "客户端名称不能为空")
        @Size(max = 200, message = "客户端名称长度不能超过 200")
        String clientName,

        @NotBlank(message = "客户端类型不能为空")
        @Pattern(regexp = "PUBLIC|CONFIDENTIAL", message = "客户端类型只能是 PUBLIC 或 CONFIDENTIAL")
        String clientType,

        @NotEmpty(message = "至少配置一个回调地址")
        List<@NotBlank(message = "回调地址不能为空") String> redirectUris,

        List<String> postLogoutRedirectUris,

        @Size(max = 500, message = "后通道登出地址长度不能超过 500")
        String backchannelLogoutUri,

        Boolean reuseRefreshTokens,

        @Pattern(regexp = "LOGIN_NAME|USER_ID", message = "主体标识策略只能是 LOGIN_NAME 或 USER_ID", flags = Pattern.Flag.CASE_INSENSITIVE)
        String subjectMode,

        List<String> scopes,

        Boolean requirePkce,

        Boolean requireConsent,

        @Positive(message = "access token 有效期必须为正整数")
        Long accessTokenTtl,

        @Positive(message = "refresh token 有效期必须为正整数")
        Long refreshTokenTtl,

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
