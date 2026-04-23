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
| **WS-Federation** | ❌ 无 | `cas-server-support-ws-idp` | 需新增 |
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
| **JWT 认证** | ❌ 无 | `cas-server-support-jwt-authentication` | 需新增 |
| **JWT Service Ticket** | ❌ 无 | CAS 原生 JWT ST（按服务配置） | 需新增 |
| **OAuth2 JWT Access Token** | ❌ 无 | CAS 原生（按服务配置） | 需新增 |
| **Token Introspection** | ❌ 无 | CAS 原生 OAuth2 内省端点 | 需新增 |
| **QR Code 扫码登录** | ❌ 无 | `cas-server-support-qr-authn` | 需新增 |
| **无密码认证 (Passwordless)** | ❌ 无 | `cas-server-support-passwordless` | 需新增 |
| **认证节流/防暴力破解** | 🔶 自定义 lockout 逻辑 | `cas-server-support-authentication-throttle` | 应替换 |
| **用户自注册** | 🔶 自定义 Controller | `cas-server-support-account-registration` | 应替换 |
| **自动 Provisioning** | 🔶 自定义 UserIdentityService | CAS Groovy/REST/SCIM Provisioning | 整合 |
| **用户账户管理** | ❌ 无 | CAS Account Profile Management | 需新增 |
| **服务访问策略** | 🔶 自定义 AppAccessService | CAS Service Access Strategy（属性/角色/REST） | 应替换 |
| **会话管理/踢人下线** | ❌ 无 | TGT 销毁 + SLO 级联 | 需新增（自定义端点） |
| **属性解析 (Person Directory)** | ❌ 无 | `cas-server-support-person-directory` | 需新增 |

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
└── cas-server-support-ws-idp                      (新增)

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
├── cas-server-core-authentication-mfa-api         (新增, MFA 核心 API)
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
├── cas-server-support-email-sending              (新增, 邮件通知)
└── cas-server-support-sms                        (新增)

SAML SP 集成:
└── cas-server-support-saml-sp-integrations       (新增)

JWT 与 Token:
├── cas-server-support-jwt-authentication          (新增, JWT 认证)
├── cas-server-support-jwt-service-ticket          (新增, JWT Service Ticket)
├── cas-server-support-oauth-core-api              (新增, Token Introspection)
└── cas-server-support-rest-tokens                 (新增, REST JWT Token)

扫码与无密码认证:
├── cas-server-support-qr-authn                    (新增, QR Code 扫码登录)
└── cas-server-support-passwordless                (新增, 无密码认证)

认证安全:
└── cas-server-support-authentication-throttle     (新增, 防暴力破解)

用户注册与账户管理:
├── cas-server-support-account-registration        (新增, 用户自注册)
└── cas-server-support-account-management          (新增, 账户 Profile 管理)
```

---

## 三、分阶段实施计划

> **Phase 依赖关系**：
> - Phase 1（基础设施）→ 无前置依赖，最先执行
> - Phase 2（协议层）→ 依赖 Phase 1 的审计模块
> - Phase 3（MFA + 密码管理）→ 可与 Phase 2 并行，依赖 Phase 1
> - Phase 4（企业功能：用户同意/AUP/通知/委托认证/模拟认证/自适应认证）→ 依赖 Phase 2 和 Phase 3
> - Phase 4.5（JWT 全链路/扫码登录/无密码认证/防暴力破解/会话管理）→ 依赖 Phase 2（JWT 需要协议层）
> - Phase 5（认证重构 + 用户注册/Provisioning + 客户端兼容）→ 依赖 Phase 2-4.5 全部完成
> - Phase 6（数据库整合）→ 贯穿 Phase 3-4.5，每个 Phase 的自定义表由对应的 V2.0.x 脚本处理
> - Phase 7（UI 定制）→ 可与 Phase 4.5-5 并行
>
> **回滚策略**：每个 Phase 在 git 上创建独立分支。如果某个 Phase 出现严重问题，可 revert 该分支的合并。数据库回滚脚本放在 `db/rollback/` 目录下。

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
- 删除 Flyway `V1.0.4__create_cas_auth_audit_log.sql`（**注意**：如果该脚本已在环境中运行过，不能删除，因为 Flyway 需要校验 checksum。仅在全新环境可删除。推荐做法：保留旧脚本，新增 V2.0.1 删除表）

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
implementation "org.apereo.cas:cas-server-support-ws-idp"
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
      # OIDC 签名加密密钥
      crypto:
        signing:
          key: ${OIDC_SIGNING_KEY:changeme-changeme-changeme-changeme-changeme-changeme-changeme}
        encryption:
          key: ${OIDC_ENCRYPTION_KEY:changeme-changeme-changeme}
```

