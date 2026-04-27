# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This is the **beenest-infrastructure** monorepo containing two independent infrastructure services for the Beenest drone platform. Each service has its own build system and can be developed/deployed independently.

- **beenest-cas**: Apereo CAS 7.3.6 SSO server (Gradle, Java 21, Spring Boot 3.5.6). Four Gradle subprojects: `beenest-cas-service` (overlay server), `beenest-cas-client-spring-security-starter`, `beenest-cas-client-login-gateway-starter`, `beenest-cas-client-resource-server-starter`.
- **beenest-payment**: Payment microservice (Maven, Java 21, Spring Boot 3.5.11)

## Build and Development Commands

### beenest-cas

```bash
cd beenest-cas

# Build (includes all subprojects: beenest-cas-service + 3 starters)
./gradlew clean build

# Run locally (starts at https://localhost:8443/cas)
./gradlew :beenest-cas-service:bootRun

# Run tests
./gradlew :beenest-cas-service:test

# Debug mode (JDWP on port 5000)
./gradlew :beenest-cas-service:debug

# Docker build (Jib)
./gradlew :beenest-cas-service:jibDockerBuild

# Docker build (Dockerfile)
./gradlew :beenest-cas-service:casBuildDockerImage

# Generate keystore for local HTTPS
./gradlew :beenest-cas-service:createKeystore
```

### beenest-payment

```bash
cd beenest-payment

# Build all modules
mvn clean install

# Run the service (port 8082)
cd beenest-payment-service && mvn spring-boot:run

# Run tests
mvn test

# Docker build (multi-stage, Eclipse Temurin JRE 21)
docker build -t beenest-payment .
```

## Architecture

### beenest-cas — Enterprise CAS SSO Server

Apereo CAS 7.3.6 overlay with ~49 CAS native modules and custom Chinese platform authentication. Provides enterprise-grade SSO for all Beenest services.

**Project structure** (4 Gradle subprojects):
- `beenest-cas-service` — CAS overlay server implementation (WAR, Jib Docker image)
- `beenest-cas-client-spring-security-starter` — Spring Security client starter (published as `club.beenest.cas:beenest-cas-client-spring-security-starter:1.0.0-SNAPSHOT`)
- `beenest-cas-client-login-gateway-starter` — Login gateway starter
- `beenest-cas-client-resource-server-starter` — Resource server starter

**Root package**: `org.apereo.cas.beenest`

**Supported protocols**: CAS Protocol 3.0, SAML2 IdP, OAuth2 Provider, OIDC Provider, REST Protocol, WS-Federation.

**Custom authentication handlers** (registered via `CasOverlayOverrideConfiguration`):
- WeChat mini-program (`WechatMiniCredential` / `WechatMiniAuthenticationHandler`)
- Douyin mini-program (`DouyinMiniCredential` / `DouyinMiniAuthenticationHandler`)
- Alipay mini-program (`AlipayMiniCredential` / `AlipayMiniAuthenticationHandler`)
- SMS OTP (`SmsOtpCredential` / `SmsOtpAuthenticationHandler`)
- App token (`AppTokenCredential` / `AppTokenAuthenticationHandler`)

**CAS native enterprise modules**:
- **MFA**: Google Authenticator (gauth JPA) + FIDO2/WebAuthn (JPA) + Trusted Devices (JDBC)
- **Password Management**: BCrypt-12, history tracking (6 entries), reset/expiry, must-change
- **JWT**: token-tickets (JWT Service Ticket) + token-webflow + rest-tokens (OAuth2 JWT)
- **Security**: CAPTCHA, Throttle (Redis rate limiting), Electrofence (adaptive risk), Interrupt Webflow
- **Enterprise**: Consent (JDBC), AUP (JDBC), Surrogate (JDBC proxy login), Pac4j OIDC (social login)
- **User Management**: Account Management, Groovy Provisioning (auto-registration), Session Management (TGT destruction + SLO cascade)
- **Auth methods**: QR Code scan, Passwordless (Magic Link via Groovy user store)
- **Monitoring**: Inspektr Audit (JDBC), Events JPA, Palantir Dashboard, Spring Boot Actuator
- **Notifications**: Email (Spring Mail), SMS (Aliyun via CAS `SmsSender` interface)

**User identity** (`UserIdentityService`): Unified user model across all channels. Account merging priority: unionid > openid > phone. Auto-registration on first login.

**SMS integration** (`AliyunSmsSender`): Implements CAS `SmsSender` interface for Aliyun SMS. Auto-degrades to log output when accessKey/secretKey not configured (dev mode). Bean name must be `smsSender`.

**Session management** (`SessionManagementController`): Admin API for kicking users offline via TGT destruction with automatic SLO cascade.

