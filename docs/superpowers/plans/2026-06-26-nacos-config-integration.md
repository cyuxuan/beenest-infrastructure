# Nacos 配置中心接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 beenest-cas 和 beenest-payment 两个服务的全量配置迁移到 Nacos 配置中心，本地只保留 Nacos 连接信息和极简兜底默认值。

**Architecture:** 两个服务都引入 `spring-cloud-starter-alibaba-nacos-config`，通过 `bootstrap.yml` 配置 Nacos 连接信息。CAS 不做服务注册（仅 nacos-config），Payment 已有 nacos-discovery 保持不变。配置通过 Nacos 的 namespace 隔离环境（dev/test/prod），通过 data-id 的 profile 后缀实现环境差异化。

**Tech Stack:** Spring Cloud Alibaba 2025.0.0.0, Spring Cloud 2025.0.0, Spring Boot 3.5.x, Nacos Server 2.4.3

---

## File Structure

### beenest-cas

| 操作 | 文件 | 职责 |
|------|------|------|
| Create | `beenest-cas/beenest-cas-service/src/main/resources/bootstrap.yml` | Nacos 连接配置（bootstrap 阶段加载） |
| Modify | `beenest-cas/beenest-cas-service/build.gradle` | 新增 Spring Cloud Alibaba BOM + nacos-config + bootstrap 依赖 |
| Modify | `beenest-cas/beenest-cas-service/src/main/resources/application.yml` | 精简为极简兜底配置，全量配置迁移到 Nacos |
| Modify | `beenest-cas/beenest-cas-service/src/main/resources/application-dev.yml` | 精简，环境差异化配置迁移到 Nacos |
| Modify | `beenest-cas/beenest-cas-service/src/main/resources/application-test.yml` | 精简，环境差异化配置迁移到 Nacos |
| Modify | `beenest-cas/.env` | 新增 NACOS_ADDR、NACOS_NAMESPACE 环境变量 |

### beenest-payment

| 操作 | 文件 | 职责 |
|------|------|------|
| Create | `beenest-payment/beenest-payment-service/src/main/resources/bootstrap.yml` | Nacos 连接配置（bootstrap 阶段加载） |
| Modify | `beenest-payment/beenest-payment-service/pom.xml` | 新增 nacos-config 依赖 |
| Modify | `beenest-payment/beenest-payment-service/src/main/resources/application.yml` | 精简，全量配置迁移到 Nacos |
| Modify | `beenest-payment/.env` | 新增 NACOS_NAMESPACE 环境变量 |

### Nacos 配置（需手动在 Nacos 控制台创建）

| Data ID | 说明 |
|---------|------|
| `beenest-cas.yml` | CAS 生产配置（从 application.yml 迁移） |
| `beenest-cas-dev.yml` | CAS 开发环境覆盖（从 application-dev.yml 迁移） |
| `beenest-cas-test.yml` | CAS 测试环境覆盖（从 application-test.yml 迁移） |
| `beenest-payment.yml` | Payment 生产配置（从 application.yml 迁移） |
| `beenest-payment-dev.yml` | Payment 开发环境覆盖 |

---

## Task 1: CAS — 新增依赖

**Files:**
- Modify: `beenest-cas/beenest-cas-service/build.gradle`

- [ ] **Step 1: 在 build.gradle 的 dependencies 块中新增 Spring Cloud Alibaba BOM 和 Nacos Config 依赖**

在 `dependencies { ... }` 块中，在 `implementation enforcedPlatform("org.apereo.cas:cas-server-support-bom:${project.'cas.version'}")` 之后添加：

```groovy
    // ===== Nacos 配置中心 =====
    implementation platform("com.alibaba.cloud:spring-cloud-alibaba-dependencies:2025.0.0.0")
    implementation("com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config") {
        // CAS 不做服务注册，排除 nacos-discovery 避免自动注册
        exclude group: "com.alibaba.cloud", module: "spring-cloud-starter-alibaba-nacos-discovery"
    }
    // Spring Cloud Bootstrap（Spring Cloud 2021+ 必须显式引入，否则 bootstrap.yml 不加载）
    implementation "org.springframework.cloud:spring-cloud-starter-bootstrap"
```