需要生成 OIDC JWKS 密钥集：
```bash
# CAS 启动时自动生成 jwks.json（如果文件不存在）
# 或手动使用 CAS 命令行工具生成
mkdir -p /etc/cas/oidc
# 首次启动后 CAS 会自动在指定路径生成 JWKS 文件
```

#### 2.4 WS-Federation 配置

```yaml
cas:
  authn:
    wsfed-idp:
      idp:
        realm: urn:org:apereo:cas:ws:idp:realm-beenest
        realm-name: Beenest
      sts:
        realm:
          issuer: https://sso.beenest.club/cas/wsfed
        crypto:
          encryption:
            key: ${WSFED_ENCRYPTION_KEY:changeme-changeme-changeme}
          signing:
            key: ${WSFED_SIGNING_KEY:changeme-changeme-changeme-changeme-changeme-changeme-changeme}
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
- 删除 Flyway `V1.0.5__create_cas_service_credential.sql`（保留文件，由 V2.0.1 删除表）
- 删除 `CasServiceRegisterDTO`, `CasServiceDetailDTO`, `CasServiceSummaryDTO`, `CasServiceAuthMethodDTO`, `CasServiceRegisterResultDTO`

---

### Phase 3: MFA + 密码管理 + 用户管理

**目标**: 完善用户生命周期管理，增强认证安全。

**预估工时**: 5-7 天

#### 3.1 新增依赖

```groovy
// MFA (gauth/webauthn 模块已传递引入 MFA 核心 API)
implementation "org.apereo.cas:cas-server-core-authentication-mfa-api"
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

> **注意**：以下配置中的密码策略属性（如 `password-length-min`、`password-require-upper` 等）
> 需要在实施时与 CAS 7.3.x 官方配置元数据核实。CAS 密码策略可能通过 Groovy 脚本或
> 自定义 `PasswordPolicy` Bean 实现，而非直接通过 YAML 属性。此处作为设计参考，
> 具体属性名以 CAS 官方文档为准。

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
- 删除 Flyway `V1.0.1__create_cas_app_access.sql`（保留文件，由 V2.0.1 删除表）

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
implementation "org.apereo.cas:cas-server-support-email-sending"
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
      # 注意：Pac4j 使用索引数组配置多个 Provider
      # 微信网页登录（注意：不同于小程序登录）
      wechat[0]:
        id: ${WECHAT_WEB_APPID:}
        secret: ${WECHAT_WEB_SECRET:}
      # Google
      google[0]:
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
- 删除 Flyway `V1.0.2__create_cas_user_change_log.sql`（保留文件，由 V2.0.1 删除表）
- 删除 Flyway `V1.0.3__create_cas_sync_strategy.sql`（保留文件，由 V2.0.1 删除表）
- 删除相关 DTO: `UserSyncWebhookPayloadDTO`, `UserSyncPushFailureDTO`, `CasSyncStrategyDTO`, `UserIdRequestDTO`

---

### Phase 4.5: JWT/Token 全链路 + 扫码登录 + 无密码认证 + 认证安全

**目标**: 建立 JWT 全链路支持（资源服务认证）、QR Code 扫码登录、无密码认证、防暴力破解。

**预估工时**: 5-6 天

#### 4.5.1 新增依赖

```groovy
// JWT 全链路
implementation "org.apereo.cas:cas-server-support-jwt-authentication"
implementation "org.apereo.cas:cas-server-support-jwt-service-ticket"
implementation "org.apereo.cas:cas-server-support-rest-tokens"

// QR Code 扫码登录
implementation "org.apereo.cas:cas-server-support-qr-authn"

// 无密码认证（Magic Link）
implementation "org.apereo.cas:cas-server-support-passwordless"

// 认证节流/防暴力破解
implementation "org.apereo.cas:cas-server-support-authentication-throttle"
```

