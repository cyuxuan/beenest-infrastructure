# CAS 用户授权同步 SPI 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 SPI 驱动的用户授权同步机制，使下游应用在用户登录/Token 刷新时自动检测 CAS 角色变更并同步本地账号状态。

**Architecture:** 零耦合 SPI 模式 — CAS 只在 grant/revoke 时递增 tokenVersion；Client Starter 新增 `CasUserAccessControlService` SPI 接口，在认证成功后根据 CAS 角色自动创建/禁用本地用户。

**Tech Stack:** Java 21, Spring Boot 3.5.x, Spring Security, Apereo CAS 7.3.6, MyBatis

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `beenest-cas-client-spring-security-starter/.../accesscontrol/CasUserAccessControlService.java` | SPI 接口 — 下游应用实现 |
| `beenest-cas-client-spring-security-starter/.../accesscontrol/CasAccessControlProperties.java` | 配置属性类 |
| `beenest-cas-client-spring-security-starter/.../accesscontrol/CasAccessControlManager.java` | 内部协调器 — 核心决策逻辑 |
| `beenest-cas-client-spring-security-starter/.../accesscontrol/AccessControlResult.java` | 访问控制结果 record |
| `beenest-cas-client-spring-security-starter/.../accesscontrol/CasAccessControlAutoConfiguration.java` | 自动配置类 |
| `beenest-cas-client-spring-security-starter/.../accesscontrol/CasAccessControlDeniedHandler.java` | 拒绝处理（清除 Session + 403） |
| `beenest-cas-client-spring-security-starter/src/test/.../accesscontrol/CasAccessControlManagerTest.java` | 协调器单元测试 |

### 修改文件

| 文件 | 变更内容 |
|------|----------|
| `beenest-cas/beenest-cas-service/.../mapper/UnifiedUserMapper.java` | 新增 `incrementTokenVersion()` 方法 |
| `beenest-cas/beenest-cas-service/.../resources/mapper/UnifiedUserMapper.xml` | 新增 `incrementTokenVersion` SQL |
| `beenest-cas/beenest-cas-service/.../service/BeenestPrincipalAttributesBuilder.java` | 输出 tokenVersion 到 CAS 属性 |
| `beenest-cas/beenest-cas-service/.../endpoint/ServiceAuthorizationEndpoint.java` | grant/revoke 后递增 tokenVersion |
| `beenest-cas-client-spring-security-starter/.../session/CasLoginSuccessHandler.java` | 注入 CasAccessControlManager，认证后调用 |
| `beenest-cas-client-spring-security-starter/.../authentication/CasBearerTokenAuthenticationProvider.java` | 注入 CasAccessControlManager，buildAuthenticatedToken 统一检查 |
| `beenest-cas-client-spring-security-starter/.../authentication/DefaultCasUserDetailsService.java` | 访问控制激活时跳过旧注册逻辑 |
| `beenest-cas-client-spring-security-starter/.../config/CasSecurityAutoConfiguration.java` | 导入 CasAccessControlAutoConfiguration |
| `beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 注册新自动配置类 |

---

## Task 1: SPI 接口定义

**Files:**
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasUserAccessControlService.java`

- [ ] **Step 1: 创建 SPI 接口文件**

```java
package org.apereo.cas.beenest.client.accesscontrol;

import java.util.Map;
import java.util.Set;

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
     * 也可通过配置 cas.client.access-control.required-role 覆盖。
     *
     * @return CAS 角色名
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

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasUserAccessControlService.java
git commit -m "feat(cas-client): 定义 CasUserAccessControlService SPI 接口"
```

---

## Task 2: 配置属性类

**Files:**
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlProperties.java`

- [ ] **Step 1: 创建配置属性类**

```java
package org.apereo.cas.beenest.client.accesscontrol;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CAS 用户访问控制配置属性。
 *
 * @see CasUserAccessControlService
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

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlProperties.java
git commit -m "feat(cas-client): 添加 CasAccessControlProperties 配置属性"
```

---

## Task 3: AccessControlResult record

**Files:**
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/AccessControlResult.java`

