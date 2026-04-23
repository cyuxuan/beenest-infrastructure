# Phase 1: 基础设施增强 实施计划

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 CAS 原生模块替换自定义审计代码，引入 Palantir Admin Dashboard、CAPTCHA 验证码、认证中断、事件持久化，建立企业级基础设施。

**Architecture:** 在现有 CAS overlay 基础上，通过 `build.gradle` 添加 8 个 CAS 原生模块依赖，通过 `application.yml` 配置这些模块，然后删除被原生替代的自定义审计代码（AuthAuditService + CasAuthAuditLog + CasAuthAuditLogMapper + mapper XML）。保留所有现有功能不受影响。

**Tech Stack:** Apereo CAS 7.3.6, Spring Boot 3.5.6, Java 21, Gradle, PostgreSQL, Redis, Thymeleaf

**Spec Reference:** `docs/superpowers/specs/2026-04-23-cas-enterprise-nativization-design.md` — Phase 1

---

## Chunk 1: 依赖添加与构建验证

### Task 1: 添加 Phase 1 CAS 原生模块依赖

**Files:**
- Modify: `beenest-cas/build.gradle:282-341` (dependencies block)

- [ ] **Step 1: 在 dependencies 块中添加 8 个新模块**

在 `build.gradle` 的 `dependencies` 块末尾（`testImplementation` 行之前）添加：

```groovy
    // ===== Phase 1: 企业级基础设施 =====
    implementation "org.apereo.cas:cas-server-support-audit-jdbc"
    implementation "org.apereo.cas:cas-server-support-events-jpa"
    implementation "org.apereo.cas:cas-server-support-reports"
    implementation "org.apereo.cas:cas-server-support-palantir"
    implementation "org.apereo.cas:cas-server-webapp-resources"
    implementation "org.apereo.cas:cas-server-support-captcha"
    implementation "org.apereo.cas:cas-server-support-interrupt-webflow"

    // Spring Boot Actuator（Palantir 依赖）
    implementation "org.springframework.boot:spring-boot-starter-actuator"
```

- [ ] **Step 2: 验证构建成功**

Run: `cd beenest-cas && ./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

> 如果构建失败，最可能的原因是模块名在 BOM 中不存在。根据错误信息调整模块名（参见 spec 文档 Section 6.2 注意事项第 7 条）。

- [ ] **Step 3: Commit**

```bash
git add beenest-cas/build.gradle
git commit -m "feat(phase1): 添加基础设施 CAS 原生模块依赖

添加 audit-jdbc, events-jpa, reports, palantir, webapp-resources,
captcha, interrupt-webflow, actuator 共 8 个模块"
```

---

## Chunk 2: Inspektr 审计配置

### Task 2: 配置 CAS Inspektr JDBC 审计

**Files:**
- Modify: `beenest-cas/src/main/resources/application.yml:1-128`

- [ ] **Step 1: 在 application.yml 的 `cas:` 节点下添加审计配置**

在 `cas.logout:` 配置之后、`# Spring Boot 配置` 注释之前，添加：

```yaml
  # Inspektr 审计（替代自定义 AuthAuditService）
  audit:
    jdbc:
      driver-class: org.postgresql.Driver
      url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/beenest?currentSchema=beenest_cas
      user: ${DB_USER:postgres}
      password: ${DB_PASSWORD:changeme}
      dialect: org.hibernate.dialect.PostgreSQLDialect
      ddl-auto: update
      audit-column-length: 5000
      max-age-days: 180
```

- [ ] **Step 2: 验证 CAS 启动时 Inspektr 表自动创建**

Run: `cd beenest-cas && ./gradlew bootRun 2>&1 | grep -i "audit\|inspektr\|COM_AUDIT" | head -10`

启动后检查数据库中是否出现 `com_audit_trail` 等表（ddl-auto: update 会自动创建）。

- [ ] **Step 3: Commit**