#### 4.5.2 JWT 认证配置

CAS 支持三种 JWT 模式，全部通过 Service 定义配置：

**JWT 认证（使用 JWT 作为凭证直接登录）**：
```yaml
cas:
  authn:
    jwt:
      core:
        enabled: true
      crypto:
        signing:
          key: ${JWT_SIGNING_KEY:changeme-changeme-changeme-changeme-changeme-changeme-changeme}
        encryption:
          key: ${JWT_ENCRYPTION_KEY:changeme-changeme-changeme}
```

**JWT Service Ticket（按服务配置，替代传统 opaque ST）**：
```json
// 在 Service 注册时配置
{
  "@class": "org.apereo.cas.services.CasRegisteredService",
  "serviceId": "https://app.example.com/.*",
  "name": "JWT ST Enabled App",
  "id": 3000,
  "properties": {
    "jwtAsServiceTicket": {
      "@class": "org.apereo.cas.services.DefaultRegisteredServiceProperty",
      "values": ["true"]
    }
  }
}
```

**OAuth2 JWT Access Token（按服务配置）**：
```json
{
  "@class": "org.apereo.cas.support.oauth.services.OAuthRegisteredService",
  "serviceId": "https://api.example.com/.*",
  "name": "JWT Access Token API",
  "id": 3001,
  "clientId": "api-client",
  "clientSecret": "hashed-secret",
  "jwtAccessToken": true,
  "properties": {
    "accessTokenAsJwt": {
      "@class": "org.apereo.cas.services.DefaultRegisteredServiceProperty",
      "values": ["true"]
    },
    "accessTokenAsJwtSigningKey": {
      "@class": "org.apereo.cas.services.DefaultRegisteredServiceProperty",
      "values": ["${JWT_ACCESS_TOKEN_SIGNING_KEY}"]
    }
  }
}
```

**Token Introspection 端点**（供资源服务器验证 Token）：
- `POST /cas/oauth2.0/introspect` — OAuth2 Token 内省
- 支持 JWT 和 opaque token 两种格式
- 资源服务器无需回调 CAS 即可本地验证 JWT Access Token

#### 4.5.3 QR Code 扫码登录

CAS 原生 QR Code 认证模块提供完整的扫码登录 Webflow：
- PC 端显示 QR Code，移动端扫码确认登录
- 内置 WebSocket 长连接通知机制
- 可与移动端已登录 Session 关联

```yaml
cas:
  authn:
    qr:
      core:
        enabled: true
        # QR Code 有效期（秒）
        ttl: 300
        # QR Code 尺寸
        qr-size: 256
        # 验证端点（移动端调用确认登录）
        verify-url: ${cas.server.prefix}/qr/verify
```

> **重要**：CAS QR Code 认证模块要求移动端已通过其他方式（如小程序、App）认证。
> 对于 beenest 场景，移动端用户已通过小程序登录后，扫码 PC 端 QR Code 即可完成 PC 端登录。
> 这与现有的小程序认证流程互补而非替代。

#### 4.5.4 无密码认证 (Passwordless)

CAS 原生 Passwordless 模块支持 Magic Link 邮件/短信认证：
- 用户输入手机号或邮箱
- CAS 发送一次性 Token（短信/邮件）
- 用户输入 Token 或点击 Magic Link 完成认证

```yaml
cas:
  authn:
    passwordless:
      core:
        enabled: true
        # Token 有效期（秒）
        token-expire-seconds: 300
        # 是否允许多次使用同一 Token
        multiple-token-usage: false
      # 用户存储（通过 Groovy 脚本查询用户信息）
      groovy:
        location: classpath:passwordlessUserStore.groovy
```

Groovy 脚本 `passwordlessUserStore.groovy` 示例（连接 cas_user 表）：
```groovy
import org.apereo.cas.authentication.principal.Principal
import org.apereo.cas.authentication.Credential
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

def run(Object[] args) {
    def (username, logger) = args
    def dataSource = applicationContext.getBean("dataSource", DataSource.class)
    def jdbc = new JdbcTemplate(dataSource)
    def user = jdbc.queryForMap(
        "SELECT user_id, phone, email FROM cas_user WHERE username = ? OR phone = ? AND status = 1",
        username, username
    )
    if (user) {
        return [
            username: user.user_id,
            email   : user.email,
            phone   : user.phone,
            name    : user.user_id
        ]
    }
    return null
}
```

