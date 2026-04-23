package org.apereo.cas.beenest.client.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    /** CAS Server 签名密钥（用于 Webhook 签名验证和拉取 API 请求签名） */
    private String signKey;

    /** TGT 校验接口共享密钥（用于 /token/validate 内部调用鉴权） */
    private String tokenValidationSecret;

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

    /** 业务系统登录代理配置 */
    private BusinessLoginProxyConfig businessLoginProxy = new BusinessLoginProxyConfig();

    /** Bearer Token 认证配置（APP/小程序场景） */
    private TokenAuthConfig tokenAuth = new TokenAuthConfig();

    /** SLO 配置 */
    private SloConfig slo = new SloConfig();

    /** 用户同步配置（已弃用 — CAS Server 已移除同步端点） */
    @Deprecated(since = "2.0")
    private SyncConfig sync = new SyncConfig();

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
     * 业务系统登录代理配置
     */
    @Data
    public static class BusinessLoginProxyConfig {
        /** 是否启用业务系统登录代理 */
        private boolean enabled = false;
        /** 业务系统对外暴露的登录代理前缀 */
        private String basePath = "/cas";
    }

    /**
     * Bearer Token 认证配置
     */
    @Data
    public static class TokenAuthConfig {
        /** 是否启用 Bearer Token 认证（APP/小程序场景） */
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
     * 用户同步配置（已弃用）
     * <p>
     * CAS Server 全面原生化后已移除自定义同步端点。
     * 用户属性通过每次 Token 验证自动获取最新值，无需额外同步。
     *
     * @deprecated 请移除 {@code cas.client.sync.*} 配置
     */
    @Data
    @Deprecated(since = "2.0")
    public static class SyncConfig {
        /** 是否启用用户同步（需显式开启） */
        private boolean enabled = false;
        /** Webhook 接收路径 */
        private String webhookPath = "/cas/sync/webhook";
        /** 是否启用拉取模式 */
        private boolean pullEnabled = false;
        /** 拉取间隔（秒） */
        private long pullIntervalSeconds = 60;
        /** 收到变更时是否自动刷新 Session 中的用户信息 */
        private boolean autoRefreshSession = true;
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
}
