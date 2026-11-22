package club.beenest.iam.core.model;

import java.util.Set;

/**
 * 客户端(接入应用)领域模型。
 * <p>对应 OIDC Client;secret 仅机密客户端持有,公共客户端强制 PKCE。
 * 令牌有效期与 sub 取值策略支持客户端级覆盖,未配置时继承所属身份域。</p>
 *
 * @param id           主键
 * @param realmId      所属身份域
 * @param clientKey    客户端标识(client_id,域内唯一)
 * @param clientName   展示名
 * @param clientType   类型:PUBLIC / CONFIDENTIAL
 * @param secret       机密客户端密钥(公共客户端为 null)
 * @param redirectUris 允许的回调地址
 * @param postLogoutRedirectUris 登出后允许回跳的地址(为空表示只能回 IAM 登录页)
 * @param backchannelLogoutUri   OIDC 后通道登出接收地址(为空表示不参与广播)
 * @param reuseRefreshTokens     刷新令牌是否复用(FALSE 表示每次刷新轮换)
 * @param scopes       允许的 scope
 * @param requirePkce   是否强制 PKCE
 * @param requireConsent 是否要求用户授权同意(首次授权展示同意页)
 * @param enabled      是否启用
 * @param subjectMode     OIDC sub 取值策略(为 null 继承所属域)
 * @param accessTokenTtl  access token 覆盖有效期(秒,为 null 取域默认)
 * @param refreshTokenTtl refresh token 覆盖有效期(秒,为 null 取域默认)
 * @param grantTypes    允许的授权类型:authorization_code / refresh_token / client_credentials
 * @param allowedLoginTypes 允许的第一因素集合;空集合表示不限制
 * @param requireMfa       是否强制 MFA
 * @param serviceAccountUserId client_credentials 的主体用户(服务账号)主键,为 null 表示不支持凭据模式
 */
public record ClientModel(
        Long id,
        Long realmId,
        String clientKey,
        String clientName,
        ClientType clientType,
        String secret,
        Set<String> redirectUris,
        Set<String> postLogoutRedirectUris,
        String backchannelLogoutUri,
        boolean reuseRefreshTokens,
        Set<String> scopes,
        boolean requirePkce,
        boolean requireConsent,
        boolean enabled,
        SubjectMode subjectMode,
        Long accessTokenTtl,
        Long refreshTokenTtl,
        Set<String> grantTypes,
        Set<String> allowedLoginTypes,
        boolean requireMfa,
        Long serviceAccountUserId
) {
    /** 客户端类型: PUBLIC 为公共客户端(无 secret,强制 PKCE),CONFIDENTIAL 为机密客户端(持有 secret)。 */
    public enum ClientType { PUBLIC, CONFIDENTIAL }

    /**
     * 解析 access token 有效期。
     *
     * @param realmDefault 所属域默认有效期(秒)
     * @return 客户端覆盖值;未配置时返回域默认值
     */
    public long resolveAccessTokenTtl(long realmDefault) {
        return accessTokenTtl == null ? realmDefault : accessTokenTtl;
    }

    /**
     * 解析 refresh token 有效期。
     *
     * @param realmDefault 所属域默认有效期(秒)
     * @return 客户端覆盖值;未配置时返回域默认值
     */
    public long resolveRefreshTokenTtl(long realmDefault) {
        return refreshTokenTtl == null ? realmDefault : refreshTokenTtl;
    }

    /**
     * 解析 OIDC sub 取值策略。
     *
     * @param realmDefault 所属域默认策略
     * @return 客户端显式配置;未配置时取域默认,域默认也为空时取 LOGIN_NAME
     */
    public SubjectMode resolveSubjectMode(SubjectMode realmDefault) {
        return subjectMode == null ? (realmDefault == null ? SubjectMode.LOGIN_NAME : realmDefault) : subjectMode;
    }

    /**
     * 是否支持 client_credentials 授权。
     *
     * @return 授权类型包含 client_credentials 且已配置服务账号用户时为 true
     */
    public boolean supportsClientCredentials() {
        return grantTypes != null && grantTypes.contains("client_credentials")
                && serviceAccountUserId != null;
    }

    /**
     * 是否参与后通道登出广播。
     *
     * @return 已登记 backchannelLogoutUri 时为 true
     */
    public boolean supportsBackchannelLogout() {
        return backchannelLogoutUri != null && !backchannelLogoutUri.isBlank();
    }
}
