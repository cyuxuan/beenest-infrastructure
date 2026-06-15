Apereo CAS WAR Overlay Template
=====================================

WAR Overlay Type: `cas-overlay`

# Versions

- CAS Server `7.3.6`
- JDK `21`

# Beenest 统一登录接入

`beenest-cas-client-spring-security-starter` 是底层兼容包，适合继续维护已有接入。
新服务建议优先使用下面两个场景化 starter：

- `club.beenest.cas:beenest-cas-client-login-gateway-starter`
- `club.beenest.cas:beenest-cas-client-resource-server-starter`

它们分别对应登录网关和资源服务两种场景。底层能力仍然由 CAS 客户端统一提供，支持：

- Web 端 CAS 单点登录
- 小程序 Bearer Token 登录
- APP 原生 OIDC 登录
- CAS back-channel 单点登出

业务系统只需要引入对应场景的 starter 并配置必要的 `cas.client.*`，即可获得统一登录能力。
场景化 starter 会自动补齐 `cas.client.enabled=true` 和对应模式默认值，因此通常不需要再手工写基础开关。

## 0. 3 分钟接入

先选服务类型，再套模板：

1. `login-gateway`
适合网关、BFF、用户直连的 Web/API 入口。
这类服务可以暴露 `/login/cas`、`/cas/**` 登录代理、SLO 回调。

2. `resource-server`
适合普通业务 API 服务。
这类服务只负责校验用户 Token、恢复用户上下文、执行权限判定，不暴露登录入口。

3. `internal-service`
适合纯内部微服务。
通常不建议接 CAS 用户登录体系，而是使用服务自己的内部认证，例如 `mTLS`、内部 JWT、`client_credentials` 或静态 Token + HMAC。

如果你只想先跑通，建议按下面两套模板直接选一种。

### 模块选择建议

| 场景 | 推荐模块 |
| --- | --- |
| 登录网关 / BFF / 用户入口 | `club.beenest.cas:beenest-cas-client-login-gateway-starter` |
| 普通业务资源服务 | `club.beenest.cas:beenest-cas-client-resource-server-starter` |
| 老项目兼容 / 平滑迁移 | `club.beenest.cas:beenest-cas-client-spring-security-starter` |

## 1. 推荐接入方式

### 方案 A：Login Gateway

适用场景：

- 浏览器端单点登录入口
- 小程序登录代理入口
- 需要承接 CAS SLO 回调
- 需要对外暴露 `/cas/**` 登录代理

```yaml
cas:
  client:
    mode: login-gateway
    server-url: https://sso.beenest.club/cas
    client-host-url: https://drone.beenest.club
    service-id: 10001
    sign-key: your-service-sign-key
    redirect-login: true
    use-session: true

    token-auth:
      enabled: true
      auto-refresh-enabled: true
      validate-cache-ttl-seconds: 300
      validate-cache-max-size: 10000
      access-token-revocation-ttl-seconds: 604800
      refresh-token-revocation-ttl-seconds: 31536000
      authority-version-attribute: permissionVersion

    slo:
      enabled: true
      callback-path: /cas/callback
```

### 方案 B：Resource Server

适用场景：

- 普通业务微服务
- 只需要 Bearer Token 鉴权
- 不需要 Web CAS 跳转登录
- 不应该对外暴露 `/cas/**` 登录代理

默认行为：

- `401 JSON`
- `STATELESS`
- 不暴露登录代理
- 不做浏览器跳转登录

```yaml
cas:
  client:
    mode: resource-server
    server-url: https://sso.beenest.club/cas
    client-host-url: https://drone-api.beenest.club
    service-id: 20001
    sign-key: your-service-sign-key
    redirect-login: false
    use-session: false

    token-auth:
      enabled: true
      auto-refresh-enabled: true
      validate-cache-ttl-seconds: 300
      validate-cache-max-size: 10000
      access-token-revocation-ttl-seconds: 604800
      refresh-token-revocation-ttl-seconds: 31536000
      authority-version-attribute: permissionVersion

    slo:
      enabled: false
```

