package club.beenest.iam.authz.token;

import club.beenest.iam.authz.jwks.RealmJwkSource;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 域感知的 JWT 解码器。
 * <p>业务接口路径中不含域标识,无法像协议端点那样从句柄解析身份域;
 * 因此先读取令牌 issuer(形如 {@code {baseUrl}/realms/{realmKey}})反查域,
 * 再用该域密钥验签。按域缓存解码器,避免每次请求重建处理器。</p>
 * <p>验签通过后还要比对 issuer 是否为本服务该域的规范签发方:iss 只被用来选密钥,
 * 不比对就无法排除"同域密钥签发、但声明了别的签发方地址"的令牌。</p>
 */
public class IamJwtDecoder implements JwtDecoder {

    private static final Logger log = LoggerFactory.getLogger(IamJwtDecoder.class);

    /** issuer 中的域路径段标识 */
    private static final String REALM_PATH_SEGMENT = "/realms/";

    /**
     * 解码器缓存条目上界。
     * <p>iss 取自未验签的令牌,域标识可被任意构造,每个新值都会占用一个条目;
     * 上界取域数量的合理上限,超出后不再写入缓存。</p>
     */
    private static final int MAX_CACHED_DECODERS = 64;

    private final RealmJwkSource realmJwkSource;
    private final OAuth2TokenValidator<Jwt> validator;
    private final IamIssuerBaseUrlResolver baseUrlResolver;
    /** 域 → 解码器;域数量有限,进程内缓存即可 */
    private final Map<String, JwtDecoder> decodersByRealm = new ConcurrentHashMap<>();

    public IamJwtDecoder(RealmJwkSource realmJwkSource, OAuth2TokenValidator<Jwt> validator,
                         IamIssuerBaseUrlResolver baseUrlResolver) {
        this.realmJwkSource = realmJwkSource;
        this.validator = validator;
        this.baseUrlResolver = baseUrlResolver;
    }

    /**
     * 按令牌 issuer 定位身份域,用该域的密钥验签并解码。
     * <p>解码器按域缓存,最多 {@value #MAX_CACHED_DECODERS} 个域;未命中的域现场构建。
     * 缓存命中与否不影响校验结果。</p>
     *
     * @param token 紧凑序列化 JWT
     * @return 验签通过的令牌
     * @throws BadJwtException 令牌格式非法、issuer 无法解析出域、域不存在或已停用、无可用密钥、
     *                         验签失败、issuer 与本服务该域的签发方不一致
     */
    @Override
    public Jwt decode(String token) {
        String realmKey = resolveRealmKey(token);
        if (realmKey == null) {
            throw new BadJwtException("令牌缺少可识别的签发方(iss)");
        }
        JwtDecoder decoder = decodersByRealm.get(realmKey);
        if (decoder == null) {
            decoder = createDecoder(realmKey);
            if (decodersByRealm.size() < MAX_CACHED_DECODERS) {
                decodersByRealm.putIfAbsent(realmKey, decoder);
            }
        }
        Jwt jwt = decoder.decode(token);
        requireCanonicalIssuer(jwt, realmKey);
        return jwt;
    }

    /**
     * 校验令牌 issuer 与本服务该域的规范签发方一致。
     * <p>规范签发方为 {@code {基址}/realms/{realmKey}}。基址未配置且当前线程没有请求上下文时
     * 无法确定,此时跳过比对(单元测试与内部调用属于此类)。</p>
     *
     * @param jwt      验签通过的令牌
     * @param realmKey 令牌所属域标识
     * @throws BadJwtException issuer 与本服务该域的签发方不一致
     */
    private void requireCanonicalIssuer(Jwt jwt, String realmKey) {
        String baseUrl = baseUrlResolver.resolveCurrent();
        if (baseUrl == null) {
            return;
        }
        String expected = baseUrl + REALM_PATH_SEGMENT + realmKey;
        String actual = jwt.getClaimAsString(JwtClaimNames.ISS);
        if (!expected.equals(actual)) {
            // 该判定依赖当前请求推导出的基址:多主机名访问或代理未透传协议时会命中,
            // 表现为 401 且响应体为空,调用方看不出原因
            log.warn("令牌签发方与当前访问地址不一致: realm={} 期望={} 实际={}",
                    realmKey, expected, actual);
            throw new BadJwtException("令牌签发方与本服务不一致");
        }
    }

    /**
     * 为指定域构建解码器,密钥取自该域 JWK,校验器为全局组合校验器。
     *
     * @param realmKey 域标识
     * @return 域专用解码器
     * @throws BadJwtException 域不存在、已停用或无可用 JWK
     */
    private JwtDecoder createDecoder(String realmKey) {
        // 域必须真实存在且启用:随机编造的域标识会占用缓存条目,达到上界后正常域无法缓存
        if (realmJwkSource.jwksFor(realmKey).isEmpty()) {
            throw new BadJwtException("令牌的签发方不存在或已停用");
        }
        JWKSource<SecurityContext> jwkSource = realmJwkSourceFor(realmKey);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * 将域 JWK 列表适配为 Nimbus 的 {@link JWKSource}。
     *
     * @param realmKey 域标识
     * @return JWK 源,按 selector 匹配器筛选
     */
    private JWKSource<SecurityContext> realmJwkSourceFor(String realmKey) {
        return (JWKSelector selector, SecurityContext context) -> {
            List<JWK> jwks = realmJwkSource.jwksFor(realmKey);
            return jwks.stream()
                    .filter(jwk -> selector.getMatcher().matches(jwk))
                    .toList();
        };
    }

    /**
     * 从未验签的令牌中读取 issuer 并解析域标识。
     * <p>只做定位,不做信任判断:真正的签名与声明校验在后续解码器中完成,伪造的 issuer
     * 只会导向错误的密钥,验签必然失败。</p>
     *
     * @param token 紧凑序列化令牌
     * @return 域标识;issuer 缺失、不含 /realms/ 段或段后为空时返回 null
     * @throws BadJwtException 令牌格式非法,无法解析 JWT
     */
    private static String resolveRealmKey(String token) {
        String issuer;
        try {
            issuer = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("令牌格式非法", e);
        }
        if (issuer == null) {
            return null;
        }
        int index = issuer.indexOf(REALM_PATH_SEGMENT);
        if (index < 0) {
            return null;
        }
        String realmKey = issuer.substring(index + REALM_PATH_SEGMENT.length());
        // 去掉可能的尾随路径与斜杠,域标识本身不含斜杠
        int slash = realmKey.indexOf('/');
        if (slash >= 0) {
            realmKey = realmKey.substring(0, slash);
        }
        return realmKey.isBlank() ? null : realmKey;
    }
}
