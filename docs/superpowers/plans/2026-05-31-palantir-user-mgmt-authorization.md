# Palantir 用户管理与服务授权 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 CAS Palantir Dashboard 中实现用户管理（Account Management tab）和服务授权（Service Authorization tab），删除空壳代码，将 BeenestPrincipalAttributesBuilder 接入所有认证 Handler。

**Architecture:** CAS 原生 Actuator Endpoint 模式提供后端 API（CasUsersEndpoint + ServiceAuthorizationEndpoint），Palantir 前端通过 `actuatorEndpoints` JS 对象调用。授权通过 `cas_user.roles` + Service JSON `requiredAttributes.memberOf` 实现。认证 Handler 统一调用 `BeenestPrincipalAttributesBuilder.buildAttributes()` 构建 memberOf 属性。

**Tech Stack:** Java 21, Apereo CAS 7.3.6 Actuator Endpoint, Thymeleaf, vanilla JS (Palantir pattern), MyBatis, PostgreSQL

---

## File Structure

### Create
- `src/main/java/org/apereo/cas/beenest/endpoint/CasUsersEndpoint.java` — Actuator 端点：查看/管理 cas_user + 创建邀请
- `src/main/java/org/apereo/cas/beenest/endpoint/ServiceAuthorizationEndpoint.java` — Actuator 端点：授权/撤销/查看服务访问
- `src/main/java/org/apereo/cas/beenest/config/AutoGrantProperties.java` — auto-grant 配置属性类
- `src/main/resources/templates/fragments/palantir/accounttab.html` — 用户管理 tab 模板
- `src/main/resources/templates/fragments/palantir/authorizationtab.html` — 服务授权 tab 模板
- `src/main/resources/static/js/beenest-account-mgmt.js` — 用户管理 tab 前端交互
- `src/main/resources/static/js/beenest-service-auth.js` — 服务授权 tab 前端交互

### Modify
- `src/main/java/org/apereo/cas/beenest/authn/handler/UsernamePasswordAuthenticationHandler.java` — 接入 BeenestPrincipalAttributesBuilder
- `src/main/java/org/apereo/cas/beenest/authn/handler/WechatMiniAuthenticationHandler.java` — 同上
- `src/main/java/org/apereo/cas/beenest/authn/handler/DouyinMiniAuthenticationHandler.java` — 同上
- `src/main/java/org/apereo/cas/beenest/authn/handler/AlipayMiniAuthenticationHandler.java` — 同上
- `src/main/java/org/apereo/cas/beenest/authn/handler/SmsOtpAuthenticationHandler.java` — 同上
- `src/main/java/org/apereo/cas/beenest/service/BeenestAccountRegistrationProvisioner.java` — 注册时赋予 auto-grant 角色
- `src/main/java/org/apereo/cas/beenest/service/UserIdentityService.java` — 小程序注册时赋予 auto-grant 角色
- `src/main/java/org/apereo/cas/beenest/controller/AppLoginController.java` — 移除 AppAccessService 依赖，改用 UnifiedUserMapper.addRole
- `src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java` — 移除 AppAccessService Bean，注册 AutoGrantProperties
- `src/main/java/org/apereo/cas/config/CasOverlayOverrideConfiguration.java` — 移除 BeenestAccessStrategyInitializer，注册 Endpoint Bean
- `src/main/resources/application.yml` — exposure.include 追加 casUsers,serviceAuthorization；添加 auto-grant-roles
- `src/main/resources/templates/palantir/casPalantirDashboardView.html` — 添加 Account/Authorization tab fragment
- `src/main/resources/templates/fragments/palantir/navigationsidebar.html` — 添加导航项
- `src/main/resources/templates/fragments/palantir/dashboardtabs.html` — 添加 tab 按钮

