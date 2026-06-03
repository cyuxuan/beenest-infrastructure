# CAS 用户授权同步 SPI 设计

> 日期：2026-06-03
> 状态：待审核

## 1. 问题背景

CAS 作为统一认证中心，管理用户对各应用的访问权限（通过 `cas_user.roles` 字段 + Service JSON `accessStrategy.requiredAttributes.memberOf`）。当管理员在 Palantir 授权/撤销用户访问某应用时，CAS 只修改了自身数据库，下游应用系统无法感知变更。

下游应用（如 drone-system、beenest-payment）维护有独立的用户和权限体系。用户被授权访问某应用时，需要在下游创建对应账号；撤销时需要禁用账号并强制下线。

## 2. 设计原则

1. **零耦合**：CAS 不主动推送通知，不配置回调 URL，不依赖下游应用的 API
2. **SPI 驱动**：下游应用通过实现 SPI 接口自主决定同步行为
3. **登录时检测**：在用户认证（登录/Token 刷新）时，根据 CAS 返回的角色判断本地账号状态
4. **渐进式**：不实现 SPI 的应用保持现有行为不变，完全向后兼容

## 3. 核心数据流

**重要说明：** SPI 访问控制是 CAS `accessStrategy` **之后的补充检查**。用户能到达下游应用，说明已通过 CAS 的角色校验（`memberOf` 匹配）。SPI 的职责是确保下游本地账号与 CAS 权限状态一致，而非替代 CAS 的访问控制。第 4 种场景（无权限+无本地用户）理论上不应出现在正常流程中，仅作为防御性兜底。

```
┌──────────────────────────────────────────────────────────────────────┐
│  CAS Server                                                          │
│                                                                      │
│  管理员操作: grant/revoke → 修改 cas_user.roles                      │
│                                                                      │
│  用户认证时:                                                          │
│  BeenestPrincipalAttributesBuilder                                   │
│  → memberOf: ["ROLE_DRONE_SYSTEM", "ROLE_PAYMENT", "ROLE_PILOT"]    │
│  → tokenVersion: 3                                                   │
└───────────────────────────────┬──────────────────────────────────────┘
                                │
                                │ CAS Assertion / Bearer Token
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  Client Starter (下游应用)                                            │
│                                                                      │
│  CasUserAccessControlService (SPI)                                   │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  onAuthentication(userId, casRoles, localUserStatus):          │  │
│  │                                                                │  │
│  │  1. 本地无用户 + 有本应用角色 → createLocalUser()              │  │
│  │  2. 本地有用户 + 无本应用角色 → disableLocalUser() + logout() │  │
│  │  3. 本地有用户 + 有本应用角色 → updateLocalUser()             │  │
│  │  4. 本地无用户 + 无本应用角色 → 拒绝访问                      │  │
│  └────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
```

## 4. SPI 接口定义

### 4.1 CasUserAccessControlService

位于 `beenest-cas-client-spring-security-starter` 包 `org.apereo.cas.beenest.client.accesscontrol`。

```java
/**
 * CAS 用户访问控制 SPI。
 * <p>
 * 下游应用实现此接口，在用户认证时根据 CAS 角色决定本地账号状态。
 * Client Starter 在以下时机调用：
 * <ul>
 *   <li>用户首次 CAS 登录（Web Session 模式）</li>
 *   <li>Bearer Token 验证/刷新时（API 模式）</li>
 * </ul>
 * <p>
 * 不实现此接口的应用保持现有行为不变（向后兼容）。
 */
public interface CasUserAccessControlService {

    /**
     * 获取本应用对应的 CAS 角色名。
     * <p>
     * 用于判断 CAS 返回的 memberOf 中是否包含本应用的访问权限。
     * 例如 "ROLE_DRONE_SYSTEM" 或 "ROLE_PAYMENT"。
     * <p>
     * 也可通过配置 cas.client.access-control.required-role 覆盖。
     */
    String getRequiredRole();

    /**
     * 检查本地用户是否存在且可用。
     *
     * @param userId CAS 用户 ID
     * @return true 表示本地用户存在且状态正常
     */
    boolean isLocalUserActive(String userId);

    /**
     * 创建本地用户（首次授权时）。
     * <p>
     * 当 CAS 角色包含本应用权限，但本地用户不存在时调用。
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性（nickname, phone, email 等）
     * @return 创建的本地用户 ID，null 表示创建失败
     */
    String createLocalUser(String userId, Set<String> casRoles,
                           Map<String, Object> casAttributes);

    /**
     * 禁用本地用户（权限撤销时）。
     * <p>
     * 当本地用户存在，但 CAS 角色不再包含本应用权限时调用。
     * 实现应：禁用账号 + 销毁该用户的所有活跃会话。
     *
     * @param userId   CAS 用户 ID
     * @param casRoles CAS 返回的所有角色（不含本应用权限）
     */
    void disableLocalUser(String userId, Set<String> casRoles);

    /**
     * 更新本地用户信息（可选）。
     * <p>
     * 当用户有权限且本地账号正常时调用，可用于同步昵称、手机号等信息。
     * 默认空实现。
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性
     */
    default void updateLocalUser(String userId, Set<String> casRoles,
                                  Map<String, Object> casAttributes) {
        // 默认不更新
    }
}
```