#### 4.5.5 认证节流/防暴力破解

替换自定义的 `failed_login_count` + `lock_until_time` 逻辑：

```yaml
cas:
  authn:
    throttle:
      core:
        enabled: true
        # 失败次数阈值
        failure-threshold: 5
        # 失败后锁定时间（秒）
        failure-range-in-seconds: 300
        # 使用 ExponentialBackoff 策略：失败越多等待越长
        username-parameter: username
        # Redis 后端存储失败记录
      redis:
        host: ${REDIS_HOST:localhost}
        port: ${REDIS_PORT:6379}
        password: ${REDIS_PASSWORD:}
```

> **注意**：CAS 原生节流是**基于 IP 或用户名**的临时限制，不等同于永久账户锁定。
> 如果业务需要"超过 N 次失败永久锁定账户"，仍需自定义 AuthenticationPostProcessor。
> 推荐：CAS 原生节流处理短期防暴力 + 自定义 PostProcessor 处理长期锁定。

#### 4.5.6 会话管理/踢人下线

CAS 通过 TGT 管理实现会话控制：

**原生能力**：
- `DELETE /cas/ticket/{tgtId}` — 销毁 TGT，级联 SLO 到所有已登录应用
- Redis Ticket Registry 支持查询所有活跃 TGT
- `track-descendant-tickets: true`（已配置）确保登出时清理所有 ST

**需要自定义的管理端点**（CAS 无原生踢人 UI）：
```java
@RestController
@RequestMapping("/api/admin/session")
public class SessionManagementController {
    // 踢人下线：销毁指定用户的所有 TGT
    // 需要通过 Redis Ticket Registry 查询 tgtId
    // 然后调用 ticketRegistry.deleteTicket(tgtId)
    // CAS 会自动触发 SLO 到所有关联应用
}
```

此 Controller 需要**保留为自定义代码**，但实现非常轻量（~50 行）。

---

### Phase 5: 认证处理器重构 + 用户注册/Provisioning + 服务访问策略 + 客户端兼容

**目标**: 重构保留的认证处理器、实现用户自注册与自动授权、完善服务级访问控制、确保客户端兼容。

**预估工时**: 7-9 天

#### 5.0 新增依赖

```groovy
// 用户注册与账户管理
implementation "org.apereo.cas:cas-server-support-account-registration"
implementation "org.apereo.cas:cas-server-support-account-management"

// 属性解析（Service Access Strategy 需要）
implementation "org.apereo.cas:cas-server-support-person-directory"
```

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

#### 5.3 用户自注册与自动 Provisioning

CAS 原生支持用户自注册（Account Registration），并可在注册时自动 Provisioning 到目标系统。

**配置**：
```yaml
cas:
  account-registration:
    core:
      enabled: true
      # 注册后自动创建用户
      auto-create-accounts: true
    # 使用 Groovy 脚本处理注册请求（写入 cas_user 表）
    provisioning:
      groovy:
        location: classpath:accountRegistrationProvisioning.groovy
```

**Groovy Provisioning 脚本**（整合 `UserIdentityService` 的自动注册逻辑）：
```groovy
// accountRegistrationProvisioning.groovy
// CAS 注册流程 → 此脚本 → 写入 cas_user 表 + 自动授权服务
def run(Object[] args) {
    def (registrationRequest, logger) = args
    def dataSource = applicationContext.getBean("dataSource", DataSource.class)
    def jdbc = new JdbcTemplate(dataSource)

    // 1. 检查用户是否已存在
    def existing = jdbc.queryForList(
        "SELECT user_id FROM cas_user WHERE phone = ? OR email = ? AND status != 4",
        registrationRequest.phone, registrationRequest.email
    )
    if (existing) {
        // 已存在，执行合并逻辑
        return existing[0].user_id
    }

    // 2. 创建新用户
    def userId = UUID.randomUUID().toString().replace("-", "").substring(0, 32)
    jdbc.update("""
        INSERT INTO cas_user (user_id, username, phone, email, password_hash,
                              phone_verified, email_verified, status, source)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'WEB')
    """, userId, registrationRequest.username, registrationRequest.phone,
         registrationRequest.email, registrationRequest.passwordHash,
         registrationRequest.phone != null, registrationRequest.email != null)

    // 3. 自动授权默认服务（替代 cas_app_access 表逻辑）
    // 通过 CAS Service Access Strategy 的 requiredAttributes 实现
    // 或通过 REST 调用 Service Management API 注册用户-服务关系

    return userId
}
```