- [ ] **Step 1: 创建结果 record**

```java
package org.apereo.cas.beenest.client.accesscontrol;

/**
 * CAS 访问控制检查结果。
 *
 * @param granted 是否允许访问
 * @param userId  本地用户 ID（granted=true 时有值）
 * @param reason  拒绝原因（granted=false 时有值）
 */
public record AccessControlResult(boolean granted, String userId, String reason) {

    /**
     * 创建允许访问的结果。
     *
     * @param userId 本地用户 ID
     * @return 允许访问的结果
     */
    public static AccessControlResult granted(String userId) {
        return new AccessControlResult(true, userId, null);
    }

    /**
     * 创建拒绝访问的结果。
     *
     * @param reason 拒绝原因
     * @return 拒绝访问的结果
     */
    public static AccessControlResult denied(String reason) {
        return new AccessControlResult(false, null, reason);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/AccessControlResult.java
git commit -m "feat(cas-client): 添加 AccessControlResult 结果 record"
```

---

## Task 4: CasAccessControlManager 核心协调器（含测试）

**Files:**
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlManager.java`
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlManagerTest.java`

- [ ] **Step 1: 编写协调器测试**

```java
package org.apereo.cas.beenest.client.accesscontrol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CasAccessControlManager 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class CasAccessControlManagerTest {

    @Mock
    private CasUserAccessControlService accessControlService;

    private CasAccessControlProperties properties;

    private CasAccessControlManager manager;

    @BeforeEach
    void setUp() {
        properties = new CasAccessControlProperties();
        properties.setEnabled(true);
        properties.setAutoCreateOnGrant(true);
        properties.setAutoDisableOnRevoke(true);
        when(accessControlService.getRequiredRole()).thenReturn("ROLE_DRONE_SYSTEM");
        manager = new CasAccessControlManager(accessControlService, properties);
    }

    // --- 场景 1: 有权限 + 无本地用户 → 自动创建 ---

    @Test
    void onAuthentication_hasAccessNoLocalUser_shouldCreateLocalUser() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);
        when(accessControlService.createLocalUser(eq("user1"), anySet(), anyMap()))
            .thenReturn("local-1");

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"),
            Map.of("nickname", "张三")
        );

        assertTrue(result.granted());
        assertEquals("local-1", result.userId());
        verify(accessControlService).createLocalUser(
            eq("user1"),
            eq(Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT")),
            eq(Map.of("nickname", "张三"))
        );
    }

    @Test
    void onAuthentication_hasAccessNoLocalUser_createFailed_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);
        when(accessControlService.createLocalUser(eq("user1"), anySet(), anyMap()))
            .thenReturn(null);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("本地用户创建失败", result.reason());
    }

    @Test
    void onAuthentication_hasAccessNoLocalUser_autoCreateDisabled_shouldDeny() {
        properties.setAutoCreateOnGrant(false);
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("本地用户不存在", result.reason());
        verify(accessControlService, never()).createLocalUser(any(), anySet(), anyMap());
    }

    // --- 场景 2: 无权限 + 有本地用户 → 禁用 ---

    @Test
    void onAuthentication_noAccessHasLocalUser_shouldDisable() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),  // 不含 ROLE_DRONE_SYSTEM
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("访问权限已撤销", result.reason());
        verify(accessControlService).disableLocalUser("user1", Set.of("ROLE_PAYMENT"));
    }

    @Test
    void onAuthentication_noAccessHasLocalUser_autoDisableOff_shouldDenyWithoutDisabling() {
        properties.setAutoDisableOnRevoke(false);
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("无访问权限", result.reason());
        verify(accessControlService, never()).disableLocalUser(any(), anySet());
    }

    // --- 场景 3: 无权限 + 无本地用户 → 拒绝（兜底） ---

    @Test
    void onAuthentication_noAccessNoLocalUser_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(false);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        assertEquals("无访问权限", result.reason());
    }

    // --- 场景 4: 有权限 + 有本地用户 → 更新 ---

    @Test
    void onAuthentication_hasAccessHasLocalUser_shouldUpdate() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT"),
            Map.of("nickname", "张三")
        );

        assertTrue(result.granted());
        assertEquals("user1", result.userId());
        verify(accessControlService).updateLocalUser(
            eq("user1"),
            eq(Set.of("ROLE_DRONE_SYSTEM", "ROLE_PAYMENT")),
            eq(Map.of("nickname", "张三"))
        );
    }

    // --- 配置属性优先级 ---

    @Test
    void onAuthentication_configuredRoleOverridesSpi_shouldUseConfiguredRole() {
        properties.setRequiredRole("ROLE_CUSTOM");
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);

        // ROLE_DRONE_SYSTEM 存在但配置要求 ROLE_CUSTOM
        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_DRONE_SYSTEM"),
            Map.of()
        );

        assertFalse(result.granted());
        verify(accessControlService).disableLocalUser("user1", Set.of("ROLE_DRONE_SYSTEM"));
    }

    // --- SPI 异常处理 ---

    @Test
    void onAuthentication_spiThrowsException_shouldDeny() {
        when(accessControlService.isLocalUserActive("user1")).thenReturn(true);
        doThrow(new RuntimeException("数据库异常"))
            .when(accessControlService).disableLocalUser(any(), anySet());

        AccessControlResult result = manager.onAuthentication(
            "user1",
            Set.of("ROLE_PAYMENT"),
            Map.of()
        );

        assertFalse(result.granted());
        // 异常后降级为拒绝，不阻断认证流程
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
cd beenest-cas && ./gradlew :beenest-cas-client-spring-security-starter:test --tests "org.apereo.cas.beenest.client.accesscontrol.CasAccessControlManagerTest" -i 2>&1 | tail -20
```

