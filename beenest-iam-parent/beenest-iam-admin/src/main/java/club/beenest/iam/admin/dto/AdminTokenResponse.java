package club.beenest.iam.admin.dto;

import java.time.Instant;
import java.util.Set;

/**
 * 已签发授权(令牌)响应。
 * <p>不返回任何令牌值,仅返回授权主体、范围与有效期。</p>
 *
 * @param authorizationId 授权记录 id(吊销时使用)
 * @param clientId        客户端标识
 * @param principalName   授权主体登录名
 * @param grantType       授权类型(authorization_code / client_credentials 等)
 * @param scopes          已授权范围
 * @param hasAccessToken  是否持有访问令牌
 * @param hasRefreshToken 是否持有刷新令牌(可续期)
 * @param issuedAt        最近签发时间
 * @param expiresAt       最晚过期时间(刷新令牌过期时间)
 */
public record AdminTokenResponse(
        String authorizationId,
        String clientId,
        String principalName,
        String grantType,
        Set<String> scopes,
        boolean hasAccessToken,
        boolean hasRefreshToken,
        Instant issuedAt,
        Instant expiresAt
) {
}
