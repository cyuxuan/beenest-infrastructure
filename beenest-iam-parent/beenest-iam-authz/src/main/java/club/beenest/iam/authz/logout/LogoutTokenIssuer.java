package club.beenest.iam.authz.logout;

import club.beenest.iam.authz.jwks.RealmJwkSource;
import club.beenest.iam.authz.session.SessionIdHash;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 后通道登出令牌签发器(OIDC Back-Channel Logout 1.0 §2.4)。
 * <p>登出令牌由应用所属域的密钥签名:应用侧只使用自身信任 issuer 的 JWKS 验签,
 * 编码器按域显式取私钥,不复用带线程上下文语义的全局编码器。</p>
 * <p>管理端强制下线时当前请求未必带域上下文,回落到默认域会签出应用验签失败的令牌。</p>
 */
@Component
public class LogoutTokenIssuer {

    /** 后通道登出事件标识,应用据此判定通知类型为登出而非普通 JWT */
    public static final String EVENT_BACKCHANNEL_LOGOUT =
            "http://schemas.openid.net/event/backchannel-logout";

    /** 登出令牌的短有效期:通知一次性使用,无需长期有效 */
    private static final Duration LOGOUT_TOKEN_TTL = Duration.ofMinutes(2);

    private final RealmJwkSource realmJwkSource;

    public LogoutTokenIssuer(RealmJwkSource realmJwkSource) {
        this.realmJwkSource = realmJwkSource;
    }

    /**
     * 签发一枚登出令牌。
     *
     * @param issuerBaseUrl 对外基址(形如 {@code https://iam.example.com})
     * @param realmKey      身份域标识(issuer 路径段)
     * @param clientKey     目标应用的 client_id,作为 aud
     * @param subject       用户主体(与 ID Token 的 sub 一致;为空则只带 sid)
     * @param sessionId     IAM 会话标识原文(明文只在本服务内流转,不写进令牌)
     * @return 紧凑序列化的 JWS
     */
    public String issue(String issuerBaseUrl, String realmKey, String clientKey,
                        String subject, String sessionId) {
        // 1. 取该域的签名私钥(轮换期只有生效密钥带私钥)
        JWK signingKey = realmJwkSource.jwksFor(realmKey).stream()
                .filter(JWK::isPrivate)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("身份域无可用签名私钥: " + realmKey));
        // 2. 单密钥编码器:不需要选择器,也不会歧义
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
        // 3. 声明:iss/aud/iat/jti 必填,events 是登出令牌的语义标识,sid 用于定位应用侧会话。
        //    sid 必须与 ID Token 中的 sid 同值(会话 id 的摘要),应用据此对号入座
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuerBaseUrl + "/realms/" + realmKey)
                .audience(java.util.List.of(clientKey))
                .issuedAt(now)
                .expiresAt(now.plus(LOGOUT_TOKEN_TTL))
                .id(UUID.randomUUID().toString())
                .claim("events", Map.of(EVENT_BACKCHANNEL_LOGOUT, Map.of()));
        String sidHash = SessionIdHash.of(sessionId);
        if (sidHash != null) {
            claims.claim("sid", sidHash);
        }
        if (subject != null && !subject.isBlank()) {
            claims.subject(subject);
        }
        // 4. 登出令牌不得包含 nonce(规范硬性要求,携带即为非法)
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.getKeyID())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
