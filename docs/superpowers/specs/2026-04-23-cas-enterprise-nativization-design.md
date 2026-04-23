# CAS 7.3.x 企业级统一认证中心 — 全面原生化重构设计

> **日期**: 2026-04-23
> **目标**: 将 beenest-cas 从自定义-heavy 的 CAS overlay 升级为以 CAS 原生模块为核心的企业级统一认证中心
> **原则**: 最大化 CAS 原生模块，最小化自定义代码；无生产数据约束，可自由重构

---

## 一、现状与差距分析

### 1.1 当前项目规模

| 类别 | 数量 | 说明 |
|---|---|---|
| Java 源文件 | ~90 个 | src/main/java 下 |
| 认证处理器 | 5 个 | 微信/抖音/支付宝小程序、SMS OTP、AppToken |
| REST Controller | 9 个 | 登录/Token/用户管理/服务管理/同步策略 |
| Service 类 | 11 个 | 用户/审计/服务/同步/短信/访问控制 |
| MyBatis Mapper | 6 个 | 6 张自定义表 |
| DTO/VO/Entity | ~35 个 | 请求/响应/实体类 |
| Flyway 迁移 | 6 个 | V1.0.0 ~ V1.0.5 |
| CAS 原生模块 | 13 个 | 基础模块（Redis Ticket、JPA Service Registry、JDBC Auth、Thymeleaf 等） |

### 1.2 差距矩阵

| 企业级功能 | 当前实现 | CAS 原生模块 | 差距 |
|---|---|---|---|
| **CAS Protocol** | ✅ 已有 | `cas-server-webapp-init` | 无差距 |
| **SAML2 IdP** | ❌ 无 | `cas-server-support-saml-idp` | 需新增 |
| **OAuth2 Provider** | ❌ 无 | `cas-server-support-oauth` | 需新增 |
| **OIDC Provider** | ❌ 无 | `cas-server-support-oidc` | 需新增 |
| **REST Protocol** | ✅ 已有 | `cas-server-support-rest` | 无差距 |
| **WS-Federation** | ❌ 无 | `cas-server-support-wsfederation` | 需新增 |
| **审计** | 🔶 自定义 `AuthAuditService` | `cas-server-support-audit-jdbc` (Inspektr) | 应替换 |
| **密码管理** | ❌ 无 | `cas-server-support-pm-jdbc` + `pm-webflow` | 需新增 |
| **MFA - TOTP** | ❌ 无 | `cas-server-support-gauth` + `gauth-jpa` | 需新增 |
| **MFA - WebAuthn** | ❌ 无 | `cas-server-support-webauthn` + `webauthn-jpa` | 需新增 |
| **用户同意/属性发布** | ❌ 无 | `cas-server-support-consent-jdbc` | 需新增 |
| **使用条款 (AUP)** | ❌ 无 | `cas-server-support-aup` | 需新增 |
| **模拟/代理认证** | ❌ 无 | `cas-server-support-surrogate-authentication-jdbc` | 需新增 |
| **自适应风险认证** | ❌ 无 | `cas-server-support-adaptive-authentication` | 需新增 |
| **Admin Dashboard** | 🔶 自定义 Controller | `cas-server-support-reports` | 应替换 |
| **服务管理 UI** | 🔶 自定义 Controller | CAS SSO Management Webapp | 应替换 |
| **用户管理** | 🔶 自定义 Service + Controller | CAS 密码管理 + Person Directory | 应替换 |
| **通知 (邮件)** | ❌ 无 | CAS 内置邮件通知 | 需新增 |
| **通知 (SMS)** | 🔶 自定义 SmsService | CAS SMS 框架 | 可保留/整合 |
| **委托认证** | ❌ 无 | `cas-server-support-integration-pac4j` | 需新增 |
| **监控/Metrics** | ❌ 无 | Spring Boot Actuator | 需新增 |
| **小程序认证** | ✅ 自定义 Handler | 无原生替代 | **必须保留** |

### 1.3 可删除的自定义代码

以下自定义代码将被 CAS 原生模块完全替代：

**Service 类（删除 ~7 个）**：
- `AuthAuditService` → Inspektr 审计框架
- `CasServiceAdminService` → CAS Service Management
- `CasServiceCredentialService` → SAML2/OIDC 原生密钥管理
- `AppAccessService` → CAS Service Access Strategy
- `UserSyncPushService` → 协议标准属性发布
- `UserSyncService` → 协议标准属性发布
- `SyncStrategyService` → 不再需要

**Controller 类（删除 ~5 个）**：
- `CasUserAdminController` → CAS Admin Dashboard
- `CasServiceAdminController` → CAS Service Management UI
- `SyncStrategyController` → 不再需要
- `UserSyncController` → 协议标准替代
- 部分 `TokenRefreshController` / `TokenValidationController` → CAS 原生 OAuth2/OIDC Token 端点

