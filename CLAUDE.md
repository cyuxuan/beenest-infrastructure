# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This is the **beenest-infrastructure** monorepo containing two independent infrastructure services for the Beenest drone platform. Each service has its own build system and can be developed/deployed independently.

- **beenest-cas**: Apereo CAS 7.3.6 SSO server (Gradle, Java 21, Spring Boot 3.5.6)
- **beenest-payment**: Payment microservice (Maven, Java 21, Spring Boot 3.5.11)

## Build and Development Commands

### beenest-cas

```bash
cd beenest-cas

# Build (includes beenest-cas-client-spring-security-starter submodule)
./gradlew clean build

# Run locally (starts at https://localhost:8443/cas)
./gradlew bootRun

# Run tests
./gradlew test

# Debug mode (JDWP on port 5000)
./gradlew debug

# Docker build
./gradlew build jibDockerBuild

# Generate keystore for local HTTPS
./gradlew createKeystore
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

### beenest-cas — CAS SSO Server

Custom Apereo CAS overlay extended with Chinese platform authentication. Provides SSO for all Beenest services.

**Root package**: `org.apereo.cas.beenest`

**Authentication handlers** (registered via `CasOverlayOverrideConfiguration`):
- WeChat mini-program (`WechatMiniCredential` / `WechatMiniAuthenticationHandler`)
- Douyin mini-program (`DouyinMiniCredential` / `DouyinMiniAuthenticationHandler`)
- Alipay mini-program (`AlipayMiniCredential` / `AlipayMiniAuthenticationHandler`)
- SMS OTP (`SmsOtpCredential` / `SmsOtpAuthenticationHandler`)
- App token (`AppTokenCredential` / `AppTokenAuthenticationHandler`)

**User identity** (`UserIdentityService`): Unified user model across all channels. Account merging priority: unionid > openid > phone. Auto-registration on first login.

**User sync**: Two modes for downstream services:
- **Push** (webhook): `UserSyncPushService` sends async HTTP POST with HmacSHA256 signatures
- **Pull**: Downstream services call `/api/user/changes` endpoint

**Access control** (`BeenestAccessStrategy`): Per-application access grants checked at ST issuance via `cas_app_access` table.

**Data layer**: MyBatis with 5 mappers. PostgreSQL schema `beenest_cas`. Flyway migrations in `src/main/resources/db/migration/`.

**Configuration properties**:
- `beenest.miniapp.*` — WeChat/Douyin/Alipay app credentials
- `beenest.sms.*` — SMS provider (Aliyun)
- `beenest.token.*` — Access/refresh token TTL and rotation settings

**Client starter** (`beenest-cas-client-spring-security-starter`): Spring Boot starter for downstream services. Activated with `cas.client.enabled=true`. Provides Bearer token auth filter, SSO/SLO, TGT validation, user sync (pull scheduler + webhook receiver). Published as `club.beenest.cas:beenest-cas-client-spring-security-starter:1.0.0-SNAPSHOT`.

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