**自动授权机制**：
- CAS Service Access Strategy 支持按属性控制访问：用户注册后自动获得特定属性（如 `memberOf=app-10001`）
- 在 Service 定义中配置 `requiredAttributes`，只有包含该属性的用户才能访问
- 自定义认证处理器（微信/支付宝等）认证成功后，`UserIdentityService` 在返回的 Principal 中附带 `memberOf` 属性

**自动注册能力矩阵**（各认证渠道的自动注册支持）：

| 认证渠道 | 自动注册 | 实现方式 |
|---|---|---|
| 微信小程序 | ✅ 支持 | `UserIdentityService` 自动创建（openid → cas_user） |
| 抖音小程序 | ✅ 支持 | 同上（douyin_openid → cas_user） |
| 支付宝小程序 | ✅ 支持 | 同上（alipay_uid → cas_user） |
| SMS OTP | ✅ 支持 | CAS Passwordless + Groovy Provisioning |
| App Token | ❌ 需先注册 | 不支持自动注册，需先通过其他渠道创建账户 |
| JDBC 用户名密码 | ✅ 支持 | CAS Account Registration Webflow |
| QR Code 扫码 | ❌ 需先登录 | 移动端已认证后才能扫码 |
| 委托认证（社交登录）| ✅ 支持 | CAS Delegate Authentication + Provisioning |

#### 5.4 Service Access Strategy（用户-应用访问控制）

CAS 原生 Service Access Strategy 替代自定义的 `cas_app_access` 表，提供更强大的细粒度访问控制。

**模式一：基于属性的访问控制（推荐）**：
```json
{
  "@class": "org.apereo.cas.services.CasRegisteredService",
  "serviceId": "https://drone-system.beenest.club/.*",
  "name": "Drone System",
  "id": 10001,
  "accessStrategy": {
    "@class": "org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy",
    "enabled": true,
    "ssoEnabled": true,
    "requiredAttributes": {
      "@class": "java.util.HashMap",
      "memberOf": ["java.util.HashSet", ["DRONE_SYSTEM_USER"]]
    },
    "unauthorizedRedirectUrl": "https://sso.beenest.club/cas/login"
  }
}
```

**模式二：REST 远程授权（最灵活）**：
```json
{
  "accessStrategy": {
    "@class": "org.apereo.cas.services.RestRegisteredServiceAccessStrategy",
    "endpointUrl": "https://internal-api.beenest.club/cas/access-check",
    "acceptableResponseCodes": "200"
  }
}
```
CAS 将用户 Principal 信息 POST 到远程端点，端点返回 200（允许）或 403（拒绝）。

**模式三：自定义扩展（BeenestAccessStrategy）**：
保留轻量自定义扩展，用于复杂业务逻辑（如时间段限制、设备限制等）：
```java
public class BeenestAccessStrategy extends DefaultRegisteredServiceAccessStrategy {
    // 扩展 CAS 原生策略
    // 可组合属性检查 + REST 远程检查
}
```

#### 5.5 会话管理/踢人下线

CAS 原生通过 TGT 销毁 + SLO 级联实现会话管理。需要自定义一个轻量管理端点：

```java
// 需要保留为自定义代码（CAS 无原生踢人 UI/API）
@RestController
@RequestMapping("/api/admin/session")
public class SessionManagementController {
    private final TicketRegistry ticketRegistry;

    // 1. 查询指定用户的所有活跃会话
    @GetMapping("/user/{userId}/sessions")
    public List<SessionInfo> getUserSessions(@PathVariable String userId) {
        // 从 Redis Ticket Registry 查询该用户的所有 TGT
    }

    // 2. 踢人下线：销毁指定 TGT，级联 SLO
    @DeleteMapping("/ticket/{tgtId}")
    public void kickOut(@PathVariable String tgtId) {
        ticketRegistry.deleteTicket(tgtId);
        // CAS 自动触发 SLO 到所有关联应用
    }

    // 3. 踢指定用户的所有会话
    @DeleteMapping("/user/{userId}/sessions")
    public void kickAll(@PathVariable String userId) {
        // 查询并删除该用户所有 TGT
    }
}
```

