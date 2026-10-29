package club.beenest.payment.common.utils;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 认证工具类。
 * <p>
 * 统一从 Spring Security 上下文中的 IAM 访问令牌（{@link Jwt}）读取当前登录用户信息。
 * 支付服务已不再依赖 CAS 客户端，令牌声明口径以 IAM 文档为准：
 * <ul>
 *   <li>{@code phone_number} —— 已验证手机号，作为钱包 {@code customer_no}</li>
 *   <li>{@code nickname} / {@code preferred_username} —— 展示名与登录名</li>
 *   <li>{@code idp_identities} —— 已绑定的渠道身份，如 {@code {"wechat-mini": "o4xt..."}}</li>
 * </ul>
 *
 * <p><b>为什么用手机号做钱包主键</b>：IAM 文档把手机号列为跨渠道归并锚点——同一自然人用微信、
 * 支付宝、后台建号等入口登录时，渠道标识各不相同、IAM 账号也可能有多个，只有手机号能把它们收敛到
 * 同一个钱包。{@code uid} 只能标识"IAM 账号"，{@code sub} 还会跟随主体策略变化（默认取登录名），
 * 二者都会在账号合并或改名时把同一用户拆成两个钱包。</p>
 *
 * <p>代价是手机号可变更：用户换号后会被视为新用户。这是业务上接受的取舍，
 * 换号场景应由 IAM 侧做账号合并或在业务侧做档案迁移。</p>
 *
 * @since 2026-10-29
 */
public final class AuthUtils {

    /** 角色权限前缀 */
    private static final String ROLE_PREFIX = "ROLE_";

    /** 作用域权限前缀（协议层，不是业务权限） */
    private static final String SCOPE_PREFIX = "SCOPE_";

    /** 手机号声明：钱包 customer_no 的唯一来源，phone 为自定义映射器的兜底名 */
    private static final List<String> PHONE_CLAIMS = List.of("phone_number", "phone");

    private static final String NICKNAME_CLAIM = "nickname";
    private static final String USERNAME_CLAIM = "preferred_username";
    private static final String IDP_IDENTITIES_CLAIM = "idp_identities";

    /** 渠道身份键中代表微信的片段，用于兜底匹配 openid */
    private static final List<String> WECHAT_KEY_HINTS = List.of("wechat", "weixin", "wx");

    private AuthUtils() {
    }