### Delete
- `src/main/java/org/apereo/cas/beenest/authn/strategy/BeenestAccessStrategy.java` — 空壳，改用原生 DefaultRegisteredServiceAccessStrategy
- `src/main/java/org/apereo/cas/beenest/service/AppAccessService.java` — 空壳，逻辑由原生 requiredAttributes + cas_user.roles 替代

---

### Task 1: 删除空壳代码 (BeenestAccessStrategy + AppAccessService)

**Files:**
- Delete: `src/main/java/org/apereo/cas/beenest/authn/strategy/BeenestAccessStrategy.java`
- Delete: `src/main/java/org/apereo/cas/beenest/service/AppAccessService.java`
- Modify: `src/main/java/org/apereo/cas/config/CasOverlayOverrideConfiguration.java`
- Modify: `src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java`
- Modify: `src/main/java/org/apereo/cas/beenest/controller/AppLoginController.java`

- [ ] **Step 1: 从 CasOverlayOverrideConfiguration 移除 BeenestAccessStrategy 相关代码**

删除 import、删除 `beenestAccessStrategyInitializer` Bean 方法、删除 `BeenestAccessStrategyInitializer` 内部类。

- [ ] **Step 2: 从 BeenestServiceConfiguration 积除 AppAccessService Bean**

删除 import 和 `appAccessService()` Bean 方法。

- [ ] **Step 3: 重构 AppLoginController 积除 AppAccessService 依赖**

移除 `AppAccessService` import 和字段。移除 `appAccessService.autoGrantOnRegister(principal.getId(), serviceId)` 调用（auto-grant 将在 Task 5 中通过 UserIdentityService 实现）。移除构造函数中的 `AppAccessService` 参数。

- [ ] **Step 4: 删除空壳文件**

```bash
rm src/main/java/org/apereo/cas/beenest/authn/strategy/BeenestAccessStrategy.java
rm src/main/java/org/apereo/cas/beenest/service/AppAccessService.java
```

- [ ] **Step 5: 验证编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:compileJava --offline --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor(cas): 删除 BeenestAccessStrategy 和 AppAccessService 空壳代码

改用 CAS 原生 DefaultRegisteredServiceAccessStrategy + requiredAttributes"
```

---

### Task 2: 创建 AutoGrantProperties 配置类

**Files:**
- Create: `src/main/java/org/apereo/cas/beenest/config/AutoGrantProperties.java`
- Modify: `src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 创建 AutoGrantProperties**

```java
package org.apereo.cas.beenest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

/**
 * 自动赋权配置属性。
 * <p>
 * 新注册用户自动赋予指定 Service 对应的角色。
 */
@Data
@ConfigurationProperties(prefix = "beenest.user")
public class AutoGrantProperties {

    /** 自动赋权的 Service ID 列表（逗号分隔） */
    private Set<Long> autoGrantServiceIds = Set.of(10001L);

    /** Service ID → 角色名映射，如 10001 → ROLE_DRONE_SYSTEM */
    private Map<Long, String> autoGrantRoles = Map.of(
            10001L, "ROLE_DRONE_SYSTEM",
            10003L, "ROLE_PAYMENT"
    );
}
```

- [ ] **Step 2: 在 BeenestServiceConfiguration 注册 AutoGrantProperties**

添加 import 和 `@EnableConfigurationProperties(AutoGrantProperties.class)` 注解到类上。

- [ ] **Step 3: 更新 application.yml auto-grant 配置**

在 `beenest.user` 下添加 `auto-grant-roles` 映射：

```yaml
beenest:
  user:
    auto-grant-service-ids: ${BEENEST_AUTO_GRANT_SERVICE_IDS:10001}
    auto-grant-roles:
      10001: ROLE_DRONE_SYSTEM
      10003: ROLE_PAYMENT
```