### 4.2 CasAccessControlProperties

```java
/**
 * CAS 用户访问控制配置属性。
 */
@Data
@ConfigurationProperties(prefix = "cas.client.access-control")
public class CasAccessControlProperties {

    /** 是否启用访问控制 SPI（默认 false，向后兼容） */
    private boolean enabled = false;

    /** 本应用要求的 CAS 角色名（可覆盖 SPI 实现的 getRequiredRole()） */
    private String requiredRole;

    /** 有权限但无本地用户时是否自动创建（默认 true） */
    private boolean autoCreateOnGrant = true;

    /** 无权限但有本地用户时是否自动禁用（默认 true） */
    private boolean autoDisableOnRevoke = true;

    /** 禁用用户时是否同时强制下线（默认 true） */
    private boolean forceLogoutOnDisable = true;
}
```

### 4.3 CasAccessControlManager（内部协调器）

```java
/**
 * CAS 用户访问控制协调器。
 * <p>
 * Client Starter 内部组件，在认证成功后调用 SPI 实现进行访问控制判断。
 * 不暴露给下游应用，下游应用只需实现 CasUserAccessControlService。
 */
@Slf4j
class CasAccessControlManager {

    private final CasUserAccessControlService accessControlService;
    private final CasAccessControlProperties properties;

    /**
     * 认证成功后执行访问控制检查。
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性
     * @return 访问控制结果
     */
    AccessControlResult onAuthentication(String userId, Set<String> casRoles,
                                          Map<String, Object> casAttributes) {
        String requiredRole = resolveRequiredRole();
        boolean hasAccess = casRoles.contains(requiredRole);
        boolean localExists = accessControlService.isLocalUserActive(userId);

        // 1. 有权限 + 无本地用户 → 自动创建
        if (hasAccess && !localExists) {
            if (properties.isAutoCreateOnGrant()) {
                String localId = accessControlService.createLocalUser(
                    userId, casRoles, casAttributes);
                if (localId != null) {
                    log.info("访问控制: 自动创建本地用户 userId={}, localId={}",
                             userId, localId);
                    return AccessControlResult.granted(localId);
                }
                log.warn("访问控制: 本地用户创建失败 userId={}", userId);
                return AccessControlResult.denied("本地用户创建失败");
            }
            return AccessControlResult.denied("本地用户不存在");
        }

        // 2. 无权限 + 有本地用户 → 自动禁用
        if (!hasAccess && localExists) {
            if (properties.isAutoDisableOnRevoke()) {
                accessControlService.disableLocalUser(userId, casRoles);
                log.info("访问控制: CAS 角色已撤销，禁用本地用户 userId={}", userId);
                return AccessControlResult.denied("访问权限已撤销");
            }
            return AccessControlResult.denied("无访问权限");
        }

        // 3. 无权限 + 无本地用户 → 拒绝
        if (!hasAccess && !localExists) {
            return AccessControlResult.denied("无访问权限");
        }

        // 4. 有权限 + 有本地用户 → 更新信息
        accessControlService.updateLocalUser(userId, casRoles, casAttributes);
        return AccessControlResult.granted(userId);
    }

    /**
     * 解析本应用要求的角色名。
     * 配置属性优先，其次 SPI 实现。
     */
    private String resolveRequiredRole() {
        if (StringUtils.hasText(properties.getRequiredRole())) {
            return properties.getRequiredRole();
        }
        return accessControlService.getRequiredRole();
    }

    /**
     * 访问控制结果
     */
    record AccessControlResult(boolean granted, String userId, String reason) {
        static AccessControlResult granted(String userId) {
            return new AccessControlResult(true, userId, null);
        }
        static AccessControlResult denied(String reason) {
            return new AccessControlResult(false, null, reason);
        }
    }
}
```