Expected: 编译失败，CasAccessControlManager 类不存在

- [ ] **Step 3: 实现 CasAccessControlManager**

```java
package org.apereo.cas.beenest.client.accesscontrol;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * CAS 用户访问控制协调器。
 * <p>
 * Client Starter 内部组件，在认证成功后调用 SPI 实现进行访问控制判断。
 * 不暴露给下游应用，下游应用只需实现 {@link CasUserAccessControlService}。
 *
 * @see CasUserAccessControlService
 */
@Slf4j
@RequiredArgsConstructor
public class CasAccessControlManager {

    private final CasUserAccessControlService accessControlService;
    private final CasAccessControlProperties properties;

    /**
     * 认证成功后执行访问控制检查。
     * <p>
     * 根据 CAS 角色和本地用户状态，调用 SPI 实现进行以下操作：
     * <ol>
     *   <li>有权限 + 无本地用户 → 自动创建</li>
     *   <li>无权限 + 有本地用户 → 自动禁用</li>
     *   <li>无权限 + 无本地用户 → 拒绝访问</li>
     *   <li>有权限 + 有本地用户 → 更新信息</li>
     * </ol>
     *
     * @param userId        CAS 用户 ID
     * @param casRoles      CAS 返回的所有角色
     * @param casAttributes CAS 返回的其他属性
     * @return 访问控制结果
     */
    public AccessControlResult onAuthentication(String userId, Set<String> casRoles,
                                                 Map<String, Object> casAttributes) {
        String requiredRole = resolveRequiredRole();
        boolean hasAccess = casRoles.contains(requiredRole);

        // 1. 检查本地用户状态（异常时降级为不存在，避免因 SPI 故障阻断认证）
        boolean localExists;
        try {
            localExists = accessControlService.isLocalUserActive(userId);
        } catch (Exception e) {
            log.error("访问控制: 检查本地用户状态异常 userId={}, 降级为不存在", userId, e);
            localExists = false;
        }

        // 2. 有权限 + 无本地用户 → 自动创建
        if (hasAccess && !localExists) {
            if (properties.isAutoCreateOnGrant()) {
                try {
                    String localId = accessControlService.createLocalUser(userId, casRoles, casAttributes);
                    if (localId != null) {
                        log.info("访问控制: 自动创建本地用户 userId={}, localId={}", userId, localId);
                        return AccessControlResult.granted(localId);
                    }
                    log.warn("访问控制: 本地用户创建失败 userId={}", userId);
                    return AccessControlResult.denied("本地用户创建失败");
                } catch (Exception e) {
                    log.error("访问控制: 创建本地用户异常 userId={}", userId, e);
                    return AccessControlResult.denied("本地用户创建失败");
                }
            }
            return AccessControlResult.denied("本地用户不存在");
        }

        // 3. 无权限 + 有本地用户 → 自动禁用
        if (!hasAccess && localExists) {
            if (properties.isAutoDisableOnRevoke()) {
                try {
                    accessControlService.disableLocalUser(userId, casRoles);
                    log.info("访问控制: CAS 角色已撤销，禁用本地用户 userId={}", userId);
                } catch (Exception e) {
                    log.error("访问控制: 禁用本地用户异常 userId={}", userId, e);
                }
                return AccessControlResult.denied("访问权限已撤销");
            }
            return AccessControlResult.denied("无访问权限");
        }

        // 4. 无权限 + 无本地用户 → 拒绝（防御性兜底）
        if (!hasAccess) {
            return AccessControlResult.denied("无访问权限");
        }

        // 5. 有权限 + 有本地用户 → 更新信息
        try {
            accessControlService.updateLocalUser(userId, casRoles, casAttributes);
        } catch (Exception e) {
            log.error("访问控制: 更新本地用户信息异常 userId={}", userId, e);
            // 更新失败不影响正常访问
        }
        return AccessControlResult.granted(userId);
    }

    /**
     * 是否启用访问控制。
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 解析本应用要求的角色名。
     * <p>
     * 配置属性优先，其次 SPI 实现。
     */
    private String resolveRequiredRole() {
        if (StringUtils.hasText(properties.getRequiredRole())) {
            return properties.getRequiredRole();
        }
        return accessControlService.getRequiredRole();
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

```bash
cd beenest-cas && ./gradlew :beenest-cas-client-spring-security-starter:test --tests "org.apereo.cas.beenest.client.accesscontrol.CasAccessControlManagerTest" -i 2>&1 | tail -20
```

Expected: 所有测试通过

- [ ] **Step 5: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlManager.java beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlManagerTest.java
git commit -m "feat(cas-client): 实现 CasAccessControlManager 核心协调器 + 单元测试"
```