- [ ] **Step 2: 验证依赖解析**

Run: `cd beenest-cas && ./gradlew :beenest-cas-service:dependencies --configuration runtimeClasspath 2>&1 | grep -E "nacos|spring-cloud-starter-bootstrap" | head -20`

Expected: 看到 `spring-cloud-starter-alibaba-nacos-config` 和 `spring-cloud-starter-bootstrap` 在依赖树中，且无 `nacos-discovery`。

- [ ] **Step 3: Commit**

```bash
git add beenest-cas/beenest-cas-service/build.gradle
git commit -m "feat(cas): 新增 Spring Cloud Alibaba Nacos Config 依赖"
```

---

## Task 2: CAS — 创建 bootstrap.yml

**Files:**
- Create: `beenest-cas/beenest-cas-service/src/main/resources/bootstrap.yml`

- [ ] **Step 1: 创建 bootstrap.yml**

```yaml
# ============================================================
# CAS Bootstrap 配置 — Nacos 配置中心连接信息
# 此文件在 Spring Boot 启动的 bootstrap 阶段加载，
# 早于 application.yml，用于建立与 Nacos 的连接并拉取远程配置。
# ============================================================

spring:
  application:
    name: beenest-cas
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        # Nacos 不可用时不阻止启动（本地开发兜底）
        fail-fast: false
        # 刷新配置（仅支持 @RefreshScope 标注的 Bean，CAS 不使用 RefreshScope）
        refresh-enabled: false
      discovery:
        # CAS 不做服务注册
        enabled: false
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/bootstrap.yml
git commit -m "feat(cas): 新增 bootstrap.yml 配置 Nacos 连接信息"
```

---

## Task 3: CAS — 精简 application.yml

**Files:**
- Modify: `beenest-cas/beenest-cas-service/src/main/resources/application.yml`

将全量配置迁移到 Nacos 后，本地 `application.yml` 只保留极简兜底配置。Nacos 中的 `beenest-cas.yml` 将包含原 `application.yml` 的全部内容。

- [ ] **Step 1: 替换 application.yml 为精简版本**

将 `beenest-cas/beenest-cas-service/src/main/resources/application.yml` 的全部内容替换为：

```yaml
# ============================================================
# CAS 本地兜底配置
# 全量配置已迁移到 Nacos 配置中心（data-id: beenest-cas.yml）
# 此文件仅在 Nacos 不可用时作为兜底，生产环境不应依赖此文件。
# ============================================================

server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: beenest-cas
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  main:
    allow-bean-definition-overriding: true

# 本地开发兜底日志级别
logging:
  level:
    org.apereo.cas: INFO
    org.apereo.cas.beenest: INFO
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/application.yml
git commit -m "refactor(cas): 精简 application.yml，全量配置迁移到 Nacos"
```

---

## Task 4: CAS — 精简 application-dev.yml

**Files:**
- Modify: `beenest-cas/beenest-cas-service/src/main/resources/application-dev.yml`

开发环境差异化配置迁移到 Nacos 的 `beenest-cas-dev.yml`，本地只保留极简兜底。

- [ ] **Step 1: 替换 application-dev.yml 为精简版本**

将 `beenest-cas/beenest-cas-service/src/main/resources/application-dev.yml` 的全部内容替换为：

```yaml
# ============================================================
# CAS 开发环境本地兜底配置
# 开发环境差异化配置已迁移到 Nacos（data-id: beenest-cas-dev.yml）
# 此文件仅在 Nacos 不可用时作为兜底。
# ============================================================

cas:
  server:
    name: http://localhost:8081
    prefix: ${cas.server.name}/cas
  tgc:
    secure: false

server:
  port: 8081
  ssl:
    enabled: false

spring:
  thymeleaf:
    cache: false

logging:
  level:
    org.apereo.cas: INFO
    org.apereo.cas.beenest: INFO
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/application-dev.yml
git commit -m "refactor(cas): 精简 application-dev.yml，差异化配置迁移到 Nacos"
```

