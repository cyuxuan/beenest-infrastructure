package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 客户端响应。
 * <p>密钥明文仅在新建与轮换密钥的响应中返回,其余场景 clientSecret 为 null。</p>
 *
 * @param id              主键
 * @param realmId         所属域
 * @param clientKey       客户端标识
 * @param clientName      客户端名称
 * @param clientType      类型:PUBLIC / CONFIDENTIAL
 * @param clientSecret    明文密钥(仅新建/轮换时返回)
 * @param redirectUris    允许的回调地址
 * @param postLogoutRedirectUris 登出后允许回跳的地址(为空表示只能回 IAM 登录页)
 * @param backchannelLogoutUri   OIDC 后通道登出接收地址(为空表示不参与单点登出广播)
 * @param reuseRefreshTokens     刷新令牌是否复用(为 false 表示每次刷新轮换)
 * @param subjectMode     OIDC sub 取值策略(为空表示继承所属域)
 * @param scopes          允许的 scope
 * @param requirePkce     是否强制 PKCE
 * @param requireConsent  是否要求用户授权同意(开启后首次授权展示同意页)
 * @param accessTokenTtl  access token 覆盖有效期(秒,空则取域默认)
 * @param refreshTokenTtl refresh token 覆盖有效期(秒,空则取域默认)
 * @param enabled         是否启用
 * @param description     说明
 * @param createdTime     创建时间
 * @param updatedTime     更新时间
 * @param allowedLoginTypes 允许的第一因素(空集合表示不限制)
 * @param requireMfa      是否强制二步验证
 * @param grantTypes      允许的授权类型
 * @param serviceAccountUserId client_credentials 的主体用户(服务账号)主键
 */
public record ClientResponse(
        Long id,
        Long realmId,
        String clientKey,
        String clientName,
        String clientType,
        String clientSecret,
        Set<String> redirectUris,
        Set<String> postLogoutRedirectUris,
        String backchannelLogoutUri,
        Boolean reuseRefreshTokens,
        String subjectMode,
        Set<String> scopes,
        Boolean requirePkce,
        Boolean requireConsent,
        Long accessTokenTtl,
        Long refreshTokenTtl,
        Boolean enabled,
        String description,
        LocalDateTime createdTime,
        LocalDateTime updatedTime,
        Set<String> allowedLoginTypes,
        Boolean requireMfa,
        Set<String> grantTypes,
        Long serviceAccountUserId
) {
}