---

## Task 5: 拒绝处理器

**Files:**
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlDeniedHandler.java`

- [ ] **Step 1: 创建拒绝处理器**

```java
package org.apereo.cas.beenest.client.accesscontrol;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * CAS 访问控制拒绝处理器。
 * <p>
 * 当访问控制检查未通过时，清除用户会话并返回 403 响应。
 * 在 Web Session 模式下使用。
 */
@Slf4j
@RequiredArgsConstructor
public class CasAccessControlDeniedHandler implements AuthenticationSuccessHandler {

    private final CasAccessControlProperties properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        log.warn("访问控制: 用户 {} 被拒绝访问, 清除会话", authentication.getName());

        // 1. 清除会话
        if (properties.isForceLogoutOnDisable()) {
            request.getSession(false).invalidate();
        }

        // 2. 返回 403 JSON 响应
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":403,\"message\":\"访问权限已变更，请联系管理员\"}");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlDeniedHandler.java
git commit -m "feat(cas-client): 添加 CasAccessControlDeniedHandler 拒绝处理器"
```

---

## Task 6: 自动配置类

**Files:**
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlAutoConfiguration.java`
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasSecurityAutoConfiguration.java`
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建自动配置类**

```java
package org.apereo.cas.beenest.client.accesscontrol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * CAS 用户访问控制自动配置。
 * <p>
 * 只有同时满足以下条件才激活：
 * <ul>
 *   <li>{@code cas.client.access-control.enabled=true}</li>
 *   <li>应用提供了 {@link CasUserAccessControlService} Bean</li>
 * </ul>
 * 不满足时完全不影响现有行为。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(prefix = "cas.client.access-control", name = "enabled",
                        havingValue = "true")