- [ ] **Step 4: 验证编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:compileJava --offline --no-daemon 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(cas): 新增 AutoGrantProperties 自动赋权配置类"
```

---

### Task 3: 将 BeenestPrincipalAttributesBuilder 接入所有认证 Handler

**Files:**
- Modify: `src/main/java/org/apereo/cas/beenest/authn/handler/UsernamePasswordAuthenticationHandler.java`
- Modify: `src/main/java/org/apereo/cas/beenest/authn/handler/WechatMiniAuthenticationHandler.java`
- Modify: `src/main/java/org/apereo/cas/beenest/authn/handler/DouyinMiniAuthenticationHandler.java`
- Modify: `src/main/java/org/apereo/cas/beenest/authn/handler/AlipayMiniAuthenticationHandler.java`
- Modify: `src/main/java/org/apereo/cas/beenest/authn/handler/SmsOtpAuthenticationHandler.java`

所有 5 个 Handler 的 `buildUserAttributes` 方法需要合并 `BeenestPrincipalAttributesBuilder.buildAttributes(user)` 返回的 `memberOf` 到 attributes Map 中。

- [ ] **Step 1: 修改 UsernamePasswordAuthenticationHandler.buildUserAttributes()**

在现有 `buildUserAttributes` 方法末尾、return 之前，添加：

```java
// 合并 memberOf 属性（基础角色 + 应用角色）
var memberOfAttrs = BeenestPrincipalAttributesBuilder.buildAttributes(user);
attrs.putAll(memberOfAttrs);
```

添加 import: `import org.apereo.cas.beenest.service.BeenestPrincipalAttributesBuilder;`

- [ ] **Step 2: 修改 WechatMiniAuthenticationHandler.buildUserAttributes()**

同上，在 `buildUserAttributes` 方法末尾添加 memberOf 合并逻辑和 import。

- [ ] **Step 3: 修改 DouyinMiniAuthenticationHandler.buildUserAttributes()**

同上。

- [ ] **Step 4: 修改 AlipayMiniAuthenticationHandler.buildResult() 中的 attributes 构建**

在 `buildResult` 方法的 attributes 构建末尾添加 memberOf 合并逻辑和 import。

- [ ] **Step 5: 修改 SmsOtpAuthenticationHandler.buildUserAttributes()**

同上。

- [ ] **Step 6: 验证编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:compileJava --offline --no-daemon 2>&1 | tail -20
```

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(cas): 所有认证 Handler 接入 BeenestPrincipalAttributesBuilder 构建 memberOf 属性"
```

---

### Task 4: 注册时自动赋权 (Provisioner + UserIdentityService)

**Files:**
- Modify: `src/main/java/org/apereo/cas/beenest/service/BeenestAccountRegistrationProvisioner.java`
- Modify: `src/main/java/org/apereo/cas/beenest/service/UserIdentityService.java`
- Modify: `src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java`

- [ ] **Step 1: 修改 BeenestAccountRegistrationProvisioner 添加 auto-grant 逻辑**

添加 `AutoGrantProperties` 和 `UnifiedUserMapper` 依赖。在 `provision()` 方法中，`userMapper.insert(user)` 之后，遍历 `autoGrantProperties.getAutoGrantRoles()` 对每个 serviceId 对应的 role 调用 `userMapper.addRole(user.getUserId(), role)`。

构造函数改为：
```java
public BeenestAccountRegistrationProvisioner(UnifiedUserMapper userMapper, AutoGrantProperties autoGrantProperties)
```

在 `provision()` 中 insert 后添加：
```java
// 自动赋权：为新建用户赋予 auto-grant 角色
for (var entry : autoGrantProperties.getAutoGrantRoles().entrySet()) {
    if (autoGrantProperties.getAutoGrantServiceIds().contains(entry.getKey())) {
        userMapper.addRole(user.getUserId(), entry.getValue());
    }
}
```

- [ ] **Step 2: 修改 UserIdentityService 添加 auto-grant 逻辑**

添加 `AutoGrantProperties` 字段。在每个 `createXxxUser` 方法的 `safeCreate(user)` 之后，调用 auto-grant 逻辑。

添加私有方法：
```java
private void autoGrantRoles(String userId) {
    if (autoGrantProperties == null) return;
    for (var entry : autoGrantProperties.getAutoGrantRoles().entrySet()) {
        if (autoGrantProperties.getAutoGrantServiceIds().contains(entry.getKey())) {
            userMapper.addRole(userId, entry.getValue());
        }
    }
}
```

在每个 `findOrRegisterByXxxResult` 方法中，当 `firstLogin = true` 时调用 `autoGrantRoles(user.getUserId())`。

- [ ] **Step 3: 更新 BeenestServiceConfiguration 中的 Bean 注册**

更新 `userIdentityService` Bean 传入 `AutoGrantProperties`：
```java
@Bean
public UserIdentityService userIdentityService(final UnifiedUserMapper userMapper,
                                                final AutoGrantProperties autoGrantProperties) {
    return new UserIdentityService(userMapper, autoGrantProperties);
}
```

更新 `beenestAccountRegistrationProvisionerConfigurer` Bean 传入 `AutoGrantProperties`：
```java
@Bean
public AccountRegistrationProvisionerConfigurer beenestAccountRegistrationProvisionerConfigurer(
        final UnifiedUserMapper userMapper,
        final AutoGrantProperties autoGrantProperties) {
    return () -> new BeenestAccountRegistrationProvisioner(userMapper, autoGrantProperties);
}
```

- [ ] **Step 4: 验证编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:compileJava --offline --no-daemon 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(cas): 注册时自动赋权 — Provisioner 和 UserIdentityService 集成 auto-grant"
```