    /**
     * 获取当前登录用户 ID（钱包 customer_no）。
     * <p>返回归一化后的手机号，未登录、令牌未携带手机号或号码不合法时返回 null。</p>
     *
     * @return 归一化手机号，无法确定时返回 null
     */
    public static String getCurrentUserId() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return null;
        }
        for (String claim : PHONE_CLAIMS) {
            String phone = PhoneNumbers.normalize(readClaimAsText(jwt, claim));
            if (phone != null) {
                return phone;
            }
        }
        return null;
    }

    /**
     * 获取当前登录用户名称。
     *
     * @return 用户昵称或登录名，未登录时返回 null
     */
    public static String getCurrentUsername() {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return null;
        }
        // 1. 优先昵称
        String nickname = jwt.getClaimAsString(NICKNAME_CLAIM);
        if (StringUtils.hasText(nickname)) {
            return nickname;
        }
        // 2. 其次登录名（该声明不受主体策略影响）
        String username = jwt.getClaimAsString(USERNAME_CLAIM);
        if (StringUtils.hasText(username)) {
            return username;
        }
        return jwt.getSubject();
    }

    /**
     * 获取当前登录用户的属性值。
     *
     * <p>会按传入顺序依次尝试多个属性名，返回第一个非空值。查找顺序：
     * 先查令牌顶层声明，再查 {@code idp_identities} 渠道身份映射
     * （键精确匹配；属性名与 openid 相关时，兜底匹配 wechat/wx 渠道）。</p>
     *
     * @param attributeNames 属性名候选列表
     * @return 第一个命中的属性值；当前用户未登录或没有匹配属性时返回 null
     */
    public static String getCurrentAttribute(String... attributeNames) {
        Jwt jwt = currentJwt();
        if (jwt == null || attributeNames == null || attributeNames.length == 0) {
            return null;
        }
        for (String attributeName : attributeNames) {
            if (!StringUtils.hasText(attributeName)) {
                continue;
            }
            // 1. 顶层声明
            String value = readClaimAsText(jwt, attributeName);
            if (StringUtils.hasText(value)) {
                return value;
            }
            // 2. 渠道身份映射（IAM 把 openid 等第三方标识放在 idp_identities 中）
            value = readIdpIdentity(jwt, attributeName);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 获取当前用户 ID；如果未登录则抛出异常。
     *
     * @return 当前用户 ID
     */
    public static String requireCurrentUserId() {
        String userId = getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new SecurityException("用户未登录或身份验证失败");
        }
        return userId;
    }

    /**
     * 判断当前请求是否已登录。
     *
     * @return true 表示已认证
     */
    public static boolean isLogin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * 获取当前请求携带的凭证串。
     *
     * @return Bearer Token 原文，未登录时返回 null
     */
    public static String getCurrentToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken().getTokenValue();
        }
        if (authentication == null || authentication.getCredentials() == null) {
            return null;
        }
        return authentication.getCredentials().toString();
    }

    /**
     * 判断当前用户是否拥有指定角色。
     *
     * @param role 角色名
     * @return true 表示拥有该角色
     */
    public static boolean hasRole(String role) {
        if (!StringUtils.hasText(role)) {
            return false;
        }
        String normalized = role.startsWith(ROLE_PREFIX)
                ? role
                : ROLE_PREFIX + role.toUpperCase(Locale.ROOT);
        return getAuthorities().contains(normalized);
    }

    /**
     * 判断当前用户是否拥有指定权限。
     *
     * @param permission 权限名（IAM 权限码，如 {@code payment:order:view}）
     * @return true 表示拥有该权限
     */
    public static boolean hasPermission(String permission) {
        if (!StringUtils.hasText(permission)) {
            return false;
        }
        return getAuthorities().contains(permission.trim());
    }

    /**
     * 获取当前用户的角色列表。
     *
     * @return 角色列表（小写，不含 ROLE_ 前缀），未登录时返回空列表
     */
    public static List<String> getRoles() {
        return getAuthorities().stream()
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .map(authority -> authority.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * 获取当前用户的权限列表。
     *
     * @return 权限列表（不含角色与作用域前缀），未登录时返回空列表
     */
    public static List<String> getPermissions() {
        return getAuthorities().stream()
                .filter(authority -> !authority.startsWith(ROLE_PREFIX))
                .filter(authority -> !authority.startsWith(SCOPE_PREFIX))
                .toList();
    }

    /**
     * 提取当前用户的所有权限字符串。
     *
     * @return 权限字符串集合
     */
    private static List<String> getAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return Collections.emptyList();
        }
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        List<String> result = new ArrayList<>(authorities.size());
        for (GrantedAuthority authority : authorities) {
            if (authority != null && StringUtils.hasText(authority.getAuthority())) {
                result.add(authority.getAuthority());
            }
        }
        return result;
    }

    /**
     * 获取当前请求的 IAM 访问令牌。
     *
     * @return JWT，未认证或非 JWT 认证时返回 null
     */
    private static Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken();
        }
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    /**
     * 读取顶层声明并转成字符串（多值声明取第一个）。
     *
     * @param jwt  访问令牌
     * @param name 声明名
     * @return 声明值，不存在时返回 null
     */
    private static String readClaimAsText(Jwt jwt, String name) {
        Object raw = jwt.getClaims().get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(item -> item != null && StringUtils.hasText(item.toString()))
                    .map(Object::toString)
                    .findFirst()
                    .orElse(null);
        }
        return raw.toString();
    }

    /**
     * 从 {@code idp_identities} 渠道身份映射中读取属性值。
     *
     * @param jwt  访问令牌
     * @param name 属性名（如 openid）
     * @return 渠道身份值，未命中时返回 null
     */
    @SuppressWarnings("unchecked")
    private static String readIdpIdentity(Jwt jwt, String name) {
        Object raw = jwt.getClaims().get(IDP_IDENTITIES_CLAIM);
        if (!(raw instanceof Map<?, ?> identities) || identities.isEmpty()) {
            return null;
        }

        // 1. 键精确匹配（如按 wechat-mini 取值）
        Object exact = ((Map<String, Object>) identities).get(name);
        if (exact != null && StringUtils.hasText(exact.toString())) {
            return exact.toString();
        }

        // 2. 属性名与 openid 相关时，按微信渠道键兜底匹配
        if (name.toLowerCase(Locale.ROOT).contains("openid")) {
            for (Map.Entry<?, ?> entry : identities.entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toString().toLowerCase(Locale.ROOT) : "";
                boolean wechatKey = WECHAT_KEY_HINTS.stream().anyMatch(key::contains);
                if (wechatKey && entry.getValue() != null && StringUtils.hasText(entry.getValue().toString())) {
                    return entry.getValue().toString();
                }
            }
        }
        return null;
    }
}