### 方案 C：Internal Service

适用场景：

- 纯内部微服务
- 不直接面向终端用户
- 只接受网关或其他服务调用

推荐做法：

- 不引入 `beenest-cas-client-spring-security-starter`
- 使用内部服务认证
- 如果只是调用支付、结算、风控等内部接口，优先延续你们现在这种 `/internal/** + Token/HMAC/IP 白名单` 的模型

## 2. 配置说明

### 必填项

- `cas.client.enabled`：是否启用 starter。场景化 starter 默认已自动开启，主 starter 仍可手工关闭或覆盖。
- `cas.client.server-url`：CAS Server 地址，例如 `https://sso.beenest.club/cas`
- `cas.client.client-host-url`：当前业务系统地址，例如 `https://drone.beenest.club`
- `cas.client.service-id`：CAS 中注册的服务 ID
- `cas.client.sign-key`：CAS 与业务系统之间使用的 HMAC 签名密钥

### 常用项

- `cas.client.mode`：运行模式，`login-gateway` 表示登录网关，`resource-server` 表示纯资源服务
- `cas.client.token-auth.enabled`：是否启用小程序 Bearer Token 认证
- `cas.client.token-auth.authority-version-attribute`：权限版本字段名，默认 `permissionVersion`
- `cas.client.slo.enabled`：是否启用单点登出
- `cas.client.token-auth.access-token-revocation-ttl-seconds`：accessToken 撤销态保留时间
- `cas.client.token-auth.refresh-token-revocation-ttl-seconds`：refreshToken 撤销态保留时间

### 重要隐含逻辑

**mode 条件装配**：以下组件的激活与 `cas.client.mode` 强关联——

| 组件 | `login-gateway` | `resource-server` | 说明 |
|------|:---:|:---:|------|
| SLO 回调（CasSloConfiguration） | ✅ 自动激活 | ❌ 跳过 | resource-server 无需 SLO 入口 |
| CasAuthenticationEntryPoint | ✅ 启用 | ❌ 不启用 | resource-server 返回 401 JSON 而非 302 跳转 |
| Session 策略 | `IF_REQUIRED` | `STATELESS` | resource-server 无会话需求 |
| CasDefaultSecurityConfiguration | ✅ 作为兜底 | ✅ 作为兜底 | 仅在业务系统**未自定义** SecurityFilterChain 时生效 |

**CasDefaultSecurityConfiguration 零代码模式**：如果业务系统没有自定义 `SecurityFilterChain` Bean，starter 会自动创建默认的过滤器链（包含 CAS 认证 + Session + CSRF）。如果业务系统自定义了 `SecurityFilterChain`，默认配置自动跳过（`@ConditionalOnMissingBean`），此时必须手动通过 `CasAuthenticationConfigurer` DSL 接入 CAS：
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .with(casConfigurer, c -> {});  // ← 必须手动引入
    return http.build();
}
```

### 旧项目迁移指引

存量项目（如直接依赖 `beenest-cas-client-spring-security-starter`）迁移到场景化 starter 分两步：

**第一步：替换依赖**

```xml
<!-- 旧 -->
<artifactId>beenest-cas-client-spring-security-starter</artifactId>