> **关键原理**：Redis Ticket Registry 存储了所有活跃 TGT，TGT 关联了用户 Principal。
> 销毁 TGT 时 CAS `LogoutManager` 自动向所有注册服务发送 SLO 请求。
> 已配置的 `track-descendant-tickets: true` 确保级联清理。

#### 5.6 CasOverlayOverrideConfiguration 瘦身

原配置类注册了 5 个 Handler + 9 个 Controller + BeenestAccessStrategy。
重构后只需要注册：
- 3-5 个自定义认证处理器
- 1-2 个自定义 Controller（小程序入口）
- BeenestAccessStrategy 扩展
- Person Directory 自定义属性解析器

#### 5.7 客户端 Starter 兼容性

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
-- （Phase 3 中已定义）

-- V2.0.1__drop_deprecated_custom_tables.sql
-- 删除被 CAS 原生替代的自定义表
-- 注意：V1.0.x Flyway 脚本文件必须保留（Flyway checksum 校验需要）
DROP TABLE IF EXISTS cas_auth_audit_log;
DROP TABLE IF EXISTS cas_app_access;
DROP TABLE IF EXISTS cas_sync_strategy;
DROP TABLE IF EXISTS cas_user_change_log;
DROP TABLE IF EXISTS cas_service_credential;

-- V2.0.2__create_surrogate_and_aup_tables.sql
-- 模拟认证表
CREATE TABLE IF NOT EXISTS cas_surrogate (
    id BIGSERIAL PRIMARY KEY,
    principal VARCHAR(128) NOT NULL,
    surrogate_user VARCHAR(128) NOT NULL,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(principal, surrogate_user)
);