**Entity / Mapper（删除 ~5 张表及相关代码）**：
- `cas_auth_audit_log` → Inspektr `COM_AUDIT_TRAIL`
- `cas_app_access` → CAS Service Access Strategy
- `cas_sync_strategy` → 不再需要
- `cas_user_change_log` → 协议标准 + Inspektr 审计
- `cas_service_credential` → SAML2/OIDC 原生

### 1.4 必须保留的自定义代码

| 文件 | 原因 |
|---|---|
| `WechatMiniAuthenticationHandler` + `WechatMiniCredential` | 中国微信小程序 code2session，CAS 无原生支持 |
| `DouyinMiniAuthenticationHandler` + `DouyinMiniCredential` | 抖音小程序，同上 |
| `AlipayMiniAuthenticationHandler` + `AlipayMiniCredential` | 支付宝小程序，同上 |
| `SmsOtpAuthenticationHandler` + `SmsOtpCredential` | 保留但重构为 CAS Webflow 集成 |
| `AppTokenAuthenticationHandler` + `AppTokenCredential` | App 端 Bearer 认证，保留 |
| `MiniAppLoginController` | 小程序登录入口，CAS 无法原生处理 |
| `AppLoginController` | App 登录入口，保留 |
| `UserIdentityService` | 核心多渠道用户合并逻辑，保留并重构 |
| `SmsService` | 短信发送，保留但整合 CAS 通知框架 |
| `BeenestAccessStrategy` | 重构为 CAS 原生 Service Access Strategy 自定义扩展 |
| `UnifiedUserDO` + `UnifiedUserMapper` | 用户数据层，保留并增强 |
| `CasConstant`, `R`, `BusinessException` 等通用类 | 保留 |

---

## 二、架构设计

### 2.1 整体架构图

```
┌──────────────────────────────────────────────────────────────┐
│                    企业级统一认证中心                          │
│                   beenest-cas 7.3.6                          │
├──────────────────────────────────────────────────────────────┤
│  协议层                                                       │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │
│  │CAS 3.0  │ │SAML2 IdP│ │ OAuth2  │ │  OIDC   │           │
│  │(已有)   │ │ (新增)  │ │ (新增)  │ │ (新增)  │           │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘           │
│  ┌─────────┐ ┌──────────┐                                    │
│  │  REST   │ │WS-Fed    │                                    │
│  │(已有)   │ │ (新增)   │                                    │
│  └─────────┘ └──────────┘                                    │
├──────────────────────────────────────────────────────────────┤
│  认证层                                                       │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ [自定义] 中国渠道: 微信 / 抖音 / 支付宝小程序           │  │
│  │ [自定义] SMS OTP / AppToken                            │  │
│  │ [新增]   JDBC 用户名密码 (CAS 原生)                    │  │
│  │ [可选]   LDAP/AD (CAS 原生)                           │  │
│  │ [新增]   委托认证: 社交登录 (Pac4j)                    │  │
│  └───────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ MFA: Google Authenticator + FIDO2/WebAuthn             │  │
│  │ 自适应风险认证 / 模拟认证 (Surrogate)                  │  │
│  └───────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  企业级功能层 (全部 CAS 原生)                                 │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐              │
│  │Inspektr    │ │密码管理     │ │属性发布     │              │
│  │审计(JDBC)  │ │(重置/策略)  │ │+用户同意    │              │
│  └────────────┘ └────────────┘ └────────────┘              │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐              │
│  │使用条款    │ │Admin       │ │监控/Metrics │              │
│  │(AUP)       │ │Dashboard   │ │(Actuator)  │              │
│  └────────────┘ └────────────┘ └────────────┘              │
├──────────────────────────────────────────────────────────────┤
│  数据层                                                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ PostgreSQL (schema: beenest_cas)                      │   │
│  │ ├─ cas_user (自定义增强)                              │   │
│  │ ├─ registered_service (CAS JPA Service Registry)     │   │
│  │ ├─ COM_AUDIT_TRAIL (Inspektr)                        │   │
│  │ ├─ SamlIdPMetadata (SAML2 JPA)                       │   │
│  │ ├─ OidcRegisteredService (OIDC)                      │   │
│  │ ├─ MFA 设备注册表 (gauth/webauthn JPA)                │   │
│  │ ├─ 用户同意表 (consent JPA)                           │   │
│  │ └─ 代理认证表 (surrogate JDBC)                        │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────┐                                   │
│  │ Redis (Ticket Registry + 缓存)                         │   │
│  └──────────────────────┘                                   │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖图

```
build.gradle 依赖（按功能分组）

CAS 核心:
├── cas-server-core-api-configuration-model     (已有)
├── cas-server-webapp-init                       (已有)
└── cas-server-webapp-init-tomcat                (已有)