---

## Task 5: CAS — 精简 application-test.yml

**Files:**
- Modify: `beenest-cas/beenest-cas-service/src/main/resources/application-test.yml`

- [ ] **Step 1: 替换 application-test.yml 为精简版本**

将 `beenest-cas/beenest-cas-service/src/main/resources/application-test.yml` 的全部内容替换为：

```yaml
# ============================================================
# CAS 测试环境本地兜底配置
# 测试环境差异化配置已迁移到 Nacos（data-id: beenest-cas-test.yml）
# 此文件仅在 Nacos 不可用时作为兜底。
# ============================================================

cas:
  server:
    name: ${CAS_SERVER_NAME:http://localhost:8081}
    prefix: ${cas.server.name}/cas
  tgc:
    secure: false

server:
  port: 8081
  ssl:
    enabled: false

spring:
  thymeleaf:
    cache: false

logging:
  level:
    org.apereo.cas: INFO
    org.apereo.cas.beenest: INFO
```

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/beenest-cas-service/src/main/resources/application-test.yml
git commit -m "refactor(cas): 精简 application-test.yml，差异化配置迁移到 Nacos"
```

---

## Task 6: CAS — 更新 .env 环境变量

**Files:**
- Modify: `beenest-cas/.env`

- [ ] **Step 1: 在 .env 文件中新增 Nacos 相关环境变量**

在 `beenest-cas/.env` 的 `# ---------- Spring / Server ----------` 段落之后添加：

```bash
# ---------- Nacos 配置中心 ----------
NACOS_ADDR=10.88.8.11:30002
NACOS_NAMESPACE=prod
```

> 注意：`10.88.8.11:30002` 是生产 Nacos 地址，需根据实际部署确认。namespace 使用 `prod` 隔离生产环境。

- [ ] **Step 2: Commit**

```bash
git add beenest-cas/.env
git commit -m "feat(cas): 新增 Nacos 配置中心环境变量"
```

---

## Task 7: Payment — 新增 nacos-config 依赖

**Files:**
- Modify: `beenest-payment/beenest-payment-service/pom.xml`

Payment 已有 `spring-cloud-alibaba-dependencies` BOM 和 `nacos-discovery`，只需新增 `nacos-config`。

- [ ] **Step 1: 在 pom.xml 的 dependencies 中新增 nacos-config**

在 `<!-- Nacos 服务注册/发现 -->` 依赖块之后添加：

```xml
        <!-- Nacos 配置中心 -->
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
```

- [ ] **Step 2: 验证依赖解析**

Run: `cd beenest-payment && mvn dependency:tree -pl beenest-payment-service 2>&1 | grep -E "nacos-config" | head -5`

Expected: 看到 `spring-cloud-starter-alibaba-nacos-config` 在依赖树中。

- [ ] **Step 3: Commit**

```bash
git add beenest-payment/beenest-payment-service/pom.xml
git commit -m "feat(payment): 新增 Nacos Config 依赖"
```

---

## Task 8: Payment — 创建 bootstrap.yml

**Files:**
- Create: `beenest-payment/beenest-payment-service/src/main/resources/bootstrap.yml`

- [ ] **Step 1: 创建 bootstrap.yml**

```yaml
# ============================================================
# Payment Bootstrap 配置 — Nacos 配置中心连接信息
# 此文件在 Spring Boot 启动的 bootstrap 阶段加载，
# 早于 application.yml，用于建立与 Nacos 的连接并拉取远程配置。
# ============================================================

spring:
  application:
    name: beenest-payment
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yml
        # Nacos 不可用时不阻止启动（本地开发兜底）
        fail-fast: false
        refresh-enabled: false
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        enabled: ${NACOS_ENABLED:true}
```

- [ ] **Step 2: Commit**

```bash
git add beenest-payment/beenest-payment-service/src/main/resources/bootstrap.yml
git commit -m "feat(payment): 新增 bootstrap.yml 配置 Nacos 连接信息"
```

---