---

### Task 5: 创建 CasUsersEndpoint (Actuator Endpoint)

**Files:**
- Create: `src/main/java/org/apereo/cas/beenest/endpoint/CasUsersEndpoint.java`
- Modify: `src/main/java/org/apereo/cas/config/CasOverlayOverrideConfiguration.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 创建 CasUsersEndpoint**

```java
package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.acct.AccountRegistrationRequest;
import org.apereo.cas.acct.AccountRegistrationResponse;
import org.apereo.cas.acct.provision.AccountRegistrationProvisioner;
import org.apereo.cas.beenest.common.constant.CasConstant;
import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.*;

/**
 * CAS 用户管理 Actuator 端点。
 * <p>
 * 提供 cas_user 表的查看、管理（禁用/启用/解锁/重置密码）和邀请注册功能。
 * Palantir 前端通过 actuatorEndpoints.casUsers 调用。
 */
@Slf4j
@Endpoint(id = "casUsers")
public class CasUsersEndpoint {

    private final UnifiedUserMapper userMapper;
    private final AccountRegistrationProvisioner registrationProvisioner;

    public CasUsersEndpoint(UnifiedUserMapper userMapper,
                            AccountRegistrationProvisioner registrationProvisioner) {
        this.userMapper = userMapper;
        this.registrationProvisioner = registrationProvisioner;
    }