CAS API:
├── cas-server-core-authentication-api            (已有)
├── cas-server-core-services-api                  (已有)
├── cas-server-core-api-services                  (已有)
├── cas-server-core-tickets-api                   (已有)
└── cas-server-core-authentication-attributes     (已有)

协议层:
├── cas-server-support-saml-idp                   (新增)
├── cas-server-support-saml-idp-metadata-jpa      (新增)
├── cas-server-support-oidc                       (新增)
├── cas-server-support-oauth                       (新增)
└── cas-server-support-wsfederation                (新增)

认证层:
├── cas-server-support-redis-ticket-registry      (已有)
├── cas-server-support-redis-modules              (已有)
├── cas-server-support-jpa-service-registry       (已有)
├── cas-server-support-jdbc-authentication        (已有)
├── cas-server-support-jdbc-drivers               (已有)
├── cas-server-support-jpa-hibernate              (已有)
├── cas-server-support-generic                    (已有)
├── cas-server-support-rest                       (已有)
├── cas-server-support-thymeleaf                  (已有)
└── cas-server-support-integration-pac4j          (新增)

MFA:
├── cas-server-support-mfa-core                   (新增)
├── cas-server-support-authentication-mfa          (新增)
├── cas-server-support-gauth                      (新增)
├── cas-server-support-gauth-jpa                  (新增)
├── cas-server-support-webauthn                   (新增)
└── cas-server-support-webauthn-jpa               (新增)

审计:
└── cas-server-support-audit-jdbc                 (新增，替代自定义)

密码管理:
├── cas-server-support-pm-jdbc                    (新增)
└── cas-server-support-pm-webflow                 (新增)

企业管理:
├── cas-server-support-consent-jdbc               (新增)
├── cas-server-support-aup-webflow                (新增)
├── cas-server-support-surrogate-authentication   (新增)
├── cas-server-support-surrogate-authentication-jdbc (新增)
├── cas-server-support-adaptive-authentication    (新增)
├── cas-server-support-reports                    (新增)
├── cas-server-webapp-resources                   (新增)
└── cas-server-support-person-directory           (新增)

通知:
├── cas-server-support-notifications              (新增)
└── cas-server-support-sms                        (新增)

SAML SP 集成:
└── cas-server-support-saml-sp-integrations       (新增)
```

---

## 三、分阶段实施计划

### Phase 1: 基础设施增强（审计 + 监控 + Admin Dashboard）

**目标**: 建立企业级基础设施，替换最容易被原生替代的自定义模块。

**预估工时**: 3-4 天

#### 1.1 新增依赖

```groovy
// build.gradle
implementation "org.apereo.cas:cas-server-support-audit-jdbc"
implementation "org.apereo.cas:cas-server-support-reports"
implementation "org.apereo.cas:cas-server-webapp-resources"

// Spring Boot Actuator（监控）
implementation "org.springframework.boot:spring-boot-starter-actuator"
```

#### 1.2 审计系统配置 (application.yml)

```yaml
cas:
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

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,cache,cas
  endpoint:
    health:
      show-details: when-authorized
```

#### 1.3 删除自定义审计代码

- 删除 `AuthAuditService.java`
- 删除 `CasAuthAuditLog.java` (Entity)
- 删除 `CasAuthAuditLogMapper.java`
- 删除 `mapper/CasAuthAuditLogMapper.xml`
- 删除 Flyway `V1.0.4__create_cas_auth_audit_log.sql`（或标记废弃）

#### 1.4 Admin Dashboard

CAS 原生 Admin Dashboard 通过 `cas-server-support-reports` 提供：
- `/cas/status` — 服务器状态
- `/cas/status/dashboard` — 管理面板
- `/cas/statistics` — 统计信息

删除自定义的 `CasUserAdminController` 中的管理功能。

#### 1.5 监控端点

启用 Spring Boot Actuator：
- `/actuator/health` — 健康检查
- `/actuator/metrics` — 性能指标
- `/actuator/info` — 服务信息

---

### Phase 2: 协议层（SAML2 + OAuth2 + OIDC + WS-Federation）

**目标**: 让 CAS 成为企业级统一身份提供商，支持所有主流协议。

**预估工时**: 5-7 天

#### 2.1 新增依赖

```groovy
implementation "org.apereo.cas:cas-server-support-saml-idp"
implementation "org.apereo.cas:cas-server-support-saml-idp-metadata-jpa"
implementation "org.apereo.cas:cas-server-support-oidc"
implementation "org.apereo.cas:cas-server-support-oauth"
implementation "org.apereo.cas:cas-server-support-wsfederation"
implementation "org.apereo.cas:cas-server-support-saml-sp-integrations"
```

#### 2.2 SAML2 IdP 配置

```yaml
cas:
  authn:
    saml-idp:
      core:
        entity-id: https://sso.beenest.club/cas/idp
        metadata:
          location: file:/etc/cas/saml
      metadata:
        jpa:
          driver-class: org.postgresql.Driver
          url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
          user: ${DB_USER}
          password: ${DB_PASSWORD}
          dialect: org.hibernate.dialect.PostgreSQLDialect
          ddl-auto: update
      response:
        sign-error: true
        sign-responses: true
        sign-assertions: true
