package club.beenest.iam.authz.token;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JWT → 认证对象转换器:把令牌声明映射为 Spring Security 权限。
 * <p>权限码声明按原名映射为 authority,与 {@code @PreAuthorize("hasAuthority('user:view')")} 同一口径;
 * scope 映射为 {@code SCOPE_*},OIDC userinfo 等协议端点据此判定授权范围;
 * 域角色与客户端角色加 {@code ROLE_} 前缀,与权限码命名空间隔离。</p>
 */
public class IamJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** 权限码声明 */
    public static final String CLAIM_PERMISSIONS = "permissions";
    /** 域角色声明 */
    public static final String CLAIM_REALM_ROLES = "realm_roles";
    /** 客户端角色声明 */
    public static final String CLAIM_CLIENT_ROLES = "client_roles";
    /** 角色前缀 */
    private static final String ROLE_PREFIX = "ROLE_";
    /** scope 权限前缀(Spring Security 约定) */
    private static final String SCOPE_PREFIX = "SCOPE_";

    /**
     * 将 JWT 转换为已认证的 {@link JwtAuthenticationToken}。
     * <p>权限码与角色声明均忽略空白值;认证名取 {@code username} 声明,
     * 缺失时回落为 {@code sub},审计日志按用户名定位到人。</p>
     * <p>ID Token 一律拒绝:它不是资源访问凭据。签发侧已不给 ID Token 写权限码,
     * 这里再挡一次,避免将来有人把权限声明加回 ID Token 时静默形成越权通道。</p>
     *
     * @param jwt 验签通过的令牌
     * @return 携带权限集合与用户名的认证对象
     * @throws InvalidBearerTokenException 令牌为 ID Token
     */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (IamTokenTypeValidator.TYPE_ID.equalsIgnoreCase(
                jwt.getClaimAsString(IamTokenTypeValidator.CLAIM_TOKEN_TYPE))) {
            throw new InvalidBearerTokenException("ID Token 不能作为访问令牌使用");
        }
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        addAuthorities(authorities, jwt.getClaimAsStringList(CLAIM_PERMISSIONS), null);
        addScopeAuthorities(authorities, jwt.getClaim("scope"));
        addAuthorities(authorities, jwt.getClaimAsStringList(CLAIM_REALM_ROLES), ROLE_PREFIX);
        addAuthorities(authorities, jwt.getClaimAsStringList(CLAIM_CLIENT_ROLES), ROLE_PREFIX);
        String name = jwt.getClaimAsString("username");
        return new JwtAuthenticationToken(jwt, authorities, name == null ? jwt.getSubject() : name);
    }

    /**
     * scope 声明映射为 {@code SCOPE_*} 权限。
     *
     * @param target     权限集合
     * @param scopeClaim {@code scope} 声明,空格分隔字符串(RFC 9068)或 JSON 数组(部分编码器)
     */
    private static void addScopeAuthorities(Set<GrantedAuthority> target, Object scopeClaim) {
        if (scopeClaim == null) {
            return;
        }
        if (scopeClaim instanceof Collection<?> values) {
            for (Object value : values) {
                addAuthorities(target, List.of(String.valueOf(value)), SCOPE_PREFIX);
            }
            return;
        }
        for (String value : String.valueOf(scopeClaim).trim().split("\\s+")) {
            if (!value.isBlank()) {
                target.add(new SimpleGrantedAuthority(SCOPE_PREFIX + value));
            }
        }
    }

    /**
     * 声明值批量映射为权限。
     *
     * @param target 权限集合
     * @param values 声明值列表,null 或空白值跳过
     * @param prefix 权限前缀;null 表示按原名映射
     */
    private static void addAuthorities(Set<GrantedAuthority> target, List<String> values, String prefix) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(new SimpleGrantedAuthority(prefix == null ? value : prefix + value));
            }
        }
    }
}
