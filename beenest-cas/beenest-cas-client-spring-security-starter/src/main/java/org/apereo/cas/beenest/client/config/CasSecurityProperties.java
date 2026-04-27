package org.apereo.cas.beenest.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * CAS Security Starter 配置属性
 * <p>
 * 配置前缀 {@code cas.client}，与现有 beenest-cas-client 兼容，
 * 业务系统迁移时无需修改 YAML 配置前缀。
 */
@Data
@ConfigurationProperties(prefix = "cas.client")
public class CasSecurityProperties {

    /** 是否启用 CAS 认证（默认 false，需显式开启） */
    private boolean enabled = false;

    /** CAS 服务器地址（如 https://sso.beenest.club/cas） */
    private String serverUrl;

    /** 当前服务的访问地址（如 https://drone.beenest.club） */
    private String clientHostUrl;

    /** 注册的服务 ID（对应 CAS Server 中的 serviceId） */
    private String serviceId;

    /** CAS Server 签名密钥（用于 Bearer Token 相关请求签名） */
    private String signKey;

    /** 不需要认证的 URL 模式（逗号分隔，用于默认 SecurityFilterChain 的 permitAll 配置） */
    private String ignorePattern;

    /** URL 匹配模式类型: ANT / REGEX */
    private String ignoreUrlPatternType = "ANT";

    /** 未认证时是否重定向到 CAS 登录页（false 则返回 401 JSON） */
    private boolean redirectLogin = true;

    /** 是否使用 Session 存储认证状态 */
    private boolean useSession = true;

    /** Starter 运行模式 */
    private CasMode mode = CasMode.LOGIN_GATEWAY;

    /** CAS 登录回调路径（Spring Security CasAuthenticationFilter 监听） */
    private String loginPath = "/login/cas";

    /** Proxy Ticket 配置 */
    private ProxyConfig proxy = new ProxyConfig();

    /** Bearer Token 认证配置（小程序/兼容场景） */
    private TokenAuthConfig tokenAuth = new TokenAuthConfig();

    /** SLO 配置 */
    private SloConfig slo = new SloConfig();

    /** Spring Security 集成配置 */
    private SecurityConfig security = new SecurityConfig();

    /**
     * Proxy Ticket 配置
     */
    @Data
    public static class ProxyConfig {
        /** 是否启用 Proxy Ticket 支持 */
        private boolean enabled = false;
        /** PGT 回调 URL（CAS Server 回传 PGT 的地址） */
        private String callbackUrl;
        /** PGT 接收路径 */
        private String receptorPath = "/login/cas/proxyreceptor";
        /** 受信任的代理列表 */
        private List<String> trustedProxies = new ArrayList<>();
    }

    /**
     * Bearer Token 认证配置
     */
    @Data
    public static class TokenAuthConfig {
        /** 是否启用 Bearer Token 认证（小程序/兼容场景） */
        private boolean enabled = false;
        /** TGT 验证结果本地缓存时间（秒） */
        private long validateCacheTtlSeconds = 300;
        /** 本地缓存最大条目数 */
        private int validateCacheMaxSize = 10000;
        /** accessToken 撤销态保留时间（秒） */
        private long accessTokenRevocationTtlSeconds = 7L * 24 * 3600;
        /** refreshToken 撤销态保留时间（秒） */
        private long refreshTokenRevocationTtlSeconds = 365L * 24 * 3600;
        /** 是否启用无感 Token 刷新（需客户端同时传递 X-Refresh-Token） */
        private boolean autoRefreshEnabled = true;
        /** CAS Server refresh 端点路径（相对于 serverUrl） */
        private String refreshEndpoint = "/refresh";
        /** refresh 请求超时（毫秒） */
        private int refreshTimeoutMs = 5000;
        /** 权限版本属性名，用于识别授权缓存是否过期 */
        private String authorityVersionAttribute = "permissionVersion";
    }

    /**
     * 判断是否为资源服务模式。
     *
     * @return true 表示资源服务模式
     */
    public boolean isResourceServerMode() {
        return mode != null && mode.isResourceServer();
    }

    /**
     * 判断是否为登录网关模式。
     *
     * @return true 表示登录网关模式
     */
    public boolean isLoginGatewayMode() {
        return mode == null || mode.isLoginGateway();
    }

    /**
     * 解析用于 CAS 原生票据校验的目标服务地址。
     * <p>
     * 优先使用业务系统自身地址；如果未配置，则回退到 CAS 服务器地址。
     *
     * @return 验证服务地址
     */
    public String resolveValidationServiceUrl() {
        if (StringUtils.hasText(clientHostUrl)) {
            return trimTrailingSlash(clientHostUrl);
        }
        return trimTrailingSlash(serverUrl);
    }

    /**
     * SLO 配置
     */
    @Data
    public static class SloConfig {
        /** 是否启用 SLO（单点登出） */
        private boolean enabled = true;
        /** SLO 回调路径 */
        private String callbackPath = "/cas/callback";
    }

    /**
     * Spring Security 集成配置
     */
    @Data
    public static class SecurityConfig {
        /** CasAuthenticationProvider 的 key（用于验证 CasAuthenticationToken） */
        private String authenticationProviderKey = "beenest-cas-provider";
        /** Session 固定保护策略 */
        private String sessionFixationProtection = "migrateSession";
    }

    /**
     * 去除字符串尾部斜杠。
     *
     * @param value 原始字符串
     * @return 标准化后的字符串
     */
    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