<!-- 新（选择一种场景） -->
<artifactId>beenest-cas-client-login-gateway-starter</artifactId>
<!-- 或 -->
<artifactId>beenest-cas-client-resource-server-starter</artifactId>
```

**第二步：精简 YAML 配置**

场景化 starter 通过 `EnvironmentPostProcessor` 自动注入以下默认值，可从 YAML 中删除：

| 配置项 | login-gateway 默认 | resource-server 默认 |
|--------|-------------------|---------------------|
| `cas.client.enabled` | `true` | `true` |
| `cas.client.mode` | `login-gateway` | `resource-server` |
| `cas.client.token-auth.enabled` | `true` | `true` |
| `cas.client.redirect-login` | `true` | `false` |
| `cas.client.use-session` | `true` | `false` |
| `cas.client.slo.enabled` | `true` | `false` |

仅保留 `server-url`、`client-host-url`、`service-id`、`sign-key` 等业务专属配置即可。

> **注意**：如果旧项目自定义了 `SecurityFilterChain`（如 drone-system），迁移后仍需保留 `http.with(casConfigurer, c -> {})` 调用，不受零代码模式影响。

## 3. APP 原生接入（OIDC + PKCE）

APP 不再使用自定义 `/app/login` 门面，而是直接走 CAS 原生 OIDC。

推荐流程：

1. APP 读取 CAS OIDC discovery，issuer 使用 `https://sso.beenest.club/cas/oidc`。
2. APP 采用 Authorization Code + PKCE 跳转到 CAS 授权页。
3. CAS 登录成功后重定向回 APP 注册的 callback URI。
4. APP 用授权码换取标准 `access_token` 和 `refresh_token`，后续请求直接携带 OIDC 令牌。

本仓库已经预置了一个 APP 原生 OIDC 注册样例：

- [beenest-mobile-app-oidc-10002.json](beenest-cas-service/etc/cas/services/beenest-mobile-app-oidc-10002.json)

说明：

- 使用 `applicationType: native`
- 支持 Authorization Code + PKCE
- 允许本地回调和自定义 scheme 回调
- 生产环境请按实际 APP callback URI 和 client secret 重新调整

`resource-server` 模式下默认只做 Bearer Token 校验，不暴露登录入口。

## 4. Web 端单点登录

Web 端走标准 CAS 登录流程：

1. 用户访问业务系统受保护页面。
2. starter 通过 `CasAuthenticationEntryPoint` 跳转到 CAS 登录页。
3. CAS 登录成功后回调业务系统的 `cas.client.login-path`，默认是 `/login/cas`。
4. starter 使用 `CasAuthenticationProvider` 校验 ST，并将用户信息放入 Spring Security `SecurityContext`。
5. 登录成功后，starter 还会把 `HttpSession`、`ST`、`userId` 和 `CasUserSession` 注册到本地会话注册表，用于后续 SLO。

### 业务代码里如何取当前登录用户

推荐直接使用 starter 提供的工具类：

```java
String userId = CasSecurityUtils.getCurrentUserId();
CasUserDetails details = CasSecurityUtils.getCurrentUserDetails();
boolean authenticated = CasSecurityUtils.isAuthenticated();
```

也可以直接从 Spring Security 的 `SecurityContextHolder` 中读取当前认证对象。

## 5. 小程序登录

小程序场景继续保留 Bearer Token 模式：

1. 客户端直接调用 CAS Server 的 `/miniapp/*` 登录接口。
2. CAS 完成微信 / 抖音 / 支付宝小程序认证。
3. CAS 返回的 accessToken / refreshToken 由客户端保存并继续传给业务系统。
4. accessToken 过期后，客户端统一调用 `/refresh` 完成续期。

### 认证方式

Bearer Token 模式默认是：

- 首次认证时远程 CAS 校验
- 后续请求优先本地缓存
- accessToken 过期后可用 refreshToken 自动刷新

也就是说，**登录和刷新走远程 CAS，业务请求优先本地认证**，不会每次都打到 CAS。

### 请求头

Bearer 请求需要携带：

- `Authorization: Bearer <accessToken>`
- `X-Refresh-Token: <refreshToken>`  可选

## 6. 单点登出

starter 默认启用 SLO，CAS Server 会通过 back-channel 回调业务系统的：

- `POST /cas/callback`

如果你修改了 `cas.client.slo.callback-path`，则以你的配置为准。

### CAS Service 注册文件中的 SLO 配置

