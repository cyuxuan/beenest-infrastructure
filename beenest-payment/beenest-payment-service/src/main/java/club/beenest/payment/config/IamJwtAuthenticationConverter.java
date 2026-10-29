package club.beenest.payment.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * IAM 访问令牌 → Spring Security 认证对象的转换器。
 * <p>
 * 与 IAM 文档的映射约定保持一致（不写转换器时 Spring 默认只映射 {@code scope}，
 * {@code permissions} 不会成为 authority）：
 * <ul>
 *   <li>{@code permissions} → 原样作为 authority（如 {@code payment:order:view}）</li>
 *   <li>{@code scope} → {@code SCOPE_*}（协议层作用域，不是业务权限）</li>
 *   <li>{@code realm_roles} / {@code client_roles} / {@code app_roles} → {@code ROLE_*}</li>
 * </ul>
 *
 * <p>因此控制器上的 {@code @PreAuthorize("hasRole('ADMIN')")} 依赖 IAM 角色码
 * {@code ADMIN}（域角色或本应用角色），而不是 {@code scope}。</p>
 *
 * @since 2026-10-29
 */
@Component
public class IamJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** 角色 authority 前缀，供 hasRole() 使用 */
    private static final String ROLE_PREFIX = "ROLE_";

    /** 作用域 authority 前缀，供 hasAuthority('SCOPE_xxx') 使用 */
    private static final String SCOPE_PREFIX = "SCOPE_";

    private static final String PERMISSIONS_CLAIM = "permissions";
    private static final String SCOPE_CLAIM = "scope";
    private static final String USERNAME_CLAIM = "preferred_username";

    /** 需要映射为 ROLE_* 的角色声明 */
    private static final List<String> ROLE_CLAIMS = List.of("realm_roles", "client_roles", "app_roles");

    /**
     * 将 JWT 转换为 {@link JwtAuthenticationToken}。
     *
     * @param jwt 已通过验签与声明校验的访问令牌
     * @return 认证对象
     */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = extractAuthorities(jwt);
        return new JwtAuthenticationToken(jwt, authorities, resolveName(jwt));
    }

    /**
     * 提取权限集合。
     *
     * @param jwt 访问令牌
     * @return 去重后的权限集合
     */
    private Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        // 使用 LinkedHashSet 去重，避免同一权限码在不同声明中重复出现
        Set<String> authorities = new LinkedHashSet<>();

        // 1. 业务权限码：原样作为 authority，不加任何前缀
        List<String> permissions = jwt.getClaimAsStringList(PERMISSIONS_CLAIM);
        if (permissions != null) {
            permissions.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(authorities::add);
        }

        // 2. 协议作用域：RFC 9068 中 scope 是空格分隔字符串
        String scope = jwt.getClaimAsString(SCOPE_CLAIM);
        if (StringUtils.hasText(scope)) {
            for (String item : scope.split(" ")) {
                if (StringUtils.hasText(item)) {
                    authorities.add(SCOPE_PREFIX + item.trim());
                }
            }
        }

        // 3. 角色码：realm_roles / client_roles / app_roles 统一映射为 ROLE_*
        for (String claimName : ROLE_CLAIMS) {
            List<String> roles = jwt.getClaimAsStringList(claimName);
            if (roles == null) {
                continue;
            }
            roles.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .forEach(role -> authorities.add(ROLE_PREFIX + role));
        }

        Collection<GrantedAuthority> result = new ArrayList<>(authorities.size());
        authorities.forEach(authority -> result.add(new SimpleGrantedAuthority(authority)));
        return result;
    }

    /**
     * 解析认证主体名称。
     * <p>展示名一律取 {@code preferred_username}（该声明恒定是登录名，不受主体策略影响），
     * 缺失时回退到 {@code sub}。</p>
     *
     * @param jwt 访问令牌
     * @return 主体名称
     */
    private String resolveName(Jwt jwt) {
        String username = jwt.getClaimAsString(USERNAME_CLAIM);
        return StringUtils.hasText(username) ? username : jwt.getSubject();
    }
}