```bash
git add beenest-cas/src/main/resources/application.yml
git commit -m "feat(phase1): 配置 Inspektr JDBC 审计系统"
```

---

## Chunk 3: Actuator + Palantir 配置

### Task 3: 配置 Spring Boot Actuator 和 Palantir Admin Dashboard

**Files:**
- Modify: `beenest-cas/src/main/resources/application.yml` (追加配置)

- [ ] **Step 1: 在 application.yml 的 Spring Boot 配置节中添加 Actuator 和 Palantir 认证**

在 `spring.jpa.hibernate.ddl-auto: none` 之后添加：

```yaml
  # Palantir Admin Dashboard 认证
  security:
    user:
      name: ${PALANTIR_ADMIN_USER:admin}
      password: ${PALANTIR_ADMIN_PASSWORD:}
      roles: ADMIN
```

在文件末尾（`beenest.sync:` 之后）添加：

```yaml

# ============================================================
# Actuator 端点（Palantir 依赖）
# ============================================================
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: when-authorized
    env:
      enabled: true
    info:
      enabled: true
    ssoSessions:
      enabled: true
```

- [ ] **Step 2: 验证 Actuator 端点可访问**

Run: `cd beenest-cas && ./gradlew bootRun`

启动后访问 `http://localhost:8081/cas/actuator/health` 应返回 JSON 健康状态。

- [ ] **Step 3: Commit**

```bash
git add beenest-cas/src/main/resources/application.yml
git commit -m "feat(phase1): 配置 Actuator 端点和 Palantir 认证"
```

---

## Chunk 4: CAS 事件持久化配置

### Task 4: 配置 CAS Events JPA 持久化

**Files:**
- Modify: `beenest-cas/src/main/resources/application.yml` (追加配置)

- [ ] **Step 1: 在 application.yml 的 cas: 节点下添加事件配置**

在 `cas.audit:` 配置之后添加：

```yaml
  # CAS 事件持久化（比 Inspektr 更结构化的事件数据）
  events:
    jpa:
      driver-class: org.postgresql.Driver
      url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/beenest?currentSchema=beenest_cas
      user: ${DB_USER:postgres}
      password: ${DB_PASSWORD:changeme}
      dialect: org.hibernate.dialect.PostgreSQLDialect
      ddl-auto: update
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/src/main/resources/application.yml
git commit -m "feat(phase1): 配置 CAS Events JPA 事件持久化"
```

---

## Chunk 5: CAPTCHA 验证码配置

### Task 5: 配置 CAS CAPTCHA 验证码

**Files:**
- Modify: `beenest-cas/src/main/resources/application.yml` (追加配置)
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/config/CaptchaProperties.java` (可选，如果需要自定义参数)

- [ ] **Step 1: 在 application.yml 的 cas: 节点下添加 CAPTCHA 配置**

在 `cas.events:` 配置之后添加：

```yaml
  # 验证码（登录/注册防机器人）
  captcha:
    core:
      enabled: ${CAPTCHA_ENABLED:false}
      provider: RECAPTCHA
    recaptcha:
      site-key: ${RECAPTCHA_SITE_KEY:}
      secret-key: ${RECAPTCHA_SECRET_KEY:}
      verify-url: https://www.google.com/recaptcha/api/siteverify
```

> **注意**：默认 `enabled: false`，需要配置 reCAPTCHA 密钥后才会启用。
> 中国大陆环境可改为 hCaptcha（`provider: HCAPTCHA`）。

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/src/main/resources/application.yml
git commit -m "feat(phase1): 添加 CAS CAPTCHA 验证码配置（默认关闭）"
```

---

## Chunk 6: 认证中断 (Interrupt Webflow) 配置

### Task 6: 配置认证中断 Webflow

**Files:**
- Modify: `beenest-cas/src/main/resources/application.yml` (追加配置)
- Create: `beenest-cas/src/main/resources/interrupt.groovy`