为了让 CAS Server 正确发起 back-channel 单点登出，`login-gateway` 模式的服务在 CAS 注册文件中必须配置以下字段：

```json
"logoutType": "BACK_CHANNEL",
"logoutUrl": "https://your-app.beenest.club/cas/callback"
```

- `logoutType: BACK_CHANNEL` — CAS 通过 HTTP POST 回调业务系统，无需浏览器参与
- `logoutUrl` — SLO 回调地址，需与业务系统的 `cas.client.slo.callback-path` 对应

**不同场景的配置策略**：

| 场景 | logoutType | logoutUrl | 说明 |
|------|-----------|-----------|------|
| login-gateway | `BACK_CHANNEL` | 必填 | 需要接收 SLO 回调 |
| resource-server | 不需要 | 不需要 | 无 SLO 入口，由网关处理 |
| 内部微服务（如 beenest-payment） | 不需要 | 不需要 | 通过 OpenFeign 调用，不持有用户 session |

### SLO 的保证方式

当 CAS 发起单点登出时，starter 会：

1. 根据 CAS 的 `SessionIndex` 找到本地 `sessionId`
2. 反查 `userId`
3. 清理该用户的本地 Bearer Token 缓存
4. 将本次 logout 里的 `accessToken` / `refreshToken` 写入撤销态
5. 使本地 `HttpSession` 失效
6. 让后续请求重新走 CAS 认证

### 这意味着什么

- Web 会话会被立即作废
- 小程序的本地 bearer 缓存也会被清掉
- 用户再次访问时，业务系统会重新认证
- 如果业务系统启用了共享 `CacheManager`，例如 Redis Cache，撤销态会同步到所有节点

### 多实例安全

starter 的 bearer 认证默认仍然是“本地缓存优先、远程 CAS 兜底”。
为了让多实例下的单点登出也能尽快失效，starter 会在收到 logout 时把 accessToken / refreshToken 写入撤销态：

- 没有共享缓存时，撤销态落在本地内存，适合单实例或开发环境
- 有共享 `CacheManager` 时，撤销态会自动进入共享缓存，多节点会同时感知注销事件

如果你的业务系统已经接了 Redis Cache，只要把 Spring Cache 打开并配置为共享缓存即可，不需要再写额外的业务代码。

### Redis Cache 推荐配置

业务系统如果想让注销态在多实例间共享，推荐直接启用 Spring Cache + Redis CacheManager：

```yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 7d

  data:
    redis:
      host: redis
      port: 6379
      timeout: 2s
```

如果你希望更细地控制撤销态的缓存名，也可以在业务系统里显式预创建这两个缓存：

- `casBearerAccessTokenRevocations`
- `casBearerRefreshTokenRevocations`

示例配置：

```java
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
                .withCacheConfiguration("casBearerAccessTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(7)))
                .withCacheConfiguration("casBearerRefreshTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(365)));
    }
}
```

这样 starter 收到单点登出回调后，会把 accessToken / refreshToken 写入共享撤销态：

- 当前节点立刻失效
- 其他节点在下一次认证时也会直接命中撤销态
- 不需要业务代码手动广播登出事件

### 业务系统接入模板

接入模板请优先使用本文开头的两套模式化模板：

1. `Login Gateway` 模板
适合统一登录入口、网关、BFF。

2. `Resource Server` 模板
适合普通业务服务，只负责 Bearer 鉴权和权限控制。

如果你的服务需要多实例共享注销态和权限版本，额外补上 Redis Cache 配置即可：

```yaml
spring:
  cache:
    type: redis
  data:
    redis:
      host: redis
      port: 6379
      timeout: 2s
```

```java
@Configuration
public class CacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
                .withCacheConfiguration("casBearerAccessTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(7)))
                .withCacheConfiguration("casBearerRefreshTokenRevocations",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(365)))
                .withCacheConfiguration("casBearerAuthorityVersions",
                        RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofDays(7)));
    }
}
```

