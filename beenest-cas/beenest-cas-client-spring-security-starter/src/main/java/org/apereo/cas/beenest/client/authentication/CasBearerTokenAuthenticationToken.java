package org.apereo.cas.beenest.client.authentication;

import org.apereo.cas.beenest.client.details.CasUserDetails;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Bearer Token 认证令牌
 * <p>
 * 用于小程序和兼容场景下的 CAS TGT 认证。
 * 携带 accessToken (TGT)、可选的 refreshToken 和验证后的用户详情。
 * <p>
 * 无感刷新：当 accessToken 过期时，若携带了 refreshToken，
 * Provider 会自动调用 CAS Server 刷新端点获取新 token。
 */
public class CasBearerTokenAuthenticationToken extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String accessToken;
    private final String refreshToken;
    private final CasUserDetails userDetails;

    /**
     * 获取 accessToken
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * 获取 refreshToken（可能为 null）
     */
    public String getRefreshToken() {
        return refreshToken;
    }

    /**
     * 未认证状态（Filter 创建时使用，不含 refreshToken）
     */
    public CasBearerTokenAuthenticationToken(String accessToken) {
        super(null);
        this.accessToken = accessToken;
        this.refreshToken = null;
        this.userDetails = null;
    }

    /**
     * 未认证状态（Filter 创建时使用，含 refreshToken）
     *
     * @param accessToken  TGT ID
     * @param refreshToken 刷新令牌（可能为 null）
     */
    public CasBearerTokenAuthenticationToken(String accessToken, String refreshToken) {
        super(null);
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userDetails = null;
    }

    /**
     * 已认证状态（Provider 验证成功后使用）
     */
    public CasBearerTokenAuthenticationToken(String accessToken, CasUserDetails userDetails) {
        this(accessToken, null, userDetails);
    }

    /**
     * 已认证状态（含 refreshToken，刷新成功后使用）
     *
     * @param accessToken   新的 TGT ID
     * @param refreshToken  新的刷新令牌（可能为 null）
     * @param userDetails   用户详情
     */
    public CasBearerTokenAuthenticationToken(String accessToken, String refreshToken, CasUserDetails userDetails) {
        super(userDetails.getAuthorities());
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.userDetails = userDetails;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return accessToken;
    }

    @Override
    public Object getPrincipal() {
        return userDetails;
    }
}