- [ ] **Step 1: 在 application.yml 的 cas: 节点下添加 Interrupt 配置**

在 `cas.captcha:` 配置之后添加：

```yaml
  # 认证中断（强制改密/公告/合规提醒）
  interrupt:
    core:
      enabled: true
      trigger-mode: AFTER
    groovy:
      location: classpath:interrupt.groovy
```

- [ ] **Step 2: 创建 Groovy 中断脚本**

Create: `beenest-cas/src/main/resources/interrupt.groovy`

```groovy
import org.apereo.cas.interrupt.InterruptResponse

/**
 * CAS 认证中断 Groovy 脚本。
 * 在认证成功后检查是否需要中断（如强制改密、全局公告等）。
 */
def run(Object[] args) {
    def (authentication, registeredService, logger) = args

    // 检查是否需要强制改密
    def attributes = authentication.attributes
    def mustChangePassword = attributes.get("mustChangePassword")?.get(0)
    if (mustChangePassword == "true") {
        logger.info("用户 {} 需要强制修改密码，触发中断", authentication.principal)
        return new InterruptResponse(
            "您的密码已过期，请立即修改",
            ["links": ["修改密码": "/cas/account"]],
            false,  // ssoEnabled
            true    // block
        )
    }

    // 默认不中断
    return InterruptResponse.none()
}
```

- [ ] **Step 3: Commit**

```bash
git add beenest-cas/src/main/resources/application.yml beenest-cas/src/main/resources/interrupt.groovy
git commit -m "feat(phase1): 添加认证中断 Webflow 和 Groovy 脚本"
```

---

## Chunk 7: 删除自定义审计代码

### Task 7: 删除被 Inspektr 替代的自定义审计代码

**Files:**
- Delete: `beenest-cas/src/main/java/org/apereo/cas/beenest/service/AuthAuditService.java`
- Delete: `beenest-cas/src/main/java/org/apereo/cas/beenest/entity/CasAuthAuditLog.java`
- Delete: `beenest-cas/src/main/java/org/apereo/cas/beenest/mapper/CasAuthAuditLogMapper.java`
- Delete: `beenest-cas/src/main/resources/mapper/CasAuthAuditLogMapper.xml`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java:74-76`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/config/CasOverlayOverrideConfiguration.java`

- [ ] **Step 1: 从 BeenestServiceConfiguration 中删除 AuthAuditService Bean**

删除 `BeenestServiceConfiguration.java` 中的以下方法（第 74-76 行）：

```java
    @Bean
    public AuthAuditService authAuditService(final CasAuthAuditLogMapper auditLogMapper) {
        return new AuthAuditService(auditLogMapper);
    }
```

同时删除文件顶部的 import：
```java
import org.apereo.cas.beenest.mapper.CasAuthAuditLogMapper;
import org.apereo.cas.beenest.service.AuthAuditService;
```

- [ ] **Step 2: 从 CasOverlayOverrideConfiguration 中移除 AuthAuditService 依赖**

在 `CasOverlayOverrideConfiguration.java` 中：

1. 删除 import：`import org.apereo.cas.beenest.service.AuthAuditService;`
2. 修改 `miniAppLoginController` Bean 方法签名：移除 `final AuthAuditService auditService` 参数，改为 `null`
3. 修改 `appLoginController` Bean 方法签名：同上
4. 修改 `tokenRefreshController` Bean 方法签名：同上

> **重要**：这三个 Controller 内部依赖 `AuthAuditService`，暂时传入 `null`。
> 后续 Phase 会重构这些 Controller 为 CAS 原生替代。当前阶段需确保编译通过。

- [ ] **Step 3: 删除自定义审计相关文件**

```bash
rm beenest-cas/src/main/java/org/apereo/cas/beenest/service/AuthAuditService.java
rm beenest-cas/src/main/java/org/apereo/cas/beenest/entity/CasAuthAuditLog.java
rm beenest-cas/src/main/java/org/apereo/cas/beenest/mapper/CasAuthAuditLogMapper.java
rm beenest-cas/src/main/resources/mapper/CasAuthAuditLogMapper.xml
```