## 5. 触发时机与集成点

### 5.1 Web Session 模式（管理后台）

**集成点：** `CasLoginSuccessHandler.onAuthenticationSuccess()`

```
用户 CAS 登录成功
  → CasLoginSuccessHandler
    → CasAccessControlManager.onAuthentication()
      → SPI: isLocalUserActive() / createLocalUser() / disableLocalUser()
      → 结果为 denied → 清除 Session + 返回 403
      → 结果为 granted → 继续正常流程
```

### 5.2 Bearer Token 模式（API / 移动端）

**集成点：** `CasBearerTokenAuthenticationProvider.authenticate()`

```
Bearer Token 验证
  → CasBearerTokenAuthenticationProvider
    → 校验 Token 有效性
    → CasAccessControlManager.onAuthentication()
      → SPI: isLocalUserActive() / createLocalUser() / disableLocalUser()
      → 结果为 denied → 抛出 AuthenticationException
      → 结果为 granted → 构造 CasUserDetails 返回
```

### 5.3 Token 刷新模式

**集成点：** `CasTokenRefresher.refresh()`

```
Token 刷新
  → CasTokenRefresher
    → 向 CAS 请求新 Token
    → CasAccessControlManager.onAuthentication()
      → SPI: isLocalUserActive() / createLocalUser() / disableLocalUser()
      → 结果为 denied → 拒绝刷新 + 清除本地缓存
      → 结果为 granted → 返回新 Token
```

## 6. CAS 侧变更

CAS 侧变更极小，仅需确保 `BeenestPrincipalAttributesBuilder` 将 `tokenVersion` 包含在 CAS 属性中，以便 Client Starter 感知权限变更。

### 6.1 BeenestPrincipalAttributesBuilder 扩展

```java
public static Map<String, List<Object>> buildAttributes(UnifiedUserDO user) {
    var memberOf = new ArrayList<String>();
    // ... 现有角色构建逻辑 ...

    var attrs = new HashMap<String, List<Object>>();
    attrs.put("memberOf", List.copyOf(memberOf));

    // 新增：tokenVersion 用于 Client Starter 感知权限变更
    if (user.getTokenVersion() != null) {
        attrs.put("tokenVersion", List.of(String.valueOf(user.getTokenVersion())));
    }

    return Map.copyOf(attrs);
}
```

### 6.2 ServiceAuthorizationEndpoint 变更

grant/revoke 操作后递增 `tokenVersion`，使已发放的 Token 在刷新时触发重新检查：

```java
@PostMapping(value = "/grant", ...)
public R<OperationResultVO> grantAccess(@RequestBody AccessRequestDTO request) {
    // ... 现有逻辑 ...
    userMapper.addRole(userId, role);
    userMapper.incrementTokenVersion(userId);  // 新增
    // ...
}

@PostMapping(value = "/revoke", ...)
public R<OperationResultVO> revokeAccess(@RequestBody AccessRequestDTO request) {
    // ... 现有逻辑 ...
    userMapper.removeRole(userId, role);
    userMapper.incrementTokenVersion(userId);  // 新增
    // ...
}
```

### 6.3 UnifiedUserMapper 新增方法

```java
/** 递增用户 tokenVersion */
void incrementTokenVersion(@Param("userId") String userId);
```

## 7. Client Starter 侧变更

### 7.1 新增文件

| 文件 | 说明 |
|------|------|
| `accesscontrol/CasUserAccessControlService.java` | SPI 接口 |
| `accesscontrol/CasAccessControlProperties.java` | 配置属性 |
| `accesscontrol/CasAccessControlManager.java` | 内部协调器 |
| `accesscontrol/CasAccessControlAutoConfiguration.java` | 自动配置 |
| `accesscontrol/CasAccessControlDeniedHandler.java` | 拒绝处理 |

### 7.2 修改文件

| 文件 | 变更 |
|------|------|
| `session/CasLoginSuccessHandler.java` | 注入 CasAccessControlManager，认证成功后调用 |
| `authentication/CasBearerTokenAuthenticationProvider.java` | 注入 CasAccessControlManager，Token 验证后调用 |
| `authentication/CasTokenRefresher.java` | 注入 CasAccessControlManager，Token 刷新后调用 |
| `config/CasSecurityAutoConfiguration.java` | 导入 CasAccessControlAutoConfiguration |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 注册自动配置 |