-- 使用条款接受记录
CREATE TABLE IF NOT EXISTS cas_user_aup (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL UNIQUE REFERENCES cas_user(user_id),
    accepted BOOLEAN DEFAULT FALSE,
    accepted_time TIMESTAMP,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 注意：以下表由 CAS 原生模块自动创建（ddl-auto: update，仅开发环境使用）：
-- - COM_AUDIT_TRAIL (Inspektr)
-- - registered_service + related (JPA Service Registry)
-- - SamlIdPMetadata (SAML2 JPA)
-- - MFA 设备注册表 (gauth/webauthn JPA)
-- - 用户同意表 (consent JPA)
-- - OAuth2/OIDC 相关表
-- 生产环境部署前，应从开发环境导出这些 DDL 生成正式的 Flyway 脚本，
-- 并将所有 ddl-auto 切换为 validate。
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
| 迁移 | `V2.0.2__create_surrogate_and_aup_tables.sql` | 模拟认证表 + AUP 表 |
| 模板 | `templates/casLoginView.html` 等 | CAS UI 定制 |
| 认证 | `BeenestAccessStrategy` (重构) | CAS 原生扩展 |
| 认证 | Person Directory 配置 | 自定义属性解析 |
| Groovy | `passwordlessUserStore.groovy` | 无密码认证用户查询 |
| Groovy | `accountRegistrationProvisioning.groovy` | 用户注册 Provisioning |
| 会话管理 | `SessionManagementController.java` | 踢人下线/会话查询 API |
| 属性解析 | 自定义 `PersonDirectoryAttributeResolver` | 从 cas_user 解析角色/权限属性 |

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
| Migration | `V1.0.1` ~ `V1.0.5` | **保留文件**（Flyway 校验需要），其创建的表由 V2.0.1 删除 |

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
| QR Code 扫码登录需要移动端配合 | 前后端联动开发 | QR Code 端点由 CAS 原生提供，移动端只需调用确认 API |
| JWT Service Ticket 跨域验证 | 资源服务需配置公钥 | 使用 JWKS 端点分发公钥，资源服务本地验证 |
| Groovy Provisioning 脚本出错 | 用户注册失败 | 充分测试 Groovy 脚本，添加异常处理和日志 |

### 5.2 注意事项

1. **版本锁定**: 所有 `cas-server-support-*` 模块不指定版本号，由 CAS BOM 统一管理
2. **ddl-auto 策略**: 开发环境可用 `update`（自动建表），生产环境必须用 `validate` + Flyway
3. **密钥管理**: 所有加密密钥（TGC、Ticket、SAML、OIDC、WS-Fed STS）必须通过环境变量注入，不得硬编码
4. **测试策略**: 每个 Phase 完成后必须通过集成测试验证
5. **文档更新**: 每个 Phase 完成后更新 CLAUDE.md 和 README
6. **Flyway 脚本保留**: V1.0.x 脚本文件不得删除——即使其创建的表已被 V2.0.1 DROP，Flyway 需要这些文件做 checksum 校验。全新环境部署时，V1.0.x 先执行建表、V2.0.1 再删表，保证迁移链完整。

### 5.3 CAS Service Management Webapp 部署说明

CAS 原生的服务管理 UI 是一个**独立的 WAR overlay**（`cas-management`），并非 CAS Server 内置模块。部署方式：

1. **独立部署**：作为单独的 Spring Boot 应用运行，连接同一个 Service Registry 数据库
2. **嵌入 CAS Server**：将 `cas-server-support-reports` 的管理功能用于基础服务查看，但完整的服务 CRUD 需要独立部署 Management Webapp
3. **推荐方案**：先阶段使用 `cas-server-support-reports` 提供的基础管理能力 + 直接通过 Service Registry JSON/数据库管理服务；后续根据需要部署独立的 Management Webapp

### 5.4 客户端 Starter 影响分析

**`beenest-cas-client-spring-security-starter`（39 个 Java 文件）**需要在服务端重构后进行适配：

| 客户端组件 | 当前实现 | 重构后方案 | 优先级 |
|---|---|---|---|
| Bearer Token 过滤器 | 自定义 TGT 验证 | 保留（CAS Protocol 兼容）+ 新增 OIDC Token 验证 | 高 |
| SLO 单点登出 | CAS Protocol Back-Channel | 保留 + 新增 OIDC Front-Channel | 中 |
| 用户同步 (Pull) | 定时调用 `/api/user/changes` | 过渡保留，长期切换为 OIDC UserInfo 端点 | 低 |
| 用户同步 (Webhook) | 接收推送通知 | 过渡保留，长期切换为事件驱动 | 低 |
| Token 刷新 | 自定义 refreshToken | 保留 + 新增 OAuth2 Refresh Token 标准流程 | 高 |
| 权限同步 | 从 CAS 获取权限属性 | 通过 OIDC Scope + Claims 标准化 | 中 |

**兼容策略**：
- Phase 1-4 重构不影响客户端（CAS Protocol 不变）
- Phase 5 认证处理器重构需验证 Token 兼容性
- 客户端新增 OIDC/OAuth2 支持可在 Phase 5 之后独立进行

---

## 六、成功标准

| 指标 | 目标 |
|---|---|
| 支持的协议 | CAS + SAML2 + OAuth2 + OIDC + REST + WS-Federation |
| 认证方式 | 5 种自定义（微信/抖音/支付宝/SMS/AppToken）+ JDBC + LDAP + 社交登录 + QR Code 扫码 + 无密码 Magic Link |
| MFA | Google Authenticator + FIDO2/WebAuthn |
| JWT 全链路 | JWT 认证 + JWT Service Ticket + JWT OAuth2 Access Token + Token Introspection |
| 审计覆盖 | 100% 认证/票据/服务操作审计 |
| 密码管理 | 重置/策略/历史/过期 |
| 企业级功能 | 用户同意/AUP/模拟认证/自适应认证/监控 |
| 用户管理 | 自注册/自动 Provisioning/账户 Profile/会话管理/踢人下线 |
| 服务访问控制 | 属性/角色/REST 远程授权/自定义策略 |
| 认证安全 | 防暴力破解(ExponentialBackoff)/自适应风险认证 |
| 自定义代码减少 | 从 ~90 个 Java 文件减少到 ~40 个 |
| CAS 原生模块数 | 从 13 个增加到 ~45 个 |
| Admin 能力 | Dashboard + Service Management UI + Actuator + 会话管理 |