    /**
     * 列出用户（分页、搜索、状态过滤）
     */
    @ReadOperation
    public Map<String, Object> listUsers(String query, Integer status, Integer page, Integer size) {
        int p = page != null ? page : 0;
        int s = size != null ? size : 20;
        long offset = (long) p * s;

        List<UnifiedUserDO> users = userMapper.selectAllPaged(query, status, offset, s + 1);
        long total = userMapper.countByQuery(query, status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", users.subList(0, Math.min(users.size(), s)));
        result.put("total", total);
        result.put("page", p);
        result.put("size", s);
        result.put("hasMore", users.size() > s);
        return result;
    }

    /**
     * 获取用户详情
     */
    @ReadOperation
    public Map<String, Object> getUser(@Selector String userId) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }
        return toUserMap(user);
    }

    /**
     * 管理员添加用户（创建邀请，发送开通链接）
     */
    @WriteOperation
    public Map<String, Object> createInvitation(@Selector String username,
                                                 String email,
                                                 String phone,
                                                 String roles) {
        // 1. 构造 CAS 原生 AccountRegistrationRequest
        var request = new AccountRegistrationRequest();
        request.put("username", username);
        if (StringUtils.isNotBlank(email)) {
            request.put("email", email);
        }
        if (StringUtils.isNotBlank(phone)) {
            request.put("phone", phone);
        }
        if (StringUtils.isNotBlank(roles)) {
            request.put("roles", roles);
        }

        // 2. 调用原生注册落库器
        AccountRegistrationResponse response = registrationProvisioner.provision(request);
        boolean success = response.isSuccess();

        if (success) {
            LOGGER.info("管理员邀请注册成功: username={}, email={}", username, email);
            return Map.of(
                    "success", true,
                    "userId", response.getProperty("userId", ""),
                    "message", "用户创建成功"
            );
        }

        LOGGER.warn("管理员邀请注册失败: username={}", username);
        return Map.of(
                "success", false,
                "message", response.getProperty("message", "注册失败")
        );
    }

    /**
     * 更新用户状态（禁用/启用/解锁/强制改密）
     */
    @WriteOperation
    public Map<String, Object> updateUser(@Selector String userId,
                                           Integer status,
                                           String action,
                                           Boolean mustChangePassword) {
        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }

        // 1. 状态变更
        if (status != null) {
            if (status != CasConstant.USER_STATUS_ACTIVE
                    && status != CasConstant.USER_STATUS_DISABLED
                    && status != CasConstant.USER_STATUS_LOCKED) {
                return Map.of("error", "无效的状态值", "status", status);
            }
            userMapper.updateStatus(userId, status);
            LOGGER.info("用户状态变更: userId={}, status={}", userId, status);
        }

        // 2. 解锁操作
        if ("unlock".equals(action)) {
            userMapper.resetFailedLoginCount(userId);
            userMapper.updateStatus(userId, CasConstant.USER_STATUS_ACTIVE);
            LOGGER.info("管理员解锁账号: userId={}", userId);
        }

        // 3. 强制改密
        if (Boolean.TRUE.equals(mustChangePassword)) {
            userMapper.updateMustChangePassword(userId, true);
            LOGGER.info("管理员强制用户改密: userId={}", userId);
        }

        UnifiedUserDO updated = userMapper.selectByUserId(userId);
        return toUserMap(updated);
    }

    private Map<String, Object> toUserMap(UnifiedUserDO user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("phone", user.getPhone());
        map.put("email", user.getEmail());
        map.put("userType", user.getUserType());
        map.put("status", user.getStatus());
        map.put("roles", user.getRoles());
        map.put("lastLoginTime", user.getLastLoginTime());
        map.put("createdTime", user.getCreatedTime());
        map.put("failedLoginCount", user.getFailedLoginCount());
        map.put("lockUntilTime", user.getLockUntilTime());
        return map;
    }
}
```

- [ ] **Step 2: 在 CasOverlayOverrideConfiguration 注册 CasUsersEndpoint Bean**

添加 import 和 Bean 方法：
```java
@Bean
public CasUsersEndpoint casUsersEndpoint(final UnifiedUserMapper userMapper,
                                          final AccountRegistrationProvisioner registrationProvisioner) {
    return new CasUsersEndpoint(userMapper, registrationProvisioner);
}
```

- [ ] **Step 3: 更新 application.yml exposure.include 追加 casUsers**

在现有 `exposure.include` 列表末尾追加 `,casUsers,serviceAuthorization`。

- [ ] **Step 4: 验证编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:compileJava --offline --no-daemon 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(cas): 新增 CasUsersEndpoint Actuator 端点 — 用户查看/管理/邀请注册"
```

---

### Task 6: 创建 ServiceAuthorizationEndpoint (Actuator Endpoint)

