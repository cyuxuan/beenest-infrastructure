package club.beenest.iam.authz.authorization;

import club.beenest.iam.authz.principal.IamUserPrincipal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 授权数据的 Redis 存储投影。
 * <p>OAuth2Authorization 中的 RegisteredClient 不参与序列化(由客户端仓储按 id 回填),
 * 认证主体只保留本项目自己的轻量快照,不落 Spring Security 的认证对象
 * 以及它的权限集合,避免把几十个权限对象冗余进每条授权记录。</p>
 * <p>{@link AuthorizationData} 与 {@link TokenData} 均声明为 final:序列化层按
 * "非 final 类型才写类型标记"的规则,两类投影以纯 JSON 形态存取,
 * 不需要进入反序列化白名单。</p>
 */
public final class AuthorizationData {

    /** 授权 id(OAuth2Authorization.id) */
    private String id;

    /** 关联客户端行 id */
    private String registeredClientId;

    /** 主体名(登录名) */
    private String principalName;

    /** 授权类型值,如 authorization_code */
    private String authorizationGrantType;

    /** 已授权 scope 列表 */
    private List<String> authorizedScopes;

    /** 认证主体快照(重新装配登录态用) */
    private IamUserPrincipal principal;

    /** 其余属性集合(如 state、授权请求等由授权服务器写入的附加数据) */
    private Map<String, Object> attributes;

    /** access 令牌(自签名 JWT) */
    private TokenData accessToken;

    /** refresh 令牌 */
    private TokenData refreshToken;

    /** ID 令牌 */
    private TokenData idToken;

    /** 授权码 */
    private TokenData authorizationCode;

    /** 令牌投影:值 + 时间 + 元数据。 */
    public static final class TokenData {

        private String tokenValue;
        /**
         * 令牌授予的 scope 集合。
         * <p>必须落库:OIDC userinfo 等端点按该集合判定是否包含 openid,
         * 丢失则一律返回 insufficient_scope。</p>
         */
        private java.util.Set<String> scopes;
        private Instant issuedAt;
        private Instant expiresAt;
        /** 元数据(如 invalidated 标记、PKCE 校验参数) */
        private Map<String, Object> metadata;
        /** 仅 ID 令牌使用:声明集合 */
        private Map<String, Object> claims;

        public String getTokenValue() {
            return tokenValue;
        }

        public void setTokenValue(String tokenValue) {
            this.tokenValue = tokenValue;
        }

        public Instant getIssuedAt() {
            return issuedAt;
        }

        public void setIssuedAt(Instant issuedAt) {
            this.issuedAt = issuedAt;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }

        public Map<String, Object> getClaims() {
            return claims;
        }

        public void setClaims(Map<String, Object> claims) {
            this.claims = claims;
        }

        public java.util.Set<String> getScopes() {
            return scopes;
        }

        public void setScopes(java.util.Set<String> scopes) {
            this.scopes = scopes;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRegisteredClientId() {
        return registeredClientId;
    }

    public void setRegisteredClientId(String registeredClientId) {
        this.registeredClientId = registeredClientId;
    }

    public String getPrincipalName() {
        return principalName;
    }

    public void setPrincipalName(String principalName) {
        this.principalName = principalName;
    }

    public String getAuthorizationGrantType() {
        return authorizationGrantType;
    }

    public void setAuthorizationGrantType(String authorizationGrantType) {
        this.authorizationGrantType = authorizationGrantType;
    }

    public List<String> getAuthorizedScopes() {
        return authorizedScopes;
    }

    public void setAuthorizedScopes(List<String> authorizedScopes) {
        this.authorizedScopes = authorizedScopes;
    }

    public IamUserPrincipal getPrincipal() {
        return principal;
    }

    public void setPrincipal(IamUserPrincipal principal) {
        this.principal = principal;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public TokenData getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(TokenData accessToken) {
        this.accessToken = accessToken;
    }

    public TokenData getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(TokenData refreshToken) {
        this.refreshToken = refreshToken;
    }

    public TokenData getIdToken() {
        return idToken;
    }

    public void setIdToken(TokenData idToken) {
        this.idToken = idToken;
    }

    public TokenData getAuthorizationCode() {
        return authorizationCode;
    }

    public void setAuthorizationCode(TokenData authorizationCode) {
        this.authorizationCode = authorizationCode;
    }
}