- [ ] **Step 4: 验证构建成功**

Run: `cd beenest-cas && ./gradlew compileJava 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

> 如果编译失败，检查哪些文件仍引用了已删除的类，修复 import。

- [ ] **Step 5: Commit**

```bash
git add -A beenest-cas/src/
git commit -m "refactor(phase1): 删除自定义审计代码（被 Inspektr 替代）

删除 AuthAuditService, CasAuthAuditLog, CasAuthAuditLogMapper, CasAuthAuditLogMapper.xml
保留 V1.0.4 Flyway 脚本（Flyway checksum 校验需要）"
```

---

## Chunk 8: 开发环境配置更新

### Task 8: 更新 application-dev.yml 添加开发环境覆盖

**Files:**
- Modify: `beenest-cas/src/main/resources/application-dev.yml`

- [ ] **Step 1: 在 application-dev.yml 末尾添加开发环境覆盖**

```yaml
# Palantir Admin Dashboard（开发环境使用简单密码）
spring:
  security:
    user:
      name: admin
      password: admin

# Actuator 开发环境配置
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/src/main/resources/application-dev.yml
git commit -m "feat(phase1): 添加开发环境 Palantir 和 Actuator 配置"
```

---

## Chunk 9: 全量构建与集成验证

### Task 9: 全量构建、启动验证、Palantir 功能确认

- [ ] **Step 1: 全量构建**

Run: `cd beenest-cas && ./gradlew clean build -x test 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: 启动 CAS 并验证核心端点**

Run: `cd beenest-cas && ./gradlew bootRun`

逐一验证：
1. `curl http://localhost:8081/cas/actuator/health` → 应返回 JSON 健康状态
2. `curl http://localhost:8081/cas/actuator/info` → 应返回服务信息
3. 浏览器访问 `http://localhost:8081/cas/palantir` → 应显示 Palantir Dashboard 登录页
4. 使用 admin/admin 登录 Palantir → 应进入管理控制台
5. 浏览器访问 `http://localhost:8081/cas/login` → 现有登录页面应正常工作

- [ ] **Step 3: 验证现有认证流程未受影响**

1. 使用小程序登录 API（如有测试环境）验证登录正常
2. 使用 Token 刷新 API 验证正常
3. 检查 Redis 中 TGT 创建/销毁正常

- [ ] **Step 4: 检查数据库中新创建的 Inspektr 表**

```sql
-- 连接 beenest_cas schema，检查 Inspektr 审计表是否已创建
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'beenest_cas' AND table_name LIKE '%audit%';
-- 预期: com_audit_trail 等表
```

- [ ] **Step 5: 最终 Commit（如有未提交的修复）**

```bash
git add -A
git commit -m "fix(phase1): Phase 1 集成验证修复"
```

---

## 关键风险与注意事项

1. **Palantir 认证冲突**：`spring.security.user.*` 配置可能影响 CAS 自身的 Spring Security 配置。如果 CAS 登录页面出现异常认证行为，需要检查 `CasAdminSecurityConfig.java` 中的 SecurityFilterChain 顺序。

2. **Inspektr ddl-auto**：开发环境使用 `ddl-auto: update` 自动建表。生产环境必须切换为 `validate` + 手动 Flyway 迁移脚本。

3. **Controller 中 AuthAuditService 引用**：删除 AuthAuditService 后，3 个 Controller（MiniAppLoginController、AppLoginController、TokenRefreshController）需要传入 null。后续 Phase 会重构这些 Controller。

4. **Flyway 脚本保留**：`V1.0.4__create_cas_auth_audit_log.sql` 文件必须保留，Flyway 需要它做 checksum 校验。表本身会在后续 Phase 通过 `V2.0.1` 脚本删除。