**Files:**
- Create: `src/main/java/org/apereo/cas/beenest/endpoint/ServiceAuthorizationEndpoint.java`
- Modify: `src/main/java/org/apereo/cas/config/CasOverlayOverrideConfiguration.java`

- [ ] **Step 1: 创建 ServiceAuthorizationEndpoint**

```java
package org.apereo.cas.beenest.endpoint;

import org.apereo.cas.beenest.entity.UnifiedUserDO;
import org.apereo.cas.beenest.mapper.UnifiedUserMapper;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.*;

/**
 * 服务授权管理 Actuator 端点。
 * <p>
 * 管理用户对服务的访问权限，底层操作 cas_user.roles 字段。
 * 角色名从 Service JSON 的 accessStrategy.requiredAttributes.memberOf 提取。
 */
@Slf4j
@Endpoint(id = "serviceAuthorization")
public class ServiceAuthorizationEndpoint {

    private final ServicesManager servicesManager;
    private final UnifiedUserMapper userMapper;

    public ServiceAuthorizationEndpoint(ServicesManager servicesManager,
                                         UnifiedUserMapper userMapper) {
        this.servicesManager = servicesManager;
        this.userMapper = userMapper;
    }

    /**
     * 列出所有已注册应用及其角色要求
     */
    @ReadOperation
    public List<Map<String, Object>> listServices() {
        var services = new ArrayList<Map<String, Object>>();
        for (RegisteredService svc : servicesManager.getAllServices()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", svc.getId());
            info.put("name", svc.getName());
            info.put("serviceId", svc.getServiceId());
            info.put("description", svc.getDescription());
            info.put("requiredRole", extractRequiredRole(svc));
            services.add(info);
        }
        services.sort(Comparator.comparingLong(m -> (Long) m.get("id")));
        return services;
    }

    /**
     * 列出有权限访问该服务的用户
     */
    @ReadOperation
    public Map<String, Object> getServiceUsers(@Selector long serviceId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return Map.of("error", "服务不存在", "serviceId", serviceId);
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            // 无角色要求 = 所有已认证用户可访问
            return Map.of("serviceId", serviceId, "name", svc.getName(),
                    "requiredRole", "无", "users", List.of(), "openAccess", true);
        }

        List<UnifiedUserDO> users = userMapper.selectByRole(role);
        List<Map<String, Object>> userList = new ArrayList<>();
        for (UnifiedUserDO u : users) {
            userList.add(toUserInfo(u));
        }
        return Map.of("serviceId", serviceId, "name", svc.getName(),
                "requiredRole", role, "users", userList, "openAccess", false);
    }

    /**
     * 授权用户访问服务
     */
    @WriteOperation
    public Map<String, Object> grantAccess(@Selector long serviceId,
                                            String userId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return Map.of("error", "服务不存在", "serviceId", serviceId);
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            return Map.of("error", "该服务无角色要求，所有已认证用户均可访问", "serviceId", serviceId);
        }

        UnifiedUserDO user = userMapper.selectByUserId(userId);
        if (user == null) {
            return Map.of("error", "用户不存在", "userId", userId);
        }

        int updated = userMapper.addRole(userId, role);
        if (updated > 0) {
            LOGGER.info("授权用户访问服务: userId={}, serviceId={}, role={}", userId, serviceId, role);
        }
        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        return Map.of("success", true, "userId", userId, "roles", refreshed.getRoles());
    }

    /**
     * 撤销用户服务访问
     */
    @DeleteOperation
    public Map<String, Object> revokeAccess(@Selector long serviceId,
                                             String userId) {
        RegisteredService svc = servicesManager.findServiceBy(serviceId);
        if (svc == null) {
            return Map.of("error", "服务不存在", "serviceId", serviceId);
        }
        String role = extractRequiredRole(svc);
        if (role == null) {
            return Map.of("error", "该服务无角色要求", "serviceId", serviceId);
        }

        int updated = userMapper.removeRole(userId, role);
        if (updated > 0) {
            LOGGER.info("撤销用户服务访问: userId={}, serviceId={}, role={}", userId, serviceId, role);
        }
        UnifiedUserDO refreshed = userMapper.selectByUserId(userId);
        return Map.of("success", true, "userId", userId, "roles", refreshed.getRoles());
    }

    /**
     * 从 Service JSON 的 accessStrategy.requiredAttributes.memberOf 提取角色名
     */
    private String extractRequiredRole(RegisteredService svc) {
        try {
            var strategy = svc.getAccessStrategy();
            if (strategy == null) return null;
            var requiredAttrs = strategy.getRequiredAttributes();
            if (requiredAttrs == null) return null;
            var memberOfValues = requiredAttrs.get("memberOf");
            if (memberOfValues == null || memberOfValues.isEmpty()) return null;
            // 取第一个角色值
            return memberOfValues.iterator().next().getValue().toString();
        } catch (Exception e) {
            LOGGER.debug("提取 Service 角色失败: serviceId={}, error={}", svc.getId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toUserInfo(UnifiedUserDO user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUsername());
        map.put("nickname", user.getNickname());
        map.put("userType", user.getUserType());
        map.put("status", user.getStatus());
        map.put("roles", user.getRoles());
        return map;
    }
}
```