@ConditionalOnBean(CasUserAccessControlService.class)
@EnableConfigurationProperties(CasAccessControlProperties.class)
public class CasAccessControlAutoConfiguration {

    @Bean
    public CasAccessControlManager casAccessControlManager(
            CasUserAccessControlService accessControlService,
            CasAccessControlProperties properties) {
        log.info("CAS 访问控制 SPI 已激活, requiredRole={}",
                 properties.getRequiredRole() != null
                     ? properties.getRequiredRole()
                     : accessControlService.getRequiredRole());
        return new CasAccessControlManager(accessControlService, properties);
    }

    @Bean
    public CasAccessControlDeniedHandler casAccessControlDeniedHandler(
            CasAccessControlProperties properties) {
        return new CasAccessControlDeniedHandler(properties);
    }
}
```

- [ ] **Step 2: 注册到 AutoConfiguration.imports**

在 `beenest-cas/beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件末尾追加一行：

```
org.apereo.cas.beenest.client.accesscontrol.CasAccessControlAutoConfiguration
```

- [ ] **Step 3: 在 CasSecurityAutoConfiguration 中导入**

在 `CasSecurityAutoConfiguration` 类上添加 `@Import(CasAccessControlAutoConfiguration.class)` 注解，确保访问控制配置在安全配置之后加载：

```java
@Import(CasAccessControlAutoConfiguration.class)
```

- [ ] **Step 4: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/accesscontrol/CasAccessControlAutoConfiguration.java beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasSecurityAutoConfiguration.java beenest-cas/beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "feat(cas-client): 添加 CasAccessControlAutoConfiguration 自动配置"
```

---

## Task 7: CAS 侧 — tokenVersion 支持

**Files:**
- Modify: `beenest-cas/beenest-cas-service/src/main/java/org/apereo/cas/beenest/mapper/UnifiedUserMapper.java`
- Modify: `beenest-cas/beenest-cas-service/src/main/resources/mapper/UnifiedUserMapper.xml`
- Modify: `beenest-cas/beenest-cas-service/src/main/java/org/apereo/cas/beenest/service/BeenestPrincipalAttributesBuilder.java`
- Modify: `beenest-cas/beenest-cas-service/src/main/java/org/apereo/cas/beenest/endpoint/ServiceAuthorizationEndpoint.java`

- [ ] **Step 1: UnifiedUserMapper 新增 incrementTokenVersion 方法**

在 `UnifiedUserMapper.java` 接口中添加：

```java
/** 递增用户 tokenVersion，使已发放的 Token 在刷新时触发重新检查 */
void incrementTokenVersion(@Param("userId") String userId);
```

- [ ] **Step 2: UnifiedUserMapper.xml 新增 SQL**

在 `UnifiedUserMapper.xml` 中添加：

```xml
<update id="incrementTokenVersion">
    UPDATE cas_user SET token_version = token_version + 1 WHERE user_id = #{userId}