## Task 9: Payment — 精简 application.yml

**Files:**
- Modify: `beenest-payment/beenest-payment-service/src/main/resources/application.yml`

全量配置迁移到 Nacos 后，本地 `application.yml` 只保留极简兜底配置。

- [ ] **Step 1: 替换 application.yml 为精简版本**

将 `beenest-payment/beenest-payment-service/src/main/resources/application.yml` 的全部内容替换为：

```yaml
# ============================================================
# Payment 本地兜底配置
# 全量配置已迁移到 Nacos 配置中心（data-id: beenest-payment.yml）
# 此文件仅在 Nacos 不可用时作为兜底，生产环境不应依赖此文件。
# ============================================================

server:
  port: ${SERVER_PORT:8082}

spring:
  application:
    name: beenest-payment
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
    include:
      - secret
  main:
    allow-bean-definition-overriding: true

# 本地开发兜底日志级别
logging:
  level:
    club.beenest.payment: INFO
    club.beenest.payment.mapper: DEBUG
```

- [ ] **Step 2: Commit**

```bash
git add beenest-payment/beenest-payment-service/src/main/resources/application.yml
git commit -m "refactor(payment): 精简 application.yml，全量配置迁移到 Nacos"
```

---

## Task 10: Payment — 更新 .env 环境变量

**Files:**
- Modify: `beenest-payment/.env`

- [ ] **Step 1: 在 .env 文件中新增 NACOS_NAMESPACE 环境变量**

在 `# ---------- Nacos / Sentinel ----------` 段落中，将 `NACOS_NAMESPACE=` 改为：

```bash
NACOS_NAMESPACE=prod
```

> 注意：Payment 的 .env 中已有 `NACOS_ADDR` 和 `NACOS_NAMESPACE`（当前为空），只需将 `NACOS_NAMESPACE` 设置为 `prod`。

- [ ] **Step 2: Commit**

```bash
git add beenest-payment/.env
git commit -m "feat(payment): 设置 NACOS_NAMESPACE 环境变量"
```

---

## Task 11: Nacos 控制台 — 创建 CAS 配置

**Files:** 无代码文件，需在 Nacos 控制台手动操作

- [ ] **Step 1: 在 Nacos 控制台创建 `beenest-cas.yml`（生产配置）**

登录 Nacos 控制台 → 配置管理 → 配置列表 → 点击 "+" 新建配置：

- **Data ID**: `beenest-cas.yml`
- **Group**: `DEFAULT_GROUP`
- **配置格式**: `YAML`
- **配置内容**: 将原 `application.yml` 的完整内容粘贴进去（即 Task 3 替换前的内容），但做以下调整：
  1. 删除 `spring.application.name`（已在 bootstrap.yml 中定义）
  2. 删除 `spring.profiles.active`（已在 bootstrap.yml 中定义）
  3. 保留所有 `cas.*`、`beenest.*`、`spring.*`、`management.*`、`mybatis.*`、`logging.*`、`server.*` 配置
  4. 所有环境变量引用 `${VAR:default}` 保持不变

- [ ] **Step 2: 在 Nacos 控制台创建 `beenest-cas-dev.yml`（开发环境覆盖）**

- **Data ID**: `beenest-cas-dev.yml`
- **Group**: `DEFAULT_GROUP`
- **配置格式**: `YAML`
- **配置内容**: 将原 `application-dev.yml` 的完整内容粘贴进去（即 Task 4 替换前的内容），同样删除 `spring.application.name` 和 `spring.profiles.active`

- [ ] **Step 3: 在 Nacos 控制台创建 `beenest-cas-test.yml`（测试环境覆盖）**

- **Data ID**: `beenest-cas-test.yml`
- **Group**: `DEFAULT_GROUP`
- **配置格式**: `YAML`
- **配置内容**: 将原 `application-test.yml` 的完整内容粘贴进去（即 Task 5 替换前的内容），同样删除 `spring.application.name` 和 `spring.profiles.active`

---

## Task 12: Nacos 控制台 — 创建 Payment 配置