- [ ] **Step 2: 在 CasOverlayOverrideConfiguration 注册 ServiceAuthorizationEndpoint Bean**

```java
@Bean
public ServiceAuthorizationEndpoint serviceAuthorizationEndpoint(
        final ServicesManager servicesManager,
        final UnifiedUserMapper userMapper) {
    return new ServiceAuthorizationEndpoint(servicesManager, userMapper);
}
```

- [ ] **Step 3: 验证编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:compileJava --offline --no-daemon 2>&1 | tail -20
```

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(cas): 新增 ServiceAuthorizationEndpoint — 服务授权/撤销/查看"
```

---

### Task 7: Palantir 前端 — 用户管理 Tab (accounttab.html + beenest-account-mgmt.js)

**Files:**
- Create: `src/main/resources/templates/fragments/palantir/accounttab.html`
- Create: `src/main/resources/static/js/beenest-account-mgmt.js`
- Modify: `src/main/resources/templates/palantir/casPalantirDashboardView.html`
- Modify: `src/main/resources/templates/fragments/palantir/navigationsidebar.html`
- Modify: `src/main/resources/templates/fragments/palantir/dashboardtabs.html`

- [ ] **Step 1: 创建 accounttab.html**

参考 Palantir 原生 `servicestab.html` 的 DataTable 风格。使用 `th:fragment="main"` 和 `class="d-none"` 初始隐藏。包含用户列表表格、搜索框、分页、操作按钮（禁用/启用/解锁/重置密码）、添加用户对话框。

- [ ] **Step 2: 创建 beenest-account-mgmt.js**

使用 `actuatorEndpoints.casUsers` 调用 API。实现：
- `loadUsers(query, status, page)` — 加载用户列表
- `showUserDetail(userId)` — 查看用户详情
- `disableUser(userId)` / `enableUser(userId)` / `unlockUser(userId)` — 状态操作
- `resetPassword(userId)` — 强制改密
- `showAddUserDialog()` / `submitInvitation()` — 添加用户对话框

- [ ] **Step 3: 在 casPalantirDashboardView.html 添加 accounttab fragment**

在 `<div class="mdc-card pal-card">` 内的 fragment 列表末尾（multitenancytab 之后）添加：
```html
<div th:replace="~{fragments/palantir/accounttab :: main}"/>
<div th:replace="~{fragments/palantir/authorizationtab :: main}"/>
```

在 `<head>` 中添加 JS 引用（在 palantir.js 之前）：
```html
<script type="text/javascript" th:src="@{/js/beenest-account-mgmt.js}"></script>
<script type="text/javascript" th:src="@{/js/beenest-service-auth.js}"></script>
```