</update>
```

- [ ] **Step 3: BeenestPrincipalAttributesBuilder 输出 tokenVersion**

在 `BeenestPrincipalAttributesBuilder.buildAttributes()` 方法中，构建 attrs 的部分追加 tokenVersion：

找到构建 attrs Map 的位置，在 `attrs.put("memberOf", ...)` 之后追加：

```java
// 输出 tokenVersion，供 Client Starter 感知权限变更
if (user.getTokenVersion() != null) {
    attrs.put("tokenVersion", List.of(String.valueOf(user.getTokenVersion())));
}
```

- [ ] **Step 4: ServiceAuthorizationEndpoint grant/revoke 后递增 tokenVersion**

在 `ServiceAuthorizationEndpoint` 中注入 `UnifiedUserMapper`（如未注入），然后在 grant 和 revoke 方法中，角色操作成功后调用：

```java
// 权限变更后递增 tokenVersion，使已发放的 Token 在刷新时触发重新检查
userMapper.incrementTokenVersion(userId);
```

在 grant 方法中，`userMapper.addRole(userId, role)` 之后添加。
在 revoke 方法中，`userMapper.removeRole(userId, role)` 之后添加。

- [ ] **Step 5: Commit**

```bash
git add beenest-cas/beenest-cas-service/src/main/java/org/apereo/cas/beenest/mapper/UnifiedUserMapper.java beenest-cas/beenest-cas-service/src/main/resources/mapper/UnifiedUserMapper.xml beenest-cas/beenest-cas-service/src/main/java/org/apereo/cas/beenest/service/BeenestPrincipalAttributesBuilder.java beenest-cas/beenest-cas-service/src/main/java/org/apereo/cas/beenest/endpoint/ServiceAuthorizationEndpoint.java
git commit -m "feat(cas): grant/revoke 后递增 tokenVersion + 属性输出"
```

---

## Task 8: Client Starter 集成 — CasLoginSuccessHandler

**Files:**
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/session/CasLoginSuccessHandler.java`

- [ ] **Step 1: 注入 CasAccessControlManager 并在认证成功后调用**

在 `CasLoginSuccessHandler` 中：

1. 添加可选注入的 `CasAccessControlManager`（使用 `@Autowired(required = false)`，因为未启用时不存在）

```java
@Autowired(required = false)
private CasAccessControlManager accessControlManager;

@Autowired(required = false)
private CasAccessControlDeniedHandler deniedHandler;
```

2. 在 `onAuthenticationSuccess()` 方法中，在现有逻辑之前插入访问控制检查：

```java
// 1. 访问控制 SPI 检查（如果启用）
if (accessControlManager != null && accessControlManager.isEnabled()) {
    Set<String> casRoles = extractCasRoles(authentication);
    Map<String, Object> casAttributes = extractCasAttributes(authentication);
    String userId = authentication.getName();

    AccessControlResult result = accessControlManager.onAuthentication(userId, casRoles, casAttributes);
    if (!result.granted()) {
        deniedHandler.onAuthenticationSuccess(request, response, authentication);
        return;
    }
}

// 2. 继续现有逻辑...
```

3. 添加辅助方法提取 CAS 属性：

```java
/**
 * 从认证信息中提取 CAS 角色（memberOf 属性）。
 */
private Set<String> extractCasRoles(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(auth -> auth.getAuthority())
        .collect(java.util.stream.Collectors.toSet());
}

/**
 * 从认证信息中提取 CAS 其他属性。
 */
@SuppressWarnings("unchecked")
private Map<String, Object> extractCasAttributes(Authentication authentication) {
    if (authentication.getPrincipal() instanceof CasUserDetails userDetails) {
        return new java.util.HashMap<>(userDetails.getAttributes());
    }
    return Map.of();
}
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/session/CasLoginSuccessHandler.java
git commit -m "feat(cas-client): CasLoginSuccessHandler 集成访问控制 SPI"
```

---

## Task 9: Client Starter 集成 — CasBearerTokenAuthenticationProvider 统一检查点