```

需要生成 SAML2 IdP 密钥对和自签名证书：
```bash
# 生成 IdP 密钥对
keytool -genkeypair -alias samlidp -keyalg RSA -keysize 4096 \
  -validity 3650 -keystore /etc/cas/saml/samlIdPKeystore.jks \
  -storepass changeit -keypass changeit
```

#### 2.3 OAuth2 / OIDC 配置

```yaml
cas:
  authn:
    oauth:
      access-token:
        time-to-kill-in-seconds: 7200
      refresh-token:
        time-to-kill-in-seconds: 2592000
    oidc:
      core:
        issuer: https://sso.beenest.club/cas/oidc
        scopes:
          - openid
          - profile
          - email
          - phone
          - offline_access
      jwks:
        jwks-file: file:/etc/cas/oidc/jwks.json
```

#### 2.4 WS-Federation 配置

```yaml
cas:
  authn:
    wsfed:
      idp:
        issuer: https://sso.beenest.club/cas/wsfed
        realm: urn:beenest:cors
```

#### 2.5 服务注册（替代自定义 Controller）

通过 CAS SSO Management Webapp 或直接在 Service Registry 中注册：

```json
// SAML2 SP 示例
{
  "@class": "org.apereo.cas.support.saml.services.SamlRegisteredService",
  "serviceId": "https://sp.example.com/saml/metadata",
  "name": "Example SAML SP",
  "id": 1000,
  "evaluationOrder": 1,
  "metadataLocation": "https://sp.example.com/saml/metadata"
}

// OIDC Client 示例
{
  "@class": "org.apereo.cas.support.oauth.services.OAuthRegisteredService",
  "serviceId": "https://app.example.com/callback",
  "name": "Example OIDC Client",
  "id": 2000,
  "clientId": "example-client",
  "clientSecret": "hashed-secret",
  "supportedGrantTypes": ["authorization_code", "refresh_token"],
  "supportedResponseTypes": ["code"]
}
```

#### 2.6 删除的自定义代码

- 删除 `CasServiceAdminController` → CAS Service Management UI
- 删除 `CasServiceCredentialService` → 协议原生密钥管理
- 删除 `CasServiceCredentialFilter` → 协议原生安全
- 删除 `CasServiceCredentialDO` → 协议原生
- 删除 `CasServiceCredentialMapper` → 协议原生
- 删除 `CasServiceCredentialProperties` → 协议原生配置
- 删除 Flyway `V1.0.5__create_cas_service_credential.sql`
- 删除 `CasServiceRegisterDTO`, `CasServiceDetailDTO`, `CasServiceSummaryDTO`, `CasServiceAuthMethodDTO`, `CasServiceRegisterResultDTO`

---

### Phase 3: MFA + 密码管理 + 用户管理

**目标**: 完善用户生命周期管理，增强认证安全。

**预估工时**: 5-7 天

#### 3.1 新增依赖

```groovy
// MFA
implementation "org.apereo.cas:cas-server-support-mfa-core"
implementation "org.apereo.cas:cas-server-support-authentication-mfa"
implementation "org.apereo.cas:cas-server-support-gauth"
implementation "org.apereo.cas:cas-server-support-gauth-jpa"
implementation "org.apereo.cas:cas-server-support-webauthn"
implementation "org.apereo.cas:cas-server-support-webauthn-jpa"

// 密码管理
implementation "org.apereo.cas:cas-server-support-pm-jdbc"
implementation "org.apereo.cas:cas-server-support-pm-webflow"

// 用户属性
implementation "org.apereo.cas:cas-server-support-person-directory"
```

#### 3.2 密码管理配置

```yaml
cas:
  authn:
    pm:
      enabled: true
      jdbc:
        driver-class: org.postgresql.Driver
        url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
        user: ${DB_USER}
        password: ${DB_PASSWORD}
        dialect: org.hibernate.dialect.PostgreSQLDialect
        ddl-auto: update
        # 密码加密算法
        password-encoder:
          type: BCRYPT
          strength: 12
      # 密码策略
      policy:
        enabled: true
        password-policy-attributes:
          password-length-min: 8
          password-length-max: 128
          password-require-upper: true
          password-require-lower: true
          password-require-digit: true
          password-require-special: true
      # 密码历史（防止重复使用最近 N 个密码）
      history:
        enabled: true
        max-passwords-to-keep: 6
      # 密码重置
      reset:
        enabled: true
        security-questions-enabled: false
        mail:
          from: noreply@beenest.club
          subject: 密码重置
          text: "点击以下链接重置密码: %s"