### 7.3 自动配置条件

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "cas.client.access-control", name = "enabled",
                        havingValue = "true")
@ConditionalOnBean(CasUserAccessControlService.class)
@EnableConfigurationProperties(CasAccessControlProperties.class)
public class CasAccessControlAutoConfiguration {
    // 注册 CasAccessControlManager Bean
}
```

关键点：**只有同时满足 `enabled=true` + 提供了 SPI 实现时才激活**，不满足则完全不影响现有行为。

## 8. 下游应用接入示例

以 drone-system 为例：

### 8.1 实现 SPI

```java
@Component
public class DroneSystemAccessControlService implements CasUserAccessControlService {

    private final SysUserMapper sysUserMapper;
    private final SessionRegistry sessionRegistry;

    @Override
    public String getRequiredRole() {
        return "ROLE_DRONE_SYSTEM";
    }

    @Override
    public boolean isLocalUserActive(String userId) {
        SysUser user = sysUserMapper.selectByCasUserId(userId);
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
        sysUserMapper.insert(user);
        return String.valueOf(user.getId());
    }

    @Override
    public void disableLocalUser(String userId, Set<String> casRoles) {
        // 1. 禁用账号
        sysUserMapper.updateStatusByCasUserId(userId, 0);
        // 2. 强制下线所有会话
        for (var session : sessionRegistry.getAllSessions(userId, false)) {
            session.expireNow();
        }
    }

    @Override
    public void updateLocalUser(String userId, Set<String> casRoles,
                                 Map<String, Object> casAttributes) {
        // 同步昵称、手机号等基本信息
        sysUserMapper.updateProfileByCasUserId(userId, casAttributes);
    }
}
```

### 8.2 配置

```yaml
cas:
  client:
    enabled: true
    access-control:
      enabled: true
      # required-role 不配置，由 SPI 的 getRequiredRole() 提供
      auto-create-on-grant: true
      auto-disable-on-revoke: true
      force-logout-on-disable: true
```

## 9. 边界场景处理

| 场景 | 处理方式 |
|------|----------|
| SPI 实现抛异常 | Client Starter 捕获异常，记录日志，降级为放行（避免因同步失败阻断认证） |
| CAS 角色变更但用户未登录 | 用户下次登录/Token 刷新时自动检测并处理 |
| 并发创建本地用户 | SPI 实现需自行处理幂等性（如数据库 UNIQUE 约束） |
| 禁用用户后 CAS 又重新授权 | 下次登录时 `isLocalUserActive()` 返回 false，触发 `createLocalUser()` 重新创建 |
| 多实例部署 | SPI 实现操作数据库，天然支持多实例；会话销毁通过 Spring Security SessionRegistry 或 Redis 集群 |
| 不实现 SPI 的应用 | `CasAccessControlAutoConfiguration` 不激活，完全不影响现有行为 |

## 10. 与现有 CasUserRegistrationService 的关系

`CasUserRegistrationService` 是现有的首次登录自动注册接口，功能与 `CasUserAccessControlService.createLocalUser()` 有重叠。

**策略：** 保留 `CasUserRegistrationService` 不变（向后兼容），当 `CasUserAccessControlService` 激活时，优先使用后者。`DefaultCasUserDetailsService` 中的 `maybeRegisterLocalUser()` 逻辑在访问控制激活后跳过，避免重复创建。

```java
// DefaultCasUserDetailsService 中
if (accessControlManager != null && accessControlManager.isEnabled()) {
    // 访问控制 SPI 已激活，由 CasAccessControlManager 统一处理
    // 不再调用 CasUserRegistrationService
} else {
    // 兼容旧逻辑
    maybeRegisterLocalUser(session, registrationService);
}
```

## 11. 实现优先级

1. **P0 — SPI 接口 + 协调器 + 自动配置**：Client Starter 核心机制
2. **P0 — CAS 侧 tokenVersion 递增**：确保权限变更可感知
3. **P1 — 集成到 CasLoginSuccessHandler**：Web 管理后台场景
4. **P1 — 集成到 CasBearerTokenAuthenticationProvider**：API 场景
5. **P2 — 集成到 CasTokenRefresher**：Token 刷新场景
6. **P2 — drone-system 接入示例**：验证端到端流程