**Files:**
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/authentication/CasBearerTokenAuthenticationProvider.java`

- [ ] **Step 1: 注入 CasAccessControlManager 并在 buildAuthenticatedToken 中统一检查**

在 `CasBearerTokenAuthenticationProvider` 中：

1. 添加可选注入：

```java
@Autowired(required = false)
private CasAccessControlManager accessControlManager;
```

2. 在 `buildAuthenticatedToken()` 方法中，构造 `CasBearerTokenAuthenticationToken` 返回之前（即方法末尾），插入访问控制检查。这样无论是正常认证、缓存命中还是 Token 刷新场景，都会统一经过此检查：

```java
// 访问控制 SPI 检查（如果启用）
if (accessControlManager != null && accessControlManager.isEnabled() && casUserDetails != null) {
    Set<String> casRoles = casUserDetails.getAuthorities().stream()
        .map(auth -> auth.getAuthority())
        .collect(java.util.stream.Collectors.toSet());
    Map<String, Object> casAttributes = new java.util.HashMap<>(casUserDetails.getCasUserSession().getAttributes());

    AccessControlResult result = accessControlManager.onAuthentication(
        casUserDetails.getUsername(), casRoles, casAttributes);
    if (!result.granted()) {
        throw new BadCredentialsException(result.reason());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/authentication/CasBearerTokenAuthenticationProvider.java
git commit -m "feat(cas-client): CasBearerTokenAuthenticationProvider 统一访问控制检查点"
```

---

## Task 10: 兼容旧 CasUserRegistrationService

**Files:**
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/authentication/DefaultCasUserDetailsService.java`

- [ ] **Step 1: 访问控制激活时跳过旧注册逻辑**

在 `DefaultCasUserDetailsService` 中：

1. 添加可选注入：

```java
@Autowired(required = false)
private CasAccessControlManager accessControlManager;
```

2. 找到调用 `CasUserRegistrationService.registerFromCas()` 的位置（`maybeRegisterLocalUser()` 方法），在调用前添加判断：

```java
// 访问控制 SPI 已激活时，由 CasAccessControlManager 统一处理用户创建/禁用
// 不再调用 CasUserRegistrationService，避免重复创建
if (accessControlManager != null && accessControlManager.isEnabled()) {
    log.debug("访问控制 SPI 已激活，跳过 CasUserRegistrationService 注册");
    return;
}
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/authentication/DefaultCasUserDetailsService.java
git commit -m "feat(cas-client): 访问控制激活时跳过旧 CasUserRegistrationService 逻辑"
```

---

## Task 11: 构建验证

**Files:** 无新增

- [ ] **Step 1: 编译 Client Starter**

```bash
cd beenest-cas && ./gradlew :beenest-cas-client-spring-security-starter:clean :beenest-cas-client-spring-security-starter:build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行 Client Starter 测试**

```bash
cd beenest-cas && ./gradlew :beenest-cas-client-spring-security-starter:test
```

Expected: 所有测试通过

- [ ] **Step 3: 编译 CAS Service**

```bash
cd beenest-cas && ./gradlew :beenest-cas-service:clean :beenest-cas-service:build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

如编译/测试有问题则修复，修复后提交：

```bash
git add -A && git commit -m "fix: 构建验证修复"
```

---

## 实现依赖关系

```
Task 1 (SPI 接口) ──┐
Task 2 (配置属性) ──┤
Task 3 (Result)   ──┼── Task 4 (Manager + 测试) ── Task 5 (DeniedHandler) ── Task 6 (AutoConfig)
                     │                                                                      │
                     │                                                                      ├── Task 8 (LoginHandler)
                     │                                                                      ├── Task 9 (BearerProvider 统一检查)
                     │                                                                      └── Task 10 (兼容旧注册)
                     │
Task 7 (CAS 侧 tokenVersion) ── 独立，可与 Task 1-6 并行
                     │
                     └── Task 11 (构建验证) ── 依赖所有其他 Task
```