```

#### 3.3 JDBC 用户名密码认证

```yaml
cas:
  authn:
    jdbc:
      query[0]:
        driver-class: org.postgresql.Driver
        url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
        user: ${DB_USER}
        password: ${DB_PASSWORD}
        dialect: org.hibernate.dialect.PostgreSQLDialect
        sql: SELECT password_hash FROM cas_user WHERE username=? AND status=1
        field-password: password_hash
        password-encoder:
          type: BCRYPT
          strength: 12
```

#### 3.4 MFA 配置

```yaml
cas:
  authn:
    mfa:
      gauth:
        core:
          window-size: 3
          time-step-size: 30
          codes: 6
          scratch-codes:
            count: 5
            length: 8
        jpa:
          driver-class: org.postgresql.Driver
          url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
          user: ${DB_USER}
          password: ${DB_PASSWORD}
          dialect: org.hibernate.dialect.PostgreSQLDialect
          ddl-auto: update
      webauthn:
        core:
          relying-party-name: Beenest CAS
          relying-party-id: sso.beenest.club
          allow-primary-authentication: false
        jpa:
          driver-class: org.postgresql.Driver
          url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
          user: ${DB_USER}
          password: ${DB_PASSWORD}
          dialect: org.hibernate.dialect.PostgreSQLDialect
          ddl-auto: update
      triggers:
        global:
          global-provider-id: mfa-gauth
```

#### 3.5 数据库迁移

新增 Flyway 脚本增强 `cas_user` 表：

```sql
-- V2.0.0__enhance_user_table_for_cas_native.sql
-- 增加密码管理所需字段
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS password_changed_time TIMESTAMP;
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS password_expiry_time TIMESTAMP;
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN DEFAULT FALSE;
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS security_question VARCHAR(256);
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS security_answer VARCHAR(256);

-- 移除自定义 MFA 字段（由 CAS 原生 MFA 模块管理）
-- 注意：mfa_secret_encrypted 字段由 CAS gauth-jpa 接管
ALTER TABLE cas_user DROP COLUMN IF EXISTS mfa_secret_encrypted;
```

#### 3.6 删除的自定义代码

- 删除 `UserAdminService` → CAS 密码管理 + Person Directory
- 删除 `AppAccessService` → CAS Service Access Strategy
- 删除 `CasAppAccessMapper` → 不再需要
- 删除 `CasAppAccess.java` → 不再需要
- 删除 `AppAccessGrantDTO` → 不再需要
- 删除 Flyway `V1.0.1__create_cas_app_access.sql`

---

### Phase 4: 企业级功能层（用户同意 + AUP + 通知 + 委托认证 + 模拟认证 + 自适应认证）

**目标**: 补齐企业级合规和安全功能。

**预估工时**: 4-5 天

#### 4.1 新增依赖

```groovy
implementation "org.apereo.cas:cas-server-support-consent-jdbc"
implementation "org.apereo.cas:cas-server-support-aup-webflow"
implementation "org.apereo.cas:cas-server-support-surrogate-authentication"
implementation "org.apereo.cas:cas-server-support-surrogate-authentication-jdbc"
implementation "org.apereo.cas:cas-server-support-adaptive-authentication"
implementation "org.apereo.cas:cas-server-support-integration-pac4j"
implementation "org.apereo.cas:cas-server-support-notifications"
implementation "org.apereo.cas:cas-server-support-sms"
```

#### 4.2 属性发布 + 用户同意

```yaml
cas:
  consent:
    core:
      enabled: true
    jdbc:
      driver-class: org.postgresql.Driver
      url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
      user: ${DB_USER}
      password: ${DB_PASSWORD}
      dialect: org.hibernate.dialect.PostgreSQLDialect
      ddl-auto: update

  # 属性发布策略（在 Service 定义中配置）
  # 可按服务控制发布哪些属性
```

#### 4.3 使用条款 (AUP)

```yaml
cas:
  acceptable-usage-policy:
    core:
      enabled: true
      aup-policy-attribute-name: acceptedAUP
    jdbc:
      driver-class: org.postgresql.Driver
      url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
      user: ${DB_USER}
      password: ${DB_PASSWORD}
      dialect: org.hibernate.dialect.PostgreSQLDialect
      ddl-auto: update
      sql-fetch-policy-attributes: SELECT accepted FROM cas_user_aup WHERE user_id=?
      sql-store-policy-attributes: UPDATE cas_user_aup SET accepted=true, accepted_time=CURRENT_TIMESTAMP WHERE user_id=?