这样可以同时让下面三类信息在多节点之间共享：

1. accessToken 撤销态
2. refreshToken 撤销态
3. 用户权限版本

## 7. 业务系统接入后怎么用

业务系统接入 starter 后，一般不需要自己再写登录过滤器。你只需要：

1. 引入 `club.beenest.cas:beenest-cas-client-login-gateway-starter` 或 `club.beenest.cas:beenest-cas-client-resource-server-starter`
2. 配置 `cas.client.*`
3. 将需要鉴权的接口交给 Spring Security
4. 在业务代码里从 `CasSecurityUtils` 读取当前用户

## 8. 常见注意点

- 小程序请求如果出现 404，优先检查是否直接请求到了 CAS Server，以及 `cas.server.prefix` 是否正确。
- 如果 SLO 到了但业务系统没有失效，通常是：
  - 业务系统没注册成功的 `HttpSession`
  - `cas.client.slo.enabled=false`
  - 多实例环境下没有共享失效状态
- Bearer Token 认证默认会先查本地缓存，再必要时远程 CAS 校验；缓存命中时会直接复用已解析的用户权限，避免每个请求都重复触发业务权限加载。若你要强制更快收敛权限变更，可以把缓存 TTL 调小，但会增加 CAS 和业务权限源压力。
- 如果你希望多实例场景下的 logout 更快生效，建议业务系统接一个共享的 `CacheManager`，starter 会自动把撤销态写进去。

## 9. 用户授权同步（访问控制 SPI）

### 问题背景

CAS 管理用户对各应用的访问权限（通过 `cas_user.roles` + Service JSON `accessStrategy.requiredAttributes.memberOf`）。当管理员在 Palantir 授权/撤销用户访问某应用时，CAS 只修改了自身数据库，下游应用系统无法感知变更。

下游应用维护有独立的用户和权限体系。用户被授权访问某应用时，需要在下游创建对应账号；撤销时需要禁用账号并强制下线。

### 设计理念：零耦合 SPI

- **CAS 不主动推送** — 不配置回调 URL，不依赖下游 API
- **下游应用通过 SPI 自主决策** — 在用户登录/Token 刷新时，根据 CAS 角色决定本地账号状态
- **完全向后兼容** — 不实现 SPI 的应用保持现有行为不变

### 工作原理

```
CAS 管理员: grant/revoke → 修改 cas_user.roles + 递增 tokenVersion
                                         ↓
用户登录/Token 刷新 → CAS 返回 memberOf + tokenVersion
                                         ↓
Client Starter: CasAccessControlManager.onAuthentication()
  ├─ 有权限 + 无本地用户 → SPI.createLocalUser()    (自动创建)
  ├─ 无权限 + 有本地用户 → SPI.disableLocalUser()   (禁用+下线)
  ├─ 有权限 + 有本地用户 → SPI.updateLocalUser()    (同步信息)
  └─ 无权限 + 无本地用户 → 拒绝访问               (兜底)
```

### 下游应用接入（两步）

**第一步：实现 SPI 接口**

```java
@Component
public class MyAppAccessControlService implements CasUserAccessControlService {

    private final SysUserMapper userMapper;
    private final SessionRegistry sessionRegistry;

    @Override
    public String getRequiredRole() {
        return "ROLE_DRONE_SYSTEM";  // 本应用对应的 CAS 角色
    }

    @Override
    public boolean isLocalUserActive(String userId) {
        SysUser user = userMapper.selectByCasUserId(userId);
        return user != null && user.getStatus() == 1;
    }

    @Override
    public String createLocalUser(String userId, Set<String> casRoles,
                                   Map<String, Object> casAttributes) {
        SysUser user = new SysUser();
        user.setCasUserId(userId);
        user.setUsername((String) casAttributes.get("username"));
        user.setNickname((String) casAttributes.get("nickname"));
        user.setPhone((String) casAttributes.get("phone"));
        user.setEmail((String) casAttributes.get("email"));
        user.setStatus(1);
        userMapper.insert(user);
        return String.valueOf(user.getId());
    }

    @Override
    public void disableLocalUser(String userId, Set<String> casRoles) {
        // 1. 禁用账号
        userMapper.updateStatusByCasUserId(userId, 0);
        // 2. 强制下线所有会话
        for (var session : sessionRegistry.getAllSessions(userId, false)) {
            session.expireNow();
        }
    }

    @Override
    public void updateLocalUser(String userId, Set<String> casRoles,
                                 Map<String, Object> casAttributes) {
        // 同步昵称、手机号等基本信息
        userMapper.updateProfileByCasUserId(userId, casAttributes);
    }
}
```