- [ ] **Step 4: 在 navigationsidebar.html 添加导航项**

在 `tenantsTabButton` (data-tab-index="14") 之后添加：
```html
<li id="accountTabButton" data-tab-index="15">
    <i class="mdi mdi-account-group" aria-hidden="true"></i>
    <span class="tooltip">用户管理</span>
</li>
<li id="authorizationTabButton" data-tab-index="16">
    <i class="mdi mdi-shield-account" aria-hidden="true"></i>
    <span class="tooltip">服务授权</span>
</li>
```

- [ ] **Step 5: 在 dashboardtabs.html 添加 tab 按钮**

在最后一个 tab 按钮（多租户与租户）之后添加：
```html
<button class="mdc-tab" role="tab" aria-selected="true">
    <span class="mdc-tab__content">
        <span class="mdc-tab__text-label">
            <i class="mdc-tab__icon mdi mdi-account-group"
               aria-hidden="true"></i>用户管理</span>
    </span>
    <span class="mdc-tab-indicator">
        <span class="mdc-tab-indicator__content mdc-tab-indicator__content--underline"></span>
    </span>
    <span class="mdc-tab__ripple"></span>
    <div class="mdc-tab__focus-ring"></div>
</button>
<button class="mdc-tab" role="tab" aria-selected="true">
    <span class="mdc-tab__content">
        <span class="mdc-tab__text-label">
            <i class="mdc-tab__icon mdi mdi-shield-account"
               aria-hidden="true"></i>服务授权</span>
    </span>
    <span class="mdc-tab-indicator">
        <span class="mdc-tab-indicator__content mdc-tab-indicator__content--underline"></span>
    </span>
    <span class="mdc-tab__ripple"></span>
    <div class="mdc-tab__focus-ring"></div>
</button>
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(cas): Palantir 用户管理 tab — accounttab.html + beenest-account-mgmt.js"
```

---

### Task 8: Palantir 前端 — 服务授权 Tab (authorizationtab.html + beenest-service-auth.js)

**Files:**
- Create: `src/main/resources/templates/fragments/palantir/authorizationtab.html`
- Create: `src/main/resources/static/js/beenest-service-auth.js`

- [ ] **Step 1: 创建 authorizationtab.html**

左右分栏布局：左侧应用列表，右侧选中应用的用户列表 + 操作按钮。使用 `th:fragment="main"` 和 `class="d-none"` 初始隐藏。

- [ ] **Step 2: 创建 beenest-service-auth.js**

使用 `actuatorEndpoints.serviceAuthorization` 调用 API。使用 `actuatorEndpoints.registeredservices` 获取应用列表。实现：
- `loadServices()` — 加载应用列表
- `selectService(serviceId)` — 选中应用，加载其用户
- `grantAccess(serviceId, userId)` — 授权用户访问
- `revokeAccess(serviceId, userId)` — 撤销用户访问
- `searchUsers(query)` — 搜索用户（用于授权对话框）

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(cas): Palantir 服务授权 tab — authorizationtab.html + beenest-service-auth.js"
```

---

### Task 9: 集成验证与最终提交

- [ ] **Step 1: 完整编译**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:clean :beenest-cas-service:build --offline --no-daemon -x test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 检查 application.yml 最终状态**

验证：
- `exposure.include` 包含 `casUsers,serviceAuthorization`
- `beenest.user.auto-grant-roles` 映射存在
- `spring.mail` 配置存在

- [ ] **Step 3: 检查 Palantir 模板集成**

验证 casPalantirDashboardView.html 包含 accounttab 和 authorizationtab fragment 引用。

- [ ] **Step 4: 运行测试**

```bash
cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure/beenest-cas && ./gradlew :beenest-cas-service:test --offline --no-daemon 2>&1 | tail -30
```