```

#### 4.4 模拟/代理认证 (Surrogate)

```yaml
cas:
  authn:
    surrogate:
      jdbc:
        driver-class: org.postgresql.Driver
        url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/beenest?currentSchema=beenest_cas
        user: ${DB_USER}
        password: ${DB_PASSWORD}
        dialect: org.hibernate.dialect.PostgreSQLDialect
        ddl-auto: update
        sql-surrogate-accounts-to-search: SELECT surrogate_user FROM cas_surrogate WHERE principal=?
```

#### 4.5 委托认证 (Pac4j - 社交登录)

```yaml
cas:
  authn:
    pac4j:
      core:
        enabled: true
      # 微信网页登录（注意：不同于小程序登录）
      wechat:
        id: ${WECHAT_WEB_APPID:}
        secret: ${WECHAT_WEB_SECRET:}
      # Google
      google:
        id: ${GOOGLE_CLIENT_ID:}
        secret: ${GOOGLE_CLIENT_SECRET:}
```

#### 4.6 邮件通知

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.beenest.club}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

#### 4.7 删除的自定义代码

- 删除 `UserSyncPushService` → 协议标准属性发布
- 删除 `UserSyncService` → 协议标准
- 删除 `SyncStrategyService` → 不再需要
- 删除 `CasSyncStrategyMapper` → 不再需要
- 删除 `CasSyncStrategy.java` → 不再需要
- 删除 `CasUserChangeLogMapper` → Inspektr 审计替代
- 删除 `CasUserChangeLog.java` → 不再需要
- 删除 `SyncStrategyController` → 不再需要
- 删除 `UserSyncController` → 不再需要
- 删除 Flyway `V1.0.2__create_cas_user_change_log.sql`
- 删除 Flyway `V1.0.3__create_cas_sync_strategy.sql`
- 删除相关 DTO: `UserSyncWebhookPayloadDTO`, `UserSyncPushFailureDTO`, `CasSyncStrategyDTO`, `UserIdRequestDTO`

---

### Phase 5: 认证处理器重构 + 客户端兼容

**目标**: 重构保留的认证处理器，确保客户端 starter 兼容新的 CAS 原生功能。

**预估工时**: 5-7 天

#### 5.1 保留并重构的认证处理器

**微信小程序处理器** — 重构方向：
- 保留 `WechatMiniCredential` 和 `WechatMiniAuthenticationHandler`
- 重构为标准 CAS `AuthenticationHandler` 实现，遵循 CAS 的 `authenticate()` 生命周期
- 返回的 `Principal` 包含 CAS 标准属性（可被属性发布策略使用）
- 用户合并逻辑从 Handler 移到 `UserIdentityService`（单一职责）

**SMS OTP 处理器** — 重构方向：
- 保留但重构为 CAS Webflow 集成
- 利用 CAS 通知框架发送验证码
- 整合 CAS 原生的 OTP 机制

**AppToken 处理器** — 重构方向：
- 保留用于 App 端 Bearer 认证
- 考虑是否可以用 CAS 原生 OAuth2 Token 端点替代
- 如果 OAuth2 能满足需求，可删除此处理器

#### 5.2 BeenestAccessStrategy 重构

从独立类重构为 CAS 原生 Service Access Strategy 的自定义扩展：

```java
@Component
public class BeenestAccessStrategy extends DefaultRegisteredServiceAccessStrategy {
    // 扩展 CAS 原生访问策略
    // 在 Service 注册时配置
    // 替代 cas_app_access 表的功能
}
```

#### 5.3 CasOverlayOverrideConfiguration 瘦身

原配置类注册了 5 个 Handler + 9 个 Controller + BeenestAccessStrategy。
重构后只需要注册：
- 3-5 个自定义认证处理器
- 1-2 个自定义 Controller（小程序入口）
- BeenestAccessStrategy 扩展
- Person Directory 自定义属性解析器

#### 5.4 客户端 Starter 兼容性

**`beenest-cas-client-spring-security-starter`**（39 个 Java 文件）：

需要评估并调整：
- Token 验证：如果启用了 CAS OAuth2/OIDC，客户端可以用标准 OAuth2 流程替代自定义 Token 校验
- 用户同步：如果 CAS 通过属性发布 + OIDC 提供用户信息，可减少自定义同步逻辑
- SLO（单点登出）：CAS 原生 SAML2/OIDC 都支持，需确保客户端兼容

**调整方向**：
1. 新增 OIDC/OAuth2 客户端支持（使用 Spring Security 原生 OAuth2 Client）
2. 保留 CAS Protocol 客户端支持（向后兼容）
3. 用户同步从"推/拉"模式切换为"属性发布"模式
4. Token 刷新机制考虑使用 OAuth2 Refresh Token 标准流程

---

### Phase 6: 数据库 Schema 整合 + Flyway 迁移

**目标**: 清理废弃表，建立新的 CAS 原生表结构。

**预估工时**: 2-3 天

#### 6.1 迁移脚本规划

```sql
-- V2.0.0__enhance_user_table_for_cas_native.sql
-- 增强 cas_user 表（Phase 3 已列出）