**第二步：配置**

```yaml
cas:
  client:
    access-control:
      enabled: true
      # required-role 不配置时由 SPI 的 getRequiredRole() 提供
      auto-create-on-grant: true    # 有权限但无本地用户时自动创建（默认 true）
      auto-disable-on-revoke: true  # 无权限但有本地用户时自动禁用（默认 true）
      force-logout-on-disable: true # 禁用时同时强制下线（默认 true）
```

### 配置属性说明

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `cas.client.access-control.enabled` | `false` | 是否启用访问控制 SPI |
| `cas.client.access-control.required-role` | — | 本应用要求的 CAS 角色名（可覆盖 SPI 的 `getRequiredRole()`） |
| `cas.client.access-control.auto-create-on-grant` | `true` | 有权限但无本地用户时是否自动创建 |
| `cas.client.access-control.auto-disable-on-revoke` | `true` | 无权限但有本地用户时是否自动禁用 |
| `cas.client.access-control.force-logout-on-disable` | `true` | 禁用用户时是否同时强制下线 |

### 边界场景

| 场景 | 处理方式 |
|------|----------|
| SPI 实现抛异常 | Client Starter 捕获异常，降级为拒绝（避免因同步失败阻断认证） |
| CAS 角色变更但用户未登录 | 用户下次登录/Token 刷新时自动检测并处理 |
| 并发创建本地用户 | SPI 实现需自行处理幂等性（如数据库 UNIQUE 约束） |
| 禁用后 CAS 又重新授权 | 下次登录时 `isLocalUserActive()` 返回 false，触发 `createLocalUser()` 重新创建 |
| 多实例部署 | SPI 实现操作数据库，天然支持多实例；会话销毁通过 Spring Security SessionRegistry 或 Redis 集群 |
| 不实现 SPI 的应用 | 自动配置不激活，完全不影响现有行为 |

### 与 CasUserRegistrationService 的关系

`CasUserRegistrationService` 是现有的首次登录自动注册接口。当 `CasUserAccessControlService` 激活时，优先使用后者（功能更完整），`DefaultCasUserDetailsService` 中的旧注册逻辑自动跳过，避免重复创建。

# Build

To build the project, use:

```bash
# Use --refresh-dependencies to force-update SNAPSHOT versions
./gradlew[.bat] clean build
```

To see what commands/tasks are available to the build script, run:

```bash
./gradlew[.bat] tasks
```

If you need to, on Linux/Unix systems, you can delete all the existing artifacts
(artifacts and metadata) Gradle has downloaded using:

```bash
# Only do this when absolutely necessary
rm -rf $HOME/.gradle/caches/
```

Same strategy applies to Windows too, provided you switch `$HOME` to its equivalent in the above command.

# Keystore

For the server to run successfully, you might need to create a keystore file.
This can either be done using the JDK's `keytool` utility or via the following command:

```bash
./gradlew[.bat] createKeystore
```

Use the password `changeit` for both the keystore and the key/certificate entries.
Ensure the keystore is loaded up with keys and certificates of the server.

# Extension Modules

Extension modules may be specified under the `dependencies` block of the [Gradle build script](build.gradle):

