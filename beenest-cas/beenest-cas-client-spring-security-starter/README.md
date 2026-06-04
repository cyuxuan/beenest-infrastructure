# beenest-cas-client-spring-security-starter

Spring Boot Starter，为下游应用提供基于 Apereo CAS 的 SSO 认证集成。

## 快速接入

### 1. 引入依赖

```xml
<dependency>
    <groupId>club.beenest.cas</groupId>
    <artifactId>beenest-cas-client-spring-security-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 基础配置

```yaml
cas:
  client:
    enabled: true
    server-url: https://cas.beenest.club/cas
    client-host-url: https://your-app.beenest.club
    login-path: /login/cas
    logout-path: /logout
```

### 3. 认证模式

Starter 同时支持两种认证模式：

- **Web Session 模式** — 管理后台等浏览器场景，CAS SSO Cookie + Spring Security Session
- **Bearer Token 模式** — API / 移动端场景，通过 `Authorization: Bearer <token>` 认证

## 用户授权同步（访问控制 SPI）

### 问题

当管理员在 CAS Palantir 授权/撤销用户访问某应用时，下游应用如何感知变更？

### 解决方案

通过 `CasUserAccessControlService` SPI 接口，在用户登录/Token 刷新时自动检测 CAS 角色变更并同步本地账号状态。

### 接入步骤

**第一步：实现 SPI 接口**

```java
@Component
public class MyAppAccessControlService implements CasUserAccessControlService {

    @Override
    public String getRequiredRole() {
        return "ROLE_DRONE_SYSTEM";  // 本应用对应的 CAS 角色
    }

    @Override
    public boolean isLocalUserActive(String userId) {
        // 检查本地用户是否存在且可用
    }

    @Override
    public String createLocalUser(String userId, Set<String> casRoles,
                                   Map<String, Object> casAttributes) {
        // CAS 授权了本应用权限，但本地无用户 → 创建
    }

    @Override
    public void disableLocalUser(String userId, Set<String> casRoles) {
        // CAS 撤销了本应用权限，但本地有用户 → 禁用 + 强制下线
    }

    @Override
    public void updateLocalUser(String userId, Set<String> casRoles,
                                 Map<String, Object> casAttributes) {
        // 有权限且有本地用户 → 同步信息（可选，默认空实现）
    }
}
```

**第二步：启用配置**

```yaml
cas:
  client:
    access-control:
      enabled: true
```

### 工作原理

```
用户登录/Token 刷新
  ↓
Client Starter: CasAccessControlManager.onAuthentication()
  ├─ 有权限 + 无本地用户 → createLocalUser()     (自动注册)
  ├─ 无权限 + 有本地用户 → disableLocalUser()    (自动禁用)
  ├─ 有权限 + 有本地用户 → updateLocalUser()     (同步信息)
  └─ 无权限 + 无本地用户 → 拒绝访问
```

### 配置属性

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `cas.client.access-control.enabled` | `false` | 是否启用访问控制 SPI |
| `cas.client.access-control.required-role` | — | CAS 角色名（可覆盖 SPI 的 `getRequiredRole()`） |
| `cas.client.access-control.auto-create-on-grant` | `true` | 有权限但无本地用户时是否自动创建 |
| `cas.client.access-control.auto-disable-on-revoke` | `true` | 无权限但有本地用户时是否自动禁用 |
| `cas.client.access-control.force-logout-on-disable` | `true` | 禁用用户时是否同时强制下线 |

### 关键特性

- **零耦合** — CAS 不主动推送，无需配置回调 URL
- **向后兼容** — `enabled=false`（默认）时一切行为不变
- **异常降级** — SPI 实现抛异常时降级为拒绝，不阻断认证
- **与 CasUserRegistrationService 兼容** — SPI 激活时自动跳过旧注册逻辑

### 触发时机

| 场景 | 触发点 |
|------|--------|
| Web 管理后台登录 | `CasLoginSuccessHandler` |
| API Bearer Token 验证 | `CasBearerTokenAuthenticationProvider` |
| Token 刷新 | `CasBearerTokenAuthenticationProvider` |

## 其他功能

- **CAS SSO/SLO** — 自动配置 CAS 认证过滤器和单点登出
- **Bearer Token 认证** — 移动端/API Token 认证与自动刷新
- **用户自动注册** — 首次登录时通过 `CasUserRegistrationService` 创建本地用户
- **会话管理** — `ActiveSessionRegistry` 注册本地会话，支持 SLO 级联
- **权限版本感知** — `BearerAuthorityVersionService` 感知 CAS 角色变更并刷新缓存