-- V2.0.1__drop_deprecated_custom_tables.sql
-- 删除被 CAS 原生替代的自定义表
DROP TABLE IF EXISTS cas_auth_audit_log;
DROP TABLE IF EXISTS cas_app_access;
DROP TABLE IF EXISTS cas_sync_strategy;
DROP TABLE IF EXISTS cas_user_change_log;
DROP TABLE IF EXISTS cas_service_credential;

-- V2.0.2__create_surrogate_table.sql
-- 模拟认证表
CREATE TABLE IF NOT EXISTS cas_surrogate (
    id BIGSERIAL PRIMARY KEY,
    principal VARCHAR(128) NOT NULL,
    surrogate_user VARCHAR(128) NOT NULL,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(principal, surrogate_user)
);

-- V2.0.3__create_aup_table.sql
-- 使用条款接受记录
CREATE TABLE IF NOT EXISTS cas_user_aup (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL UNIQUE REFERENCES cas_user(user_id),
    accepted BOOLEAN DEFAULT FALSE,
    accepted_time TIMESTAMP,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 注意：以下表由 CAS 原生模块自动创建（ddl-auto: update）：
-- - COM_AUDIT_TRAIL (Inspektr)
-- - registered_service + related (JPA Service Registry)
-- - SamlIdPMetadata (SAML2 JPA)
-- - MFA 设备注册表 (gauth/webauthn JPA)
-- - 用户同意表 (consent JPA)
-- - OAuth2/OIDC 相关表
```

---

### Phase 7: 模板与 UI 定制

**目标**: 使用 Thymeleaf 定制 CAS 登录页面，适配品牌和多渠道认证入口。

**预估工时**: 2-3 天

#### 7.1 登录页面定制

在 `src/main/resources/templates/` 下定制 CAS 默认模板：

- `casLoginView.html` — 登录页面（增加小程序二维码、社交登录按钮）
- `casGenericSuccessView.html` — 登录成功页面
- `casLogoutView.html` — 登出页面
- `casPasswordUpdateView.html` — 密码修改页面
- `casMfaView.html` — MFA 验证页面
- `casConsentView.html` — 属性同意页面
- `casAupView.html` — 使用条款页面

#### 7.2 静态资源

- `src/main/resources/static/css/` — 自定义样式
- `src/main/resources/static/js/` — 小程序扫码等交互脚本
- `src/main/resources/static/images/` — Logo、品牌素材

---

## 四、最终文件变更清单

### 4.1 新增文件

| 类别 | 文件 | 说明 |
|---|---|---|
| 配置 | `application.yml` (大幅修改) | 新增所有 CAS 原生模块配置 |
| 配置 | `src/main/resources/saml/` | SAML2 IdP 密钥和元数据 |
| 配置 | `src/main/resources/oidc/jwks.json` | OIDC JWKS 密钥集 |
| 迁移 | `V2.0.0__enhance_user_table.sql` | 用户表增强 |
| 迁移 | `V2.0.1__drop_deprecated_tables.sql` | 删除废弃表 |
| 迁移 | `V2.0.2__create_surrogate_table.sql` | 模拟认证表 |
| 迁移 | `V2.0.3__create_aup_table.sql` | 使用条款表 |
| 模板 | `templates/casLoginView.html` 等 | CAS UI 定制 |
| 认证 | `BeenestAccessStrategy` (重构) | CAS 原生扩展 |
| 认证 | Person Directory 配置 | 自定义属性解析 |

### 4.2 保留并重构的文件

| 文件 | 重构方向 |
|---|---|
| `CasOverlayOverrideConfiguration.java` | 瘦身：只注册 3-5 个自定义处理器 |
| `WechatMiniAuthenticationHandler.java` | 标准 CAS Handler 重构 |
| `DouyinMiniAuthenticationHandler.java` | 标准 CAS Handler 重构 |
| `AlipayMiniAuthenticationHandler.java` | 标准 CAS Handler 重构 |
| `SmsOtpAuthenticationHandler.java` | CAS Webflow 集成重构 |
| `AppTokenAuthenticationHandler.java` | 评估是否可用 OAuth2 替代 |
| `MiniAppLoginController.java` | 保留（小程序入口） |
| `AppLoginController.java` | 保留（App 入口） |
| `SmsController.java` | 保留（短信验证码发送） |
| `TokenRefreshController.java` | 评估是否可用 OAuth2 Token 端点替代 |
| `TokenValidationController.java` | 评估是否可用 CAS 内省端点替代 |
| `UserIdentityService.java` | 保留并增强 |
| `SmsService.java` | 保留，整合 CAS 通知框架 |
| `UnifiedUserDO.java` + `UnifiedUserMapper.java` | 保留并增强 |
| 5 个 Credential 类 | 保留 |
| `WxMaConfiguration.java` | 保留 |
| `BeenestServiceConfiguration.java` | 瘦身 |
| DTO 类 (MiniAppLoginDTO, AppLoginRequestDTO 等) | 保留（与保留的 Controller 配套） |
| `R.java`, `BusinessException.java`, `CasConstant.java` | 保留 |
| `AesEncryptionUtils.java`, `CasRequestSignatureUtils.java` | 保留（仍有使用场景） |

### 4.3 删除的文件（~40+ 个）

| 类别 | 文件 | 被什么替代 |
|---|---|---|
| Service | `AuthAuditService.java` | Inspektr |
| Service | `CasServiceAdminService.java` | CAS Service Management |
| Service | `CasServiceCredentialService.java` | 协议原生 |
| Service | `AppAccessService.java` | CAS Access Strategy |
| Service | `UserSyncPushService.java` | 协议标准 |
| Service | `UserSyncService.java` | 协议标准 |
| Service | `SyncStrategyService.java` | 不再需要 |
| Service | `UserAdminService.java` | CAS 密码管理 |
| Controller | `CasUserAdminController.java` | CAS Admin Dashboard |
| Controller | `CasServiceAdminController.java` | CAS Service Management UI |
| Controller | `SyncStrategyController.java` | 不再需要 |
| Controller | `UserSyncController.java` | 协议标准 |
| Entity | `CasAuthAuditLog.java` | Inspektr |
| Entity | `CasAppAccess.java` | CAS Access Strategy |
| Entity | `CasSyncStrategy.java` | 不再需要 |
| Entity | `CasUserChangeLog.java` | Inspektr |
| Entity | `CasServiceCredentialDO.java` | 协议原生 |
| Mapper | `CasAuthAuditLogMapper.java` | Inspektr |
| Mapper | `CasAppAccessMapper.java` | 不再需要 |
| Mapper | `CasSyncStrategyMapper.java` | 不再需要 |
| Mapper | `CasUserChangeLogMapper.java` | 不再需要 |
| Mapper | `CasServiceCredentialMapper.java` | 协议原生 |
| Filter | `CasServiceCredentialFilter.java` | 协议原生 |
| Config | `CasServiceCredentialProperties.java` | 协议原生配置 |
| DTO | ~15 个 DTO 类 | 对应的 Controller 被删除 |
| Migration | `V1.0.1` ~ `V1.0.5` | 被新迁移脚本替代 |

---

## 五、风险与注意事项

### 5.1 高风险项

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| CAS 原生模块与自定义代码冲突 | 编译/启动失败 | Phase 1 先验证基础模块，逐步叠加 |
| SAML2 IdP 密钥管理复杂度 | 生产部署困难 | 提前编写密钥生成脚本和文档 |
| OIDC JWKS 轮换 | 已有 Token 失效 | 提前规划密钥轮换策略 |
| 客户端 Starter 不兼容 | 下游服务中断 | 保留 CAS Protocol 兼容，OIDC/OAuth2 作为新增选项 |
| Inspektr 表结构与业务需求不匹配 | 审计数据不完整 | 可通过自定义 AuditActionResolver 扩展 |

### 5.2 注意事项

1. **版本锁定**: 所有 `cas-server-support-*` 模块不指定版本号，由 CAS BOM 统一管理
2. **ddl-auto 策略**: 开发环境可用 `update`（自动建表），生产环境必须用 `validate` + Flyway
3. **密钥管理**: 所有加密密钥（TGC、Ticket、SAML、OIDC）必须通过环境变量注入，不得硬编码
4. **测试策略**: 每个 Phase 完成后必须通过集成测试验证
5. **文档更新**: 每个 Phase 完成后更新 CLAUDE.md 和 README

---

## 六、成功标准

| 指标 | 目标 |
|---|---|
| 支持的协议 | CAS + SAML2 + OAuth2 + OIDC + REST + WS-Federation |
| 认证方式 | 5 种自定义（微信/抖音/支付宝/SMS/AppToken）+ JDBC + LDAP + 社交登录 |
| MFA | Google Authenticator + FIDO2/WebAuthn |
| 审计覆盖 | 100% 认证/票据/服务操作审计 |
| 密码管理 | 重置/策略/历史/过期 |
| 企业级功能 | 用户同意/AUP/模拟认证/自适应认证/监控 |
| 自定义代码减少 | 从 ~90 个 Java 文件减少到 ~45 个 |
| CAS 原生模块数 | 从 13 个增加到 ~35 个 |
| Admin 能力 | Dashboard + Service Management UI + Actuator |