```gradle
dependencies {
    implementation "org.apereo.cas:cas-server-some-module"
    ...
}
```

To collect the list of all project modules and dependencies in the overlay:

```bash
./gradlew[.bat] dependencies
```

# Deployment

On a successful deployment via the following methods, the server will be available at:

* `https://localhost:8443/cas`


## Executable WAR

Run the server web application as an executable WAR. Note that running an executable WAR requires CAS to use an embedded container such as Apache Tomcat, Jetty, etc.

The current servlet container is specified as `-tomcat`.

```bash
java -jar build/libs/cas.war
```

Or via:

```bash
./gradlew[.bat] run
```

It is often an advantage to explode the generated web application and run it in unpacked mode.
One way to run an unpacked archive is by starting the appropriate launcher, as follows:

```bash
jar -xf build/libs/cas.war
cd build/libs
java org.springframework.boot.loader.launch.JarLauncher
```

This is slightly faster on startup (depending on the size of the WAR file) than
running from an unexploded archive. After startup, you should not expect any differences.

Debug the CAS web application as an executable WAR:

```bash
./gradlew[.bat] debug
```

Or via:

```bash
java -Xdebug -Xrunjdwp:transport=dt_socket,address=5000,server=y,suspend=y -jar build/libs/cas.war
```

Run the CAS web application as a *standalone* executable WAR:

```bash
./gradlew[.bat] clean executable
```

### CDS Support

CDS is a JVM feature that can help reduce the startup time and memory footprint of Java applications. CAS via Spring Boot
now has support for easy creation of a CDS friendly layout. This layout can be created by extracting the CAS web application file
with the help of the `tools` jarmode:

```bash
# Note: You must first build the web application with "executable" turned off
java -Djarmode=tools -jar build/libs/cas.war extract

# Perform a training run once
java -XX:ArchiveClassesAtExit=cas.jsa -Dspring.context.exit=onRefresh -jar cas/cas.war

# Run the CAS web application via CDS
java XX:SharedArchiveFile=cas.jsa -jar cas/cas.war
```

## External

Deploy the binary web application file in `build/libs` after a successful build to a servlet container of choice.

# Docker

The following strategies outline how to build and deploy CAS Docker images.

## Jib

The overlay embraces the [Jib Gradle Plugin](https://github.com/GoogleContainerTools/jib) to provide easy-to-use out-of-the-box tooling for building CAS docker images. Jib is an open-source Java containerizer from Google that lets Java developers build containers using the tools they know. It is a container image builder that handles all the steps of packaging your application into a container image. It does not require you to write a Dockerfile or have Docker installed, and it is directly integrated into the overlay.

```bash
# Running this task requires that you have Docker installed and running.
./gradlew build jibDockerBuild
```

## Dockerfile

You can also use the Docker tooling and the provided `Dockerfile` to build and run.
There are dedicated Gradle tasks available to build and push Docker images using the supplied `DockerFile`:

```bash
./gradlew build casBuildDockerImage
```

Once ready, you may also push the images:

```bash
./gradlew casPushDockerImage
```

If credentials (username+password) are required for pull and push operations, they may be specified
using system properties via `-DdockerUsername=...` and `-DdockerPassword=...`.

A `docker-compose.yml` is also provided to orchestrate the build:

```bash
docker-compose build
```

## Spring Boot

You can use the Spring Boot build plugin for Gradle to create CAS container images.
The plugins create an OCI image (the same format as one created by docker build)
by using [Cloud Native Buildpacks](https://buildpacks.io/). You do not need a Dockerfile, but you do need a Docker daemon,
either locally (which is what you use when you build with docker) or remotely
through the `DOCKER_HOST` environment variable. The default builder is optimized for
Spring Boot applications such as CAS, and the image is layered efficiently.

```bash
./gradlew bootBuildImage
```

The first build might take a long time because it has to download some container
images and the JDK, but subsequent builds should be fast.
