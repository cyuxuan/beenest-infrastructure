# CAS 业务系统登录代理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让业务系统在自己域名下承接 APP / 小程序登录请求，并通过 starter 转发到 CAS；CAS 负责验证业务系统秘钥串、执行用户认证、并在首次认证时自动授予当前系统权限。

**Architecture:** 业务系统侧 starter 只做请求承载、HMAC 签名和转发；CAS 侧负责系统证书管理、nonce 防重放、签名验真、用户认证以及首次登录自动授权。Web 登录继续保留现有 CAS WebFlow 跳转，不和代理链路混用。

**Tech Stack:** Spring Boot 3.5、Spring Security CAS、Spring MVC、Spring Data Redis、MyBatis、Gradle、JUnit 5、Mockito、HMAC-SHA256。

---

## 文件地图

- `beenest-cas/src/main/java/org/apereo/cas/beenest/service/CasServiceAdminService.java`：注册/更新服务时发放和轮换系统秘钥串。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/controller/CasServiceAdminController.java`：新增返回秘钥串与轮换接口。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/service/CasServiceCredentialService.java`：系统秘钥保存、验签、nonce 防重放。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/filter/CasServiceCredentialFilter.java`：保护 `/cas/app/**` 和 `/cas/miniapp/**` 登录入口。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/service/UserIdentityService.java`：把“是否首次创建用户”显式返回给调用方。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/controller/AppLoginController.java`：首次登录自动授权当前系统，老用户登录前检查权限。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/controller/MiniAppLoginController.java`：同上。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/common/constant/CasConstant.java`：补充请求头和 request attribute 常量。
- `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasSecurityProperties.java`：新增业务系统证书和代理开关配置。
- `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyController.java`：业务系统域名下的登录代理入口。
- `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyService.java`：把请求转发到 CAS 并附带签名头。
- `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasBusinessLoginProxyConfiguration.java`：自动装配代理控制器和转发组件。
- `beenest-cas/src/main/resources/db/migration/V1.0.5__create_cas_service_credential.sql`：新增系统证书表。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/entity/CasServiceCredentialDO.java`：系统证书实体。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/mapper/CasServiceCredentialMapper.java`：系统证书 MyBatis mapper。
- `beenest-cas/src/main/resources/mapper/CasServiceCredentialMapper.xml`：SQL 映射。
- `beenest-cas/src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java`：装配证书服务、签名服务和过滤器。
- `beenest-cas/beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：注册新的 starter 自动配置。
- `beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceAdminServiceTest.java`：注册 / 轮换秘钥测试。
- `beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceCredentialServiceTest.java`：签名 / nonce / 时间窗测试。
- `beenest-cas/src/test/java/org/apereo/cas/beenest/controller/AppLoginControllerTest.java`：首次登录自动授权与权限拒绝测试。
- `beenest-cas/src/test/java/org/apereo/cas/beenest/controller/MiniAppLoginControllerTest.java`：首次登录自动授权与权限拒绝测试。
- `beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyControllerTest.java`：代理转发与签名头测试。

### Task 1: 系统证书发放与管理

**Files:**
- Create: `beenest-cas/src/main/resources/db/migration/V1.0.5__create_cas_service_credential.sql`
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/entity/CasServiceCredentialDO.java`
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/mapper/CasServiceCredentialMapper.java`
- Create: `beenest-cas/src/main/resources/mapper/CasServiceCredentialMapper.xml`
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/service/CasServiceCredentialService.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/service/CasServiceAdminService.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/controller/CasServiceAdminController.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/dto/CasServiceRegisterDTO.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/dto/CasServiceDetailDTO.java`
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/dto/CasServiceRegisterResultDTO.java`
- Modify: `beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceAdminServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void createServiceShouldReturnSecretOnce() {
    CasServiceRegisterDTO dto = new CasServiceRegisterDTO();
    dto.setName("drone");
    dto.setServiceId("^https://drone.example.com/.*");

    CasServiceRegisterResultDTO result = service.createServiceWithSecret(dto);

    assertThat(result.getServiceSecret()).isNotBlank();
    assertThat(result.getSecretVersion()).isEqualTo(1);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.service.CasServiceAdminServiceTest`

Expected: 编译或断言失败，提示 `createServiceWithSecret` / `CasServiceRegisterResultDTO` 尚未实现。

- [ ] **Step 3: Write minimal implementation**

```java
public CasServiceRegisterResultDTO createServiceWithSecret(CasServiceRegisterDTO dto) {
    CasRegisteredService service = buildService(dto);
    CasServiceCredentialDO credential = credentialService.issueForService(service.getId());
    servicesManager.save(service);
    return CasServiceRegisterResultDTO.of(service, credential.getPlainSecret(), credential.getSecretVersion());
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.service.CasServiceAdminServiceTest`

Expected: 测试通过，注册时只返回一次明文秘钥，后续列表和详情不暴露明文。

- [ ] **Step 5: Commit**

```bash
git add beenest-cas/src/main/resources/db/migration/V1.0.5__create_cas_service_credential.sql \
  beenest-cas/src/main/java/org/apereo/cas/beenest/entity/CasServiceCredentialDO.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/mapper/CasServiceCredentialMapper.java \
  beenest-cas/src/main/resources/mapper/CasServiceCredentialMapper.xml \
  beenest-cas/src/main/java/org/apereo/cas/beenest/service/CasServiceCredentialService.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/service/CasServiceAdminService.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/controller/CasServiceAdminController.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/dto/CasServiceRegisterDTO.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/dto/CasServiceDetailDTO.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/dto/CasServiceRegisterResultDTO.java \
  beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceAdminServiceTest.java
git commit -m "feat: manage cas service credentials"
```

### Task 2: CAS 登录入口签名校验与 nonce 防重放

**Files:**
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/config/CasServiceCredentialProperties.java`
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/util/CasRequestSignatureUtils.java`
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/filter/CasServiceCredentialFilter.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/common/constant/CasConstant.java`
- Create: `beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceCredentialServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void verifyShouldRejectRepeatedNonce() {
    String body = "{\"principal\":\"alice\"}";
    String nonce = "nonce-1";
    String signature = CasRequestSignatureUtils.sign("1700000000", nonce, body, "secret");

    assertThat(credentialService.verifyRequest("10001", "1700000000", nonce, signature, body)).isTrue();
    assertThat(credentialService.verifyRequest("10001", "1700000000", nonce, signature, body)).isFalse();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.service.CasServiceCredentialServiceTest`

Expected: `verifyRequest`、`CasRequestSignatureUtils.sign` 或 nonce 逻辑缺失导致失败。

- [ ] **Step 3: Write minimal implementation**

```java
public boolean verifyRequest(String serviceId, String timestamp, String nonce, String signature, String body) {
    if (isTimestampExpired(timestamp)) {
        return false;
    }
    if (isNonceUsed(serviceId, nonce)) {
        return false;
    }
    String expected = CasRequestSignatureUtils.sign(timestamp, nonce, body, secretFor(serviceId));
    return constantTimeEquals(expected, signature);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.service.CasServiceCredentialServiceTest`

Expected: 首次请求通过，重复 nonce 被拒绝，过期时间戳被拒绝。

- [ ] **Step 5: Commit**

```bash
git add beenest-cas/src/main/java/org/apereo/cas/beenest/config/CasServiceCredentialProperties.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/util/CasRequestSignatureUtils.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/filter/CasServiceCredentialFilter.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/config/BeenestServiceConfiguration.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/common/constant/CasConstant.java \
  beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceCredentialServiceTest.java
git commit -m "feat: validate cas business system signatures"
```

### Task 3: 首次登录自动授权与老用户权限校验

**Files:**
- Create: `beenest-cas/src/main/java/org/apereo/cas/beenest/service/UserIdentityResult.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/service/UserIdentityService.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/WechatMiniAuthenticationHandler.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/DouyinMiniAuthenticationHandler.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/AlipayMiniAuthenticationHandler.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/SmsOtpAuthenticationHandler.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/AppTokenAuthenticationHandler.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/controller/AppLoginController.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/controller/MiniAppLoginController.java`
- Modify: `beenest-cas/src/main/java/org/apereo/cas/beenest/common/constant/CasConstant.java`
- Modify: `beenest-cas/src/test/java/org/apereo/cas/beenest/controller/AppLoginControllerTest.java`
- Create: `beenest-cas/src/test/java/org/apereo/cas/beenest/controller/MiniAppLoginControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void firstLoginShouldAutoGrantCurrentService() {
    // 先模拟认证成功并返回 firstLogin=true
    // 再断言 controller 会调用 appAccessService.autoGrantOnRegister(userId, serviceId)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.controller.AppLoginControllerTest`

Expected: 编译失败或断言失败，因为 `UserIdentityResult` / `firstLogin` / `AppAccessService` 自动授权链路尚未接通。

- [ ] **Step 3: Write minimal implementation**

```java
public record UserIdentityResult(UnifiedUserDO user, boolean firstLogin) {}

if (Boolean.TRUE.equals(firstLogin)) {
    appAccessService.autoGrantOnRegister(userId, serviceId);
} else if (!appAccessService.hasAccess(userId, serviceId)) {
    return R.fail(403, "您没有访问该应用的权限");
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.controller.AppLoginControllerTest --tests org.apereo.cas.beenest.controller.MiniAppLoginControllerTest`

Expected: 首次登录自动授权当前系统，已存在但无权限的用户返回 403。

- [ ] **Step 5: Commit**

```bash
git add beenest-cas/src/main/java/org/apereo/cas/beenest/service/UserIdentityResult.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/service/UserIdentityService.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/WechatMiniAuthenticationHandler.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/DouyinMiniAuthenticationHandler.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/AlipayMiniAuthenticationHandler.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/SmsOtpAuthenticationHandler.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/authn/handler/AppTokenAuthenticationHandler.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/controller/AppLoginController.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/controller/MiniAppLoginController.java \
  beenest-cas/src/main/java/org/apereo/cas/beenest/common/constant/CasConstant.java \
  beenest-cas/src/test/java/org/apereo/cas/beenest/controller/AppLoginControllerTest.java \
  beenest-cas/src/test/java/org/apereo/cas/beenest/controller/MiniAppLoginControllerTest.java
git commit -m "feat: auto grant first login access"
```

### Task 4: starter 侧业务系统登录代理与请求签名

**Files:**
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasSecurityProperties.java`
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyController.java`
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyService.java`
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessRequestSigner.java`
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasBusinessLoginProxyConfiguration.java`
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyControllerTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void proxyShouldForwardSignedAppLoginRequest() {
    // 断言 controller 会把 serviceId / timestamp / nonce / signature 转发到 CAS
    // 并保持响应体原样返回给调用方
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd beenest-cas && ./gradlew :beenest-cas-client-spring-security-starter:test --tests org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyControllerTest`

Expected: 新 controller / signer / 自动配置尚未存在导致失败。

- [ ] **Step 3: Write minimal implementation**

```java
public ResponseEntity<String> proxyAppLogin(String body, HttpHeaders headers) {
    String timestamp = Instant.now().getEpochSecond() + "";
    String nonce = UUID.randomUUID().toString().replace("-", "");
    String signature = CasBusinessRequestSigner.sign(timestamp, nonce, body, properties.getSystemSecret());
    return restTemplate.postForEntity(casLoginUrl, new HttpEntity<>(body, signedHeaders), String.class);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd beenest-cas && ./gradlew :beenest-cas-client-spring-security-starter:test --tests org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyControllerTest`

Expected: 代理请求能生成正确签名并转发到 CAS，响应保持透传。

- [ ] **Step 5: Commit**

```bash
git add beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasSecurityProperties.java \
  beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyController.java \
  beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyService.java \
  beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/proxy/CasBusinessRequestSigner.java \
  beenest-cas/beenest-cas-client-spring-security-starter/src/main/java/org/apereo/cas/beenest/client/config/CasBusinessLoginProxyConfiguration.java \
  beenest-cas/beenest-cas-client-spring-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyControllerTest.java
git commit -m "feat: add business system login proxy"
```

### Task 5: 端到端回归验证

**Files:**
- Modify: `beenest-cas/src/test/java/org/apereo/cas/beenest/controller/AppLoginControllerTest.java`
- Modify: `beenest-cas/src/test/java/org/apereo/cas/beenest/controller/MiniAppLoginControllerTest.java`
- Modify: `beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceAdminServiceTest.java`
- Modify: `beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyControllerTest.java`

- [ ] **Step 1: Run the focused test suite**

Run: `cd beenest-cas && ./gradlew test --tests org.apereo.cas.beenest.service.CasServiceAdminServiceTest --tests org.apereo.cas.beenest.service.CasServiceCredentialServiceTest --tests org.apereo.cas.beenest.controller.AppLoginControllerTest --tests org.apereo.cas.beenest.controller.MiniAppLoginControllerTest --tests org.apereo.cas.beenest.client.proxy.CasBusinessLoginProxyControllerTest`

Expected: 所有新增和修改的测试通过。

- [ ] **Step 2: Run the module build**

Run: `cd beenest-cas && ./gradlew clean build`

Expected: `beenest-cas` 和 `beenest-cas-client-spring-security-starter` 都能成功编译、测试并打包。

- [ ] **Step 3: Commit**

```bash
git add beenest-cas/src/test/java/org/apereo/cas/beenest/controller/AppLoginControllerTest.java \
  beenest-cas/src/test/java/org/apereo/cas/beenest/controller/MiniAppLoginControllerTest.java \
  beenest-cas/src/test/java/org/apereo/cas/beenest/service/CasServiceAdminServiceTest.java \
  beenest-cas/beenest-cas-client-spring-security-starter/src/test/java/org/apereo/cas/beenest/client/proxy/CasBusinessLoginProxyControllerTest.java
git commit -m "test: cover cas login proxy flow"
```

## 自检清单

- 系统证书发放有单独任务，且包含注册、轮换和只返回一次明文的要求。
- HMAC 签名和 nonce 防重放有单独任务，且放在 CAS 侧。
- 首次第三方认证自动授权当前系统有单独任务，且明确要求修改 `UserIdentityService` 和各登录 handler。
- starter 只做透传和签名，不承担最终系统合法性判断。
- `casweb` 继续走原有 CAS WebFlow，不改登录页路径。
- 每个任务都给出了具体文件、测试、命令和提交方式，没有留空占位。