**Files:** 无代码文件，需在 Nacos 控制台手动操作

- [ ] **Step 1: 在 Nacos 控制台创建 `beenest-payment.yml`（生产配置）**

- **Data ID**: `beenest-payment.yml`
- **Group**: `DEFAULT_GROUP`
- **配置格式**: `YAML`
- **配置内容**: 将原 `application.yml` 的完整内容粘贴进去（即 Task 9 替换前的内容），但做以下调整：
  1. 删除 `spring.application.name`（已在 bootstrap.yml 中定义）
  2. 删除 `spring.profiles.active`（已在 bootstrap.yml 中定义）
  3. 保留所有 `payment.*`、`withdraw.*`、`cas.*`、`spring.*`、`mybatis.*`、`pagehelper.*`、`logging.*`、`springdoc.*`、`server.*` 配置
  4. 所有环境变量引用 `${VAR:default}` 保持不变

- [ ] **Step 2: 在 Nacos 控制台创建 `beenest-payment-dev.yml`（开发环境覆盖）**

- **Data ID**: `beenest-payment-dev.yml`
- **Group**: `DEFAULT_GROUP`
- **配置格式**: `YAML`
- **配置内容**: 开发环境差异化配置，例如：

```yaml
# Payment 开发环境覆盖配置
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/beenest?currentSchema=beenest_payment
    username: postgres
    password: postgres
  data:
    redis:
      host: localhost
      port: 6379
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

cas:
  client:
    server-url: https://localhost:8443/cas
    client-host-url: http://localhost:8082

payment:
  common:
    sandbox: true
```

---

## Task 13: 验证 CAS 启动

- [ ] **Step 1: 编译 CAS**

Run: `cd beenest-cas && ./gradlew :beenest-cas-service:clean :beenest-cas-service:build --offline --no-daemon -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 启动 CAS 容器**

Run: `cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure && docker compose up -d beenest-cas`

- [ ] **Step 3: 检查 CAS 启动日志，确认 Nacos 配置加载成功**

Run: `docker logs beenest-cas --tail 100 -f`

Expected: 日志中出现类似以下内容：
- `Located property source: [BootstrapPropertySource {name='bootstrapProperties-beenest-cas-dev.yml,DEFAULT_GROUP'}]`
- CAS 正常启动，端口 8081 监听成功

- [ ] **Step 4: 验证 CAS 功能正常**

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/cas/login`

Expected: HTTP 200

---

## Task 14: 验证 Payment 启动

- [ ] **Step 1: 编译 Payment**

Run: `cd beenest-payment && mvn clean package -DskipTests`

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动 Payment 容器**

Run: `cd /Users/sunny/Desktop/dev/beenest/beenest-infrastructure && docker compose up -d beenest-payment`

- [ ] **Step 3: 检查 Payment 启动日志，确认 Nacos 配置加载成功**

Run: `docker logs beenest-payment --tail 100 -f`

Expected: 日志中出现 Nacos config 加载成功信息，Payment 正常启动，端口 8082 监听成功

- [ ] **Step 4: 验证 Payment 功能正常**

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health`

Expected: HTTP 200

---

## Task 15: 最终提交和文档更新

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: 更新 CLAUDE.md 中的配置管理说明**

在 CLAUDE.md 的 `beenest-cas` 架构描述中，更新配置管理相关内容，添加 Nacos 配置中心说明：

在 `**Configuration properties**:` 段落之前添加：

```markdown
**Nacos 配置中心**: CAS 和 Payment 的全量配置已迁移到 Nacos。本地 `bootstrap.yml` 仅保留 Nacos 连接信息（`spring.cloud.nacos.config.*`），`application.yml` 仅保留极简兜底默认值。Nacos 中的配置通过 namespace 隔离环境（dev/test/prod），通过 data-id 的 profile 后缀实现环境差异化（如 `beenest-cas-dev.yml`）。CAS 不做服务注册（`spring.cloud.nacos.discovery.enabled=false`），仅从 Nacos 拉取配置。
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: 更新 CLAUDE.md 添加 Nacos 配置中心说明"
```