**Data layer**: MyBatis with remaining mappers. PostgreSQL schema `beenest_cas`. Flyway migrations V1.0.x (legacy) + V2.0.x (nativization). Tables: `cas_user`, `cas_surrogate`, `aup_usage_terms`, CAS native tables (Inspektr audit, events, consent, etc.).

**Groovy scripts**:
- `interrupt.groovy` — Checks `mustChangePassword` attribute for password expiry interrupt
- `passwordlessUserStore.groovy` — Queries `cas_user` by username/phone/email for passwordless auth
- `accountRegistrationProvisioning.groovy` — Dedup + create user in `cas_user` for auto-registration

**Templates** (Thymeleaf, Aurora dark theme):
- `layout.html` — Base layout with brand header/footer, shared by all secondary pages
- `login/casLoginView.html` — Standalone dual-panel login page (password + SMS modes)
- 15 override templates: logout, MFA (gauth/webauthn), AUP, consent, surrogate, passwordless, password-reset, interrupt, error, adaptive-authn, login-error, mfa-trusted-devices

**Configuration properties**:
- `beenest.miniapp.*` — WeChat/Douyin/Alipay app credentials
- `beenest.sms.*` — Aliyun SMS accessKey/secretKey/template
- `beenest.token.*` — Access/refresh token TTL and rotation settings

**Client starter** (`beenest-cas-client-spring-security-starter`): Spring Boot starter for downstream services. Activated with `cas.client.enabled=true`. Provides Bearer token auth filter, SSO/SLO, TGT validation. Published as `club.beenest.cas:beenest-cas-client-spring-security-starter:1.0.0-SNAPSHOT`. The `beenest-cas-service` subproject is the CAS overlay server implementation, containing all Java source, resources, Docker/Jib configuration.

**Deleted code** (Phase 1-6): ~3300 lines removed. Custom audit (`AuthAuditService`), service management (`CasServiceAdmin*`), user admin (`CasUserAdmin*`), sync strategy (`SyncStrategy*`, `UserSync*`), service credentials (`CasServiceCredential*`), app access (`CasAppAccess*`) — all replaced by CAS native modules or converted to no-op shells.

### beenest-payment — Payment Microservice

Two-module Maven project: API contract module (`beenest-payment-api`) and service implementation (`beenest-payment-service`).

**Root package**: `club.beenest.payment`

**Inter-service communication**: Downstream services import `beenest-payment-api` as a JAR dependency and use `PaymentFeignClient` (30+ endpoints). Service discovery via Nacos. Circuit breaking via Sentinel.

**Payment strategy pattern**: `PaymentStrategy` interface → `AbstractPaymentStrategy` (template method) → concrete implementations (`WechatPaymentStrategy`, `AlipayPaymentStrategy`, `DouyinPaymentStrategy`). `PaymentStrategyFactory` auto-discovers beans. Same pattern for withdrawals via `WithdrawStrategy`.

**Internal API security** (`InternalApiFilter` on `/internal/**`): Three-layer — IP whitelist, static token (`X-Internal-Token`), HMAC-SHA256 signature with nonce anti-replay.

**Wallet integrity**: Every wallet row stores a `balance_hash` (HMAC-SHA256 over balance fields). All mutations compute hash atomically with optimistic locking (`version`). `WalletIntegrityScheduler` verifies periodically.

**MQ reliability** (Outbox pattern): `PaymentEventProducer` writes to `ds_payment_outbox` table on RabbitMQ failure. `OutboxMessageScheduler` retries with exponential backoff. All messages signed with HMAC-SHA256.

**Schedulers**: `PaymentOrderExpireScheduler`, `RefundStatusSyncScheduler`, `OutboxMessageScheduler`, `WalletIntegrityScheduler`.

**Database**: PostgreSQL schema `beenest_payment`. Tables prefixed `ds_`. Flyway migrations `V1_0_0` through `V1_0_5`. MyBatis mapper XMLs in `resources/mapper/payment/`.

**Key environment variables**: `PAYMENT_DB_URL`, `NACOS_ADDR`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `WALLET_HASH_SECRET`.

## Code Comment Standards

All code must follow these comment standards:

### Backend (Java)
- **Class-level**: Every class must have a Javadoc explaining its purpose.
- **Public methods**: Javadoc describing what the method does, parameters, and return values.
- **Complex logic**: Non-obvious conditional branches, algorithms, and workarounds must have inline comments explaining **why**.
- **Step comments**: Separate logical steps with numbered comments (e.g., `// 1. 参数校验`, `// 2. 构建订单`).
- Comments must be written in Chinese (matching existing codebase convention).

### What NOT to comment
- Obvious self-explanatory code.
- Redundant comments that restate the code.
