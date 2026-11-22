package club.beenest.iam.persistence.entity;

import club.beenest.iam.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 客户端实体(iam_client)。
 * <p>PUBLIC 客户端强制 PKCE,CONFIDENTIAL 客户端使用 client_secret;
 * subjectMode 为空时继承所属域的 sub 取值策略。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Client extends BaseEntity {

    /** 所属域 */
    private Long realmId;
    /** 客户端标识(域内唯一) */
    private String clientKey;
    /** 客户端名称 */
    private String clientName;
    /** 客户端类型:PUBLIC 公共(强制 PKCE)/ CONFIDENTIAL 机密 */
    private String clientType;
    /** 客户端密钥(机密客户端;公共客户端为空) */
    private String clientSecret;
    /** 回调地址(逗号分隔) */
    private String redirectUris;
    /** 登出后允许回跳的地址(逗号分隔;为空表示只回 IAM 登录页) */
    private String postLogoutRedirectUris;
    /** OIDC 后通道登出接收地址(为空表示不参与单点登出广播) */
    private String backchannelLogoutUri;
    /** 刷新令牌是否复用(TRUE 复用同一枚;FALSE 每次刷新轮换) */
    private Boolean reuseRefreshTokens;
    /** OIDC sub 取值策略(为空继承所属域):LOGIN_NAME 登录名 / USER_ID 不可变用户主键 */
    private String subjectMode;
    /** 作用域(逗号分隔) */
    private String scopes;
    /** 是否强制 PKCE */
    private Boolean requirePkce;
    /** 是否要求用户授权同意(OAuth2 consent) */
    private Boolean requireConsent;
    /** access token 覆盖有效期(秒,空则取域默认) */
    private Long accessTokenTtl;
    /** refresh token 覆盖有效期(秒,空则取域默认) */
    private Long refreshTokenTtl;
    /**
     * 允许的授权类型(逗号分隔):authorization_code / refresh_token / client_credentials。
     * <p>client_credentials 为机器对机器凭据模式,仅机密客户端可用,且需绑定服务账号主体。</p>
     */
    private String grantTypes;
    /**
     * 允许的第一因素集合(逗号分隔):PASSWORD/LDAP/SMS/WECHAT/OIDC/SAML。
     * <p>空串表示不限制,沿用域级可用方式;非空则只接受集合内的登录方式,
     * 会话认证方式不在集合内时转入补验流程。</p>
     */
    private String allowedLoginTypes;
    /**
     * 是否强制 MFA。
     * <p>与域级 iam_login_policy.force_mfa 叠加,任一为真即要求二步验证;
     * 客户端级强制可能晚于登录发生,会话需记录 MFA 完成态。</p>
     */
    private Boolean requireMfa;
    /**
     * client_credentials 的主体用户(服务账号)主键。
     * <p>为空表示该客户端不允许使用凭据模式换取令牌。</p>
     */
    private Long serviceAccountUserId;
    /** 是否启用 */
    private Boolean enabled;
    /** 说明 */
    private String description;
}
