# 客户端准入策略(Client Admission Policy)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让接入客户端可以声明"允许哪些第一因素登录方式"与"是否强制 MFA",认证方式不满足时走可操作的补验流程而不是把用户挡在门外。

**Architecture:** 策略落 `iam_client` 两列,经 `ClientSettings.setting()` 随 `RegisteredClient` 下发到协议链,授权端点前外挂 `ClientAdmissionFilter` 做准入判定。不匹配时签发 Redis 一次性票据转"补验挑战",补验成功后**升级**原会话的 `loginType` / `mfaVerified`(不新建会话),三条登录路径(表单/REST/联邦)统一在 `IamLoginSessionService.establish` 汇聚,由它单点完成升级。

**Tech Stack:** Java 21 / Spring Boot 4.0.8 / Spring Security 7.0.7 / Spring Authorization Server 7.0.7 / MyBatis 4.0.1 / PostgreSQL / Redis(Lettuce)/ Vue 3 + TypeScript + Vite / JUnit 5 + Mockito / Testcontainers

**Spec:** `docs/superpowers/specs/2026-10-22-client-admission-policy-design.md`

## Global Constraints

- 目标版本:Java 21、Spring Boot 4.0.8、Spring Security / SAS 7.0.7(已核实 `RegisteredClient` 无 `getMetadata()`,策略走 `ClientSettings.setting()`)
- **不新增任何 Maven 依赖**;挑战票据、准入策略全部落在既有 Redis 与 MyBatis 体系内
- 包命名 `club.beenest.iam.*`;新增准入相关代码统一放 `club.beenest.iam.authz.admission`
- 注释一律中文,类级 Javadoc 必须说明"为什么",不写"是什么"
- Flyway 迁移命名 `V{version}__{description}.sql`,本轮迁移版本号固定为 **V1.2.1**
- 迁移只加列不改列;`iam_client` 新列的默认值为"不限制"(`allowed_login_types` 空串、`require_mfa` false)
- REST 统一返回 `Result<T>`,业务失败抛 `BusinessException(ErrorCode.X, "中文提示")`
- Redis 一律走 `RedisUtils`;不直接依赖 `StringRedisTemplate`
- **落 Redis / 经 Jackson 序列化的类型**,新增字段一律用**包装类型**(`Boolean` / `String`),不能用原始类型:存量 JSON 缺该字段时 Jackson 反序列化原始类型会抛异常
  - **豁免:`ClientModel` 用原始类型**。它是从不落 Redis、也不经 `ObjectMapper` 的纯内存领域模型(已核实:全仓库无任何 Jackson/Redis 路径触及它),且已有四个同类原始布尔分量(`reuseRefreshTokens` / `requirePkce` / `requireConsent` / `enabled`)。改用 `Boolean` 只会让全部构造点与消费点都多一层 `Boolean.TRUE.equals(...)` 噪音,不带来任何实际收益
  - 本轮会落 Redis 的类型:`IamSessionPayload`(新增 `Boolean mfaVerified`)、`IamUserPrincipal`(新增 `String loginType`)—— 这两个用包装类型
  - **豁免:`AdmissionChallenge` 用原始类型**(`boolean requireMfa` / `long createdAt`)。它是本轮**全新**的类型,Redis 里不存在"历史 JSON 缺字段"这回事;包装类型在这里买不到兼容性,只多出 `Boolean.TRUE.equals(...)` 的噪音。真正的兼容风险(将来删/改字段)包装类型也挡不住
- 后端单元测试放 `beenest-iam-server/src/test/java/...`,集成测试放 `beenest-iam-server/src/test/java/club/beenest/iam/integration/`
- 前端测试用 `node:test` + `node:assert/strict`,文件放 `beenest-iam-web/frontend/src/lib/`,命令 `npm test`
- 前端表单字段类型由 `SchemaForm.vue` 统一定义,新增类型必须同时改三处:`initialValue()`(初始化)、`read()`(取值)与模板;字段类型联合体在 `lib/types.ts` 的 `FieldType`,漏改会让 `vue-tsc --noEmit` 失败
- 每完成一个 Task 跑一次 `./mvnw -o -q test -pl beenest-iam-server -am`,全绿再进下一个 Task
- **聚焦单测的命令形式**:`./mvnw -o test -pl beenest-iam-server -am -Dtest=XxxTest -Dsurefire.failIfNoSpecifiedTests=false`。`-am` 会带上 `beenest-iam-common` 等没有同名测试的模块,不加这个开关时 surefire 因 "No tests matching pattern" 直接退出,看起来像测试失败

## Review Focus

以下五类是 spec 隐含、但任何单个 Task 的测试都不会自然覆盖、且最可能坑到人的行为。每个 Task 完成后都要对照本节确认。

1. **存量会话访问受限客户端**:升级前签发的会话没有 `loginType`(为 null)。此时访问有白名单的客户端**必须要求补验**,不能因为"读不到方式"就放行——那等于给所有存量会话开后门。
2. **白名单不含 PASSWORD 时登录页仍展示口令表单**:用户会经历"密码输对了却被弹走",比不展示更糟。登录页必须按白名单隐藏口令表单。
3. **补验成功后会话最大存续时长被重置**:补验走"升级"而非"新建"的核心原因。`createdTime` / `maxLifetimeSeconds` 必须原样保留,否则补验变成续期后门。
4. **挑战页回跳地址未做站内校验**:`returnTo` 最终要重定向浏览器,必须是本域授权端点路径,否则是开放重定向。
5. **SAML 走 HTTP-POST 绑定回跳时不带会话 Cookie**:`SameSite=Lax` 下跨站 POST 不送 Cookie。挑战上下文必须能经 RelayState 传递,不能只靠 sid 反查。

---

## File Structure

**新增(后端 `beenest-iam-authz`):**

| 文件 | 职责 |
|---|---|
| `authz/admission/LoginMethod.java` | 第一因素枚举(值即词表:PASSWORD/LDAP/SMS/WECHAT/OIDC/SAML) |
| `authz/admission/LoginMethodCatalog.java` | 域级可用方式的唯一计算入口;提供方类型 → 登录方式的映射 |
| `authz/admission/ClientAdmissionPolicy.java` | record:白名单 + 强制 MFA;判定方法 `admits(loginType)` / `requiresMfa()` |
| `authz/admission/AdmissionChallenge.java` | record:一次待处理的补验挑战 |
| `authz/admission/AdmissionChallengeService.java` | Redis 挑战票据的签发/读取/消费 |
| `authz/admission/ClientAdmissionFilter.java` | 协议链过滤器:授权端点前的准入判定与挑战发起 |
| `authz/admission/AdmissionDecision.java` | record:判定结果(放行 / 需方式挑战 / 需 MFA 挑战) |

**新增(后端 `beenest-iam-web`):**

| 文件 | 职责 |
|---|---|
| `web/controller/AdmissionChallengeController.java` | `/login-challenge` 页面渲染与提交 |
| `web/controller/AdmissionChallengePageController.java` | 挑战页所需的客户端上下文(可并入上一文件,优先并入) |

**新增(前端):**

| 文件 | 职责 |
|---|---|
| `frontend/src/lib/clientAdmission.ts` | 客户端准入字段的表单值 ↔ 请求体组装 |
| `frontend/src/lib/clientAdmission.test.ts` | 上者的单测 |

**新增(迁移):** `beenest-iam-persistence/src/main/resources/db/migration/V1.2.1__client_admission_policy.sql`

**修改:**

| 文件 | 改动 |
|---|---|
| `core/model/ClientModel.java` | 加 `allowedLoginTypes` / `requireMfa` 两个分量 |
| `persistence/entity/Client.java` | 加 `allowedLoginTypes` / `requireMfa` 两个字段 |
| `persistence/converter/ClientConverter.java` | 实体 ↔ 模型映射 |
| `persistence/resources/mapper/system/ClientMapper.xml` | `baseColumns` / `insert` / `updateById` |
| `authz/client/IamRegisteredClientRepository.java` | 把策略写进 `ClientSettings` |
| `authz/principal/IamUserPrincipal.java` | 加 `loginType` 分量 |
| `authz/principal/IamPrincipalFactory.java` | `build` 接受 loginType |
| `authz/authentication/IamAuthenticationProvider.java` | LDAP 分支打标 |
| `authz/web/IamFederationController.java` | 联邦按提供方类型打标;从 RelayState 取挑战 token |
| `authz/session/IamSessionPayload.java` | 加 `mfaVerified` 分量 |
| `authz/session/IamUserSessionService.java` | `upgradeAuthentication` |
| `authz/session/IamLoginSessionService.java` | `establish` 加重载;挑战感知 |
| `authz/mfa/MfaPendingService.java` | 票据携带 loginType;支持按会话签发 |
| `authz/config/IamSecurityConfiguration.java` | 协议链接入准入过滤器;业务链放行挑战页 |
| `authz/web/IamAuthenticationEntryPoint.java` | 重定向登录页时带 `client_id` |
| `authz/rest/AuthController.java` | `/idps` 按客户端过滤;`/login`、`/login-mfa` 的会话与事件带上实际登录方式 |
| `admin/dto/ClientCreateRequest.java` / `ClientUpdateRequest.java` / `ClientResponse.java` | 加两个字段 |
| `admin/converter/ClientDtoConverter.java` | 字段映射 |
| `admin/service/impl/ClientServiceImpl.java` | 保存时白名单 ∩ 域级可用方式校验 |
| `common/constant/IamRedisKeyConstants.java` | `ADMISSION_CHALLENGE_SID` / `ADMISSION_CHALLENGE_TOKEN` |
| `common/event/LoginEventEvent.java` | 三个新事件类型常量 |
| `web/controller/LoginPageController.java` | 按客户端过滤展示 |
| `web/frontend/src/components/SchemaForm.vue` | 新增 `multiselect` 字段类型 |
| `web/frontend/src/views/ClientsView.vue` | 客户端表单加两个字段 |

---

## Task 1: 迁移与持久层字段

**Files:**
- Create: `beenest-iam-persistence/src/main/resources/db/migration/V1.2.1__client_admission_policy.sql`
- Create: `beenest-iam-persistence/src/main/resources/db/rollback/U1.2.1__drop_client_admission_policy.sql`
- Modify: `beenest-iam-persistence/src/main/java/club/beenest/iam/persistence/entity/Client.java`
- Modify: `beenest-iam-core/src/main/java/club/beenest/iam/core/model/ClientModel.java`
- Modify: `beenest-iam-persistence/src/main/java/club/beenest/iam/persistence/converter/ClientConverter.java`
- Modify: `beenest-iam-persistence/src/main/resources/mapper/system/ClientMapper.xml`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/persistence/converter/ClientConverterTest.java`

**Interfaces:**
- Consumes: 无(本任务是链路起点)
- Produces:
  - `ClientModel.allowedLoginTypes()` → `Set<String>`
  - `ClientModel.requireMfa()` → `boolean`
  - `Client.getAllowedLoginTypes()` / `setAllowedLoginTypes(String)` → `String`(逗号分隔)
  - `Client.getRequireMfa()` / `setRequireMfa(Boolean)` → `Boolean`

- [ ] **Step 1: 写迁移脚本**

Create `beenest-iam-persistence/src/main/resources/db/migration/V1.2.1__client_admission_policy.sql`:

```sql
-- 客户端准入策略:允许的第一因素集合与是否强制 MFA。
-- allowed_login_types 为空串表示不限制(沿用域级可用方式);
-- require_mfa 与域级 iam_login_policy.force_mfa 叠加,任一为真即要求二步验证。
ALTER TABLE iam_client
    ADD COLUMN IF NOT EXISTS allowed_login_types VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE iam_client
    ADD COLUMN IF NOT EXISTS require_mfa BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN iam_client.allowed_login_types IS
    '客户端允许的第一因素集合(PASSWORD/LDAP/SMS/WECHAT/OIDC/SAML),逗号分隔;空 = 不限制';
COMMENT ON COLUMN iam_client.require_mfa IS
    '客户端强制 MFA;与域级 iam_login_policy.force_mfa 叠加,任一为真即要求二步验证';
```

- [ ] **Step 2: 实体加字段**

在 `Client.java` 的 `grantTypes` 字段之后、`serviceAccountUserId` 之前插入:

```java
    /**
     * 允许的第一因素集合(逗号分隔):PASSWORD/LDAP/SMS/WECHAT/OIDC/SAML。
     * <p>空串表示不限制,沿用域级可用方式;非空则只接受集合内的登录方式,
     * 会话认证方式不在集合内时转入补验流程。</p>
     */
    private String allowedLoginTypes;
    /**
     * 是否强制 MFA。
     * <p>与域级 iam_login_policy.force_mfa 叠加:任一为真即要求二步验证。
     * 域级强制在登录时生效,客户端级强制可以晚于登录发生(用户先进门户,
     * 再进高敏应用),故会话需记录 MFA 完成态。</p>
     */
    private Boolean requireMfa;
```

- [ ] **Step 3: 领域模型加分量**

`ClientModel.java` 的 record 分量列表中,在 `grantTypes` 之后、`serviceAccountUserId` 之前插入 `Set<String> allowedLoginTypes,` 与 `boolean requireMfa,`(顺序:grantTypes → allowedLoginTypes → requireMfa → serviceAccountUserId),并同步更新类级 Javadoc 的 `@param` 列表:

```java
 * @param allowedLoginTypes 允许的第一因素集合;空集合表示不限制
 * @param requireMfa       是否强制 MFA
```

`ClientModel` 是 record,新增分量会波及所有构造点。执行 `./mvnw -o -q -DskipTests compile` 找出全部编译错误并逐个补齐(测试代码里的构造点也要补,传 `Set.of()` 与 `false` 即可)。

- [ ] **Step 4: 转换器映射**

`ClientConverter.toModel` 中,在 `grantTypesOf(client.getGrantTypes()),` 之前插入:

```java
                // 准入白名单:空串表示不限制,与 DDL 默认值一致
                splitToSet(client.getAllowedLoginTypes()),
```

在 `client.getServiceAccountUserId()` 之前插入:

```java
                // opt-in 开关:null 按"未开启"处理,与 DDL 默认值 FALSE 一致。
                // 不能用 !Boolean.FALSE.equals(...):那会让 null 变成 true,
                // 凭空给客户端套上强制 MFA
                Boolean.TRUE.equals(client.getRequireMfa()),
```

- [ ] **Step 5: Mapper XML 三处**

`ClientMapper.xml`:

`baseColumns` 里,在 `grant_types, service_account_user_id,` 之间插入 `allowed_login_types, require_mfa,`:

```xml
        scopes, require_pkce, require_consent, access_token_ttl, refresh_token_ttl,
        grant_types, allowed_login_types, require_mfa, service_account_user_id, enabled, description,
```

`insert` 的列清单与 VALUES 清单各插入一对:

```xml
            <if test="allowedLoginTypes != null">allowed_login_types,</if>
            <if test="requireMfa != null">require_mfa,</if>
```

```xml
            <if test="allowedLoginTypes != null">#{allowedLoginTypes},</if>
            <if test="requireMfa != null">#{requireMfa},</if>
```

`updateById` 的 `<set>` 里插入:

```xml
            <if test="allowedLoginTypes != null">allowed_login_types = #{allowedLoginTypes},</if>
            <if test="requireMfa != null">require_mfa = #{requireMfa},</if>
```

> 注意:`updateById` 的 `<if test="x != null">` 语义是"null 表示不更新"。管理端要**清空白名单**时必须显式写空串而非 null,这一点在 Task 6 的 DTO 转换里处理。

- [ ] **Step 5.1: 写回滚脚本**

Create `beenest-iam-persistence/src/main/resources/db/rollback/U1.2.1__drop_client_admission_policy.sql`:

```sql
-- 回滚:移除客户端准入策略的两列。
-- 执行后客户端回到"不限制、不强制 MFA"的状态,与 V1.2.1 迁移前一致。
ALTER TABLE iam_client DROP COLUMN IF EXISTS require_mfa;
ALTER TABLE iam_client DROP COLUMN IF EXISTS allowed_login_types;
```

紧邻的 V1.2.0 有配套回滚(`U1.2.0__drop_organization_position.sql`),本迁移跟上同一约定。

- [ ] **Step 6: 写转换器单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/persistence/converter/ClientConverterTest.java`:

```java
package club.beenest.iam.persistence.converter;

import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.persistence.entity.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端转换器测试。
 * <p>准入白名单是准入判定的输入,转换环节一旦丢了字段,
 * 策略就会退化成"不限制"——而"不限制"看起来是正常状态,不会被发现。</p>
 */
class ClientConverterTest {

    private final ClientConverter converter = new ClientConverter();

    @Test
    @DisplayName("准入白名单与强制 MFA 从实体带到模型")
    void carriesAdmissionFields() {
        Client entity = new Client();
        entity.setAllowedLoginTypes("PASSWORD,SMS");
        entity.setRequireMfa(true);

        ClientModel model = converter.toModel(entity);

        assertEquals(java.util.Set.of("PASSWORD", "SMS"), model.allowedLoginTypes());
        assertTrue(model.requireMfa());
    }

    @Test
    @DisplayName("白名单为空表示不限制,不是空集合语义错误")
    void emptyWhitelistMeansUnrestricted() {
        Client entity = new Client();
        entity.setAllowedLoginTypes("");

        ClientModel model = converter.toModel(entity);

        assertTrue(model.allowedLoginTypes().isEmpty());
    }

    @Test
    @DisplayName("白名单字段未设置时按不限制处理")
    void unsetWhitelistMeansUnrestricted() {
        // DDL 是 NOT NULL DEFAULT '',走 SQL 时不可达;但"一旦可达就静默放开
        // 访问权限"是准入策略最不能出错的那个分支,值得钉死
        Client entity = new Client();

        ClientModel model = converter.toModel(entity);

        assertTrue(model.allowedLoginTypes().isEmpty());
    }
}
```

- [ ] **Step 7: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=ClientConverterTest`
Expected: PASS(2 tests)

- [ ] **Step 8: 全量编译**

Run: `./mvnw -o -q -DskipTests compile`
Expected: 无错误(若 Task 3 之前有其它 `ClientModel` 构造点,在此一并补齐)

---

## Task 2: LoginMethod 词表与 LoginMethodCatalog

**Files:**
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/LoginMethod.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/LoginMethodCatalog.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/LoginMethodCatalogTest.java`

**Interfaces:**
- Consumes: `IamSecurityPolicyManager.getLoginPolicy(Long, String)` → `LoginPolicy`;`IamLdapConfigStore.getConfig(Long, String)` → `LdapConfig`;`IdentityProviderStore.listByRealm(Long)` → `List<IdentityProviderModel>`;**`SmsChannelRouter.resolve()` → `Optional<SmsChannel>`**(短信可用性的唯一权威判定,见 Step 4.1)
- Modify: `beenest-iam-core/src/main/java/club/beenest/iam/core/spi/sms/SmsChannel.java`(新增 `isConfigured()`)
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/sms/AliyunSmsChannel.java`(实现 `isConfigured()`)
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/sms/SmsChannelRouter.java`(`resolve()` 按配置完整性过滤)
- Produces:
  - `LoginMethod` 枚举常量:`PASSWORD` / `LDAP` / `SMS` / `WECHAT` / `OIDC` / `SAML`,每项带 `code` 字段(大写,与存储一致)
  - `LoginMethod.fromCode(String)` → `LoginMethod`(非法值返回 null)
  - `LoginMethodCatalog.availableInRealm(Long realmId, String realmKey)` → `Set<LoginMethod>`
  - `LoginMethodCatalog.toLoginMethod(String providerId)` → `LoginMethod`(联邦提供方类型 → 登录方式;未知返回 null)

- [ ] **Step 1: 写失败测试**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/LoginMethodCatalogTest.java`:

```java
package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.sms.SmsChannelRouter;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.sms.SmsChannel;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 域级可用方式计算测试。
 * <p>这张表是"管理端校验"与"运行时判定"的共同口径:两处若不一致,
 * 会出现"配置时提示可用,运行时却登录不进去"的错配。</p>
 */
class LoginMethodCatalogTest {

    private static final Long REALM_ID = 1L;
    private static final String REALM_KEY = "default";

    private final IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
    private final IamLdapConfigStore ldapConfigStore = mock(IamLdapConfigStore.class);
    private final IdentityProviderStore idpStore = mock(IdentityProviderStore.class);
    private final SmsChannelRouter channelRouter = mock(SmsChannelRouter.class);
    private final LoginMethodCatalog catalog = new LoginMethodCatalog(
            policyManager, ldapConfigStore, idpStore, channelRouter);

    @BeforeEach
    void setUp() {
        LoginPolicy policy = new LoginPolicy();
        policy.setSmsLoginEnabled(false);
        when(policyManager.getLoginPolicy(REALM_ID, REALM_KEY)).thenReturn(policy);
        when(ldapConfigStore.getConfig(REALM_ID, REALM_KEY)).thenReturn(null);
        when(idpStore.listByRealm(REALM_ID)).thenReturn(List.of());
        // 短信通道已就绪:本用例聚焦域级开关,通道可用性单独验证
        when(channelRouter.resolve()).thenReturn(Optional.of(mock(SmsChannel.class)));
    }

    @Test
    @DisplayName("口令恒可用")
    void passwordAlwaysAvailable() {
        assertTrue(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.PASSWORD));
    }

    @Test
    @DisplayName("域级开关关闭时短信不可用,即使通道已就绪")
    void smsDisabled() {
        // setUp 即"通道已就绪 + 域级开关为假",所以本用例同时盖住了这两个条件
        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.SMS));
    }

    @Test
    @DisplayName("域级开启短信但通道未配置,短信仍不可用")
    void smsEnabledButChannelMissing() {
        LoginPolicy policy = new LoginPolicy();
        policy.setSmsLoginEnabled(true);
        when(policyManager.getLoginPolicy(REALM_ID, REALM_KEY)).thenReturn(policy);

        SmsChannelRouter noChannelRouter = mock(SmsChannelRouter.class);
        when(noChannelRouter.resolve()).thenReturn(Optional.empty());
        LoginMethodCatalog noChannel = new LoginMethodCatalog(
                policyManager, ldapConfigStore, idpStore, noChannelRouter);

        assertFalse(noChannel.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.SMS));
    }

    @Test
    @DisplayName("域级启用 LDAP 时 LDAP 可用")
    void ldapEnabled() {
        LdapConfig config = new LdapConfig();
        config.setEnabled(true);
        when(ldapConfigStore.getConfig(REALM_ID, REALM_KEY)).thenReturn(config);

        assertTrue(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.LDAP));
    }

    @Test
    @DisplayName("注册了微信提供方则微信可用")
    void wechatAvailable() {
        when(idpStore.listByRealm(REALM_ID)).thenReturn(List.of(
                new IdentityProviderModel(1L, REALM_ID, "wechat-mini", "wx", "微信", true, Map.of())));

        assertTrue(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WECHAT));
    }

    @Test
    @DisplayName("提供方类型映射到登录方式")
    void mapsProviderType() {
        assertEquals(LoginMethod.WECHAT, LoginMethodCatalog.toLoginMethod("wechat-mini"));
        assertEquals(LoginMethod.WECHAT, LoginMethodCatalog.toLoginMethod("wechat-web"));
        assertEquals(LoginMethod.OIDC, LoginMethodCatalog.toLoginMethod("oidc"));
        assertEquals(LoginMethod.SAML, LoginMethodCatalog.toLoginMethod("saml"));
    }

    @Test
    @DisplayName("无法识别的提供方类型不产出登录方式")
    void unknownProviderTypeMapsToNull() {
        assertNull(LoginMethodCatalog.toLoginMethod("unknown-idp"));
    }

    @Test
    @DisplayName("提供方未启用时不算可用")
    void disabledProviderNotAvailable() {
        when(idpStore.listByRealm(REALM_ID)).thenReturn(List.of(
                new IdentityProviderModel(1L, REALM_ID, "wechat-mini", "wx", "微信", false, Map.of())));

        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.WECHAT));
    }

    @Test
    @DisplayName("LDAP 配了但未启用时不算可用")
    void disabledLdapNotAvailable() {
        LdapConfig config = new LdapConfig();
        config.setEnabled(false);
        when(ldapConfigStore.getConfig(REALM_ID, REALM_KEY)).thenReturn(config);

        assertFalse(catalog.availableInRealm(REALM_ID, REALM_KEY).contains(LoginMethod.LDAP));
    }

    @Test
    @DisplayName("存储码解析:空白与非法值一律返回 null")
    void fromCodeRejectsBlankAndUnknown() {
        assertNull(LoginMethod.fromCode(null));
        assertNull(LoginMethod.fromCode(""));
        assertNull(LoginMethod.fromCode("   "));
        assertNull(LoginMethod.fromCode("TOTP"));
    }

    @Test
    @DisplayName("存储码解析:忽略大小写与首尾空白")
    void fromCodeTrimsAndIgnoresCase() {
        assertEquals(LoginMethod.SMS, LoginMethod.fromCode("  sms  "));
        assertEquals(LoginMethod.WECHAT, LoginMethod.fromCode("wechat"));
    }

    @Test
    @DisplayName("存储码即词表值")
    void codeIsTheCanonicalValue() {
        assertEquals("PASSWORD", LoginMethod.PASSWORD.code());
        assertEquals("WECHAT", LoginMethod.WECHAT.code());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=LoginMethodCatalogTest`
Expected: FAIL —— 编译错误 "找不到符号: 类 LoginMethodCatalog"

- [ ] **Step 3: 写 LoginMethod 枚举**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/LoginMethod.java`:

```java
package club.beenest.iam.authz.admission;

/**
 * 第一因素(独立登录方式)词表。
 * <p>值即存储形态:直接落库、落登录事件、落会话主体,三处同源,
 * 免去"枚举名与存储码对不上"这一类只在生产数据里才暴露的错配。</p>
 * <p>TOTP 不在此列:它是第二因素,与"用什么方式证明身份"是两回事。
 * 把它混进第一因素会让"强制 MFA"与"允许的登录方式"两个开关互相污染。</p>
 */
public enum LoginMethod {

    /** 本地口令凭证 */
    PASSWORD("PASSWORD"),
    /** 目录(LDAP/AD)兜底认证 */
    LDAP("LDAP"),
    /** 手机号 + 短信验证码 */
    SMS("SMS"),
    /** 微信(小程序登录 / 开放平台扫码) */
    WECHAT("WECHAT"),
    /** 外部 OIDC 提供方 */
    OIDC("OIDC"),
    /** 外部 SAML IdP(平台作 SP) */
    SAML("SAML");

    /** 存储码 */
    private final String code;

    LoginMethod(String code) {
        this.code = code;
    }

    /** 存储码。 */
    public String code() {
        return code;
    }

    /** 按存储码解析;非法值返回 null(调用方自行决定是拒绝还是忽略)。 */
    public static LoginMethod fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (LoginMethod method : values()) {
            if (method.code.equalsIgnoreCase(code.trim())) {
                return method;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 写 LoginMethodCatalog**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/LoginMethodCatalog.java`:

```java
package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.sms.SmsChannelRouter;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.sms.SmsChannel;
import club.beenest.iam.persistence.entity.LdapConfig;
import club.beenest.iam.persistence.entity.LoginPolicy;
import club.beenest.iam.persistence.manager.IamLdapConfigStore;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 域级可用登录方式的计算入口。
 * <p>"某方式在本域能不能用"是管理端配置校验、登录页展示过滤、
 * 挑战页渲染、运行时准入判定四处共用的同一份口径。四处若各写一份,
 * 就会出现"配置时提示可用、运行时却登不进去"的错配,而这类错配
 * 只有在客户真正接入时才暴露。</p>
 */
@Component
public class LoginMethodCatalog {

    private final IamSecurityPolicyManager securityPolicyManager;
    private final IamLdapConfigStore ldapConfigStore;
    private final IdentityProviderStore identityProviderStore;
    private final SmsChannelRouter smsChannelRouter;

    /**
     * @param securityPolicyManager 读域级登录策略(短信开关)
     * @param ldapConfigStore       读域级目录配置(是否存在且启用)
     * @param identityProviderStore 读域内已注册的联邦提供方
     * @param smsChannelRouter      判定短信通道是否真的可用(不是"配了名字"而是"配全了")
     */
    public LoginMethodCatalog(IamSecurityPolicyManager securityPolicyManager,
                              IamLdapConfigStore ldapConfigStore,
                              IdentityProviderStore identityProviderStore,
                              SmsChannelRouter smsChannelRouter) {
        this.securityPolicyManager = securityPolicyManager;
        this.ldapConfigStore = ldapConfigStore;
        this.identityProviderStore = identityProviderStore;
        this.smsChannelRouter = smsChannelRouter;
    }

    /**
     * 本域当前可用的第一因素集合。
     *
     * @param realmId  身份域主键
     * @param realmKey 身份域标识
     */
    public Set<LoginMethod> availableInRealm(Long realmId, String realmKey) {
        Set<LoginMethod> available = EnumSet.of(LoginMethod.PASSWORD);
        // 短信:域级开关打开且通道真的可用,两者缺一都发不出验证码。
        // "通道可用"必须问 SmsChannelRouter,不能自己去看 iam.sms.channel 是否非空:
        // 路由是发送路径的唯一入口,问它才能保证"展示可用"与"真能发出去"永远一致。
        // 自己拼一个偏宽松的判定,就会做出一个点了必然报错的登录 Tab
        LoginPolicy policy = securityPolicyManager.getLoginPolicy(realmId, realmKey);
        if (policy != null && Boolean.TRUE.equals(policy.getSmsLoginEnabled())
                && smsChannelRouter.resolve().isPresent()) {
            available.add(LoginMethod.SMS);
        }
        // LDAP:域级配了目录才算数
        LdapConfig ldapConfig = ldapConfigStore.getConfig(realmId, realmKey);
        if (ldapConfig != null && Boolean.TRUE.equals(ldapConfig.getEnabled())) {
            available.add(LoginMethod.LDAP);
        }
        // 联邦:按域内已注册的提供方类型推导
        for (IdentityProviderModel idp : identityProviderStore.listByRealm(realmId)) {
            if (!idp.enabled()) {
                continue;
            }
            LoginMethod method = toLoginMethod(idp.providerId());
            if (method != null) {
                available.add(method);
            }
        }
        return available;
    }

    /**
     * 联邦提供方类型 → 登录方式。
     * <p>微信有两个形态(小程序 / 开放平台扫码),但对准入而言都是"微信登录",
     * 归并成一个方式,否则管理员要维护两套几乎一样的白名单。</p>
     */
    public static LoginMethod toLoginMethod(String providerId) {
        if (providerId == null) {
            return null;
        }
        // 显式指定 Locale.ROOT:默认 locale 下(如土耳其语)"OIDC".toLowerCase()
        // 会得到无点 ı,静默落到 default -> null,表现为"提供方注册了却不出现在可用方式里"
        return switch (providerId.trim().toLowerCase(Locale.ROOT)) {
            case "wechat-mini", "wechat-web" -> LoginMethod.WECHAT;
            case "oidc" -> LoginMethod.OIDC;
            case "saml" -> LoginMethod.SAML;
            default -> null;
        };
    }
}
```

短信通道可用性一律通过 `SmsChannelRouter.resolve()` 判定——它是发送路径的唯一入口,见 Step 4.1。

- [ ] **Step 4.1: 补齐短信通道的"配置完整性"判定**

`SmsChannelRouter` 的类注释写着"未配置或配置不完整时返回空,由调用方明确报'功能未启用'",但实现只比对了通道名——配置不完整的通道会被当成可用。准入策略要以它作为"短信能否用"的权威判定(Task 6 的保存校验、Task 14 的登录页 Tab 展示都读它),这个缺口会把"必然失败的登录 Tab"暴露给用户。

`beenest-iam-core/src/main/java/club/beenest/iam/core/spi/sms/SmsChannel.java` 追加:

```java
    /**
     * 通道配置是否完整到可以发送。
     * <p>路由器据此过滤:配置不完整的通道不算"可用"。默认返回 true 是为了
     * 不破坏既有实现与测试替身;真实通道应覆写为"只校验自己 send() 真正读取的那几项"。</p>
     */
    default boolean isConfigured() {
        return true;
    }
```

`beenest-iam-authz/src/main/java/club/beenest/iam/authz/sms/AliyunSmsChannel.java` 追加:

```java
    @Override
    public boolean isConfigured() {
        // 委托给配置类自己的判定:同一件事只留一个实现,否则两处迟早漂移。
        // isAliyunConfigured() 检查的正是 send() 读取的那四项,此前是死代码,
        // 恰好由本改造接上
        return properties.isAliyunConfigured();
    }
```

`IamSmsProperties.isAliyunConfigured()` 保持不动——"配置项是否配全"本来就是配置类该回答的问题。

`beenest-iam-authz/src/main/java/club/beenest/iam/authz/sms/SmsChannelRouter.java` 的 `resolve()` 加一道过滤:

```java
        return channels.stream()
                .filter(channel -> channel.channelId().equalsIgnoreCase(configured.trim()))
                // 配置不完整不算可用:让"功能未启用"在登录页就体现出来,
                // 而不是等用户点了发送才失败
                .filter(SmsChannel::isConfigured)
                .findFirst();
```

- [ ] **Step 4.2: 给路由器补测试**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/sms/SmsChannelRouterTest.java`:

```java
package club.beenest.iam.authz.sms;

import club.beenest.iam.authz.config.IamSmsProperties;
import club.beenest.iam.core.spi.sms.SmsChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 短信通道路由测试。
 * <p>"通道可用"是准入链路上的一个事实判断:管理端据此允许把短信写进
 * 客户端白名单,登录页据此决定展不展示短信入口。判定偏宽松,就会做出
 * 一个点了必然报错的入口——那比不展示更糟。</p>
 */
class SmsChannelRouterTest {

    @Test
    @DisplayName("通道名未配置时不可用")
    void notConfigured() {
        IamSmsProperties properties = new IamSmsProperties();
        SmsChannelRouter router = new SmsChannelRouter(List.of(channel("aliyun", true)), properties);

        assertTrue(router.resolve().isEmpty());
    }

    @Test
    @DisplayName("通道名匹配但配置不完整时不可用")
    void configuredButIncomplete() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        SmsChannelRouter router = new SmsChannelRouter(List.of(channel("aliyun", false)), properties);

        assertTrue(router.resolve().isEmpty());
    }

    @Test
    @DisplayName("通道名匹配且配置完整时可用")
    void configuredAndComplete() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        SmsChannelRouter router = new SmsChannelRouter(List.of(channel("aliyun", true)), properties);

        assertFalse(router.resolve().isEmpty());
    }

    @Test
    @DisplayName("阿里云通道:四项配置缺一即视为未就绪")
    void aliyunIncompleteConfig() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        properties.setAccessKeyId("ak");
        properties.setAccessKeySecret("sk");
        properties.setSignName("签名");
        // templateCode 故意留空
        SmsChannel channel = new AliyunSmsChannel(properties, new ObjectMapper());

        assertFalse(channel.isConfigured());
    }

    @Test
    @DisplayName("阿里云通道:四项配置齐全视为就绪")
    void aliyunCompleteConfig() {
        IamSmsProperties properties = new IamSmsProperties();
        properties.setChannel("aliyun");
        properties.setAccessKeyId("ak");
        properties.setAccessKeySecret("sk");
        properties.setSignName("签名");
        properties.setTemplateCode("SMS_123");
        SmsChannel channel = new AliyunSmsChannel(properties, new ObjectMapper());

        assertTrue(channel.isConfigured());
    }

    /** 构造一个只关心"配置是否完整"的通道替身。 */
    private static SmsChannel channel(String id, boolean configured) {
        SmsChannel channel = mock(SmsChannel.class);
        when(channel.channelId()).thenReturn(id);
        when(channel.isConfigured()).thenReturn(configured);
        return channel;
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -o test -pl beenest-iam-server -am -Dtest='LoginMethodCatalogTest,SmsChannelRouterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS(两个测试类全绿;用例数随覆盖补强会增长,以实际为准)

---

## Task 3: ClientAdmissionPolicy 与策略随 RegisteredClient 下发

**Files:**
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/ClientAdmissionPolicy.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/client/IamRegisteredClientRepository.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/ClientAdmissionPolicyTest.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/client/IamRegisteredClientRepositoryTest.java`

**Interfaces:**
- Consumes: `ClientModel.allowedLoginTypes()` / `requireMfa()`(Task 1)
- Produces:
  - `ClientAdmissionPolicy(Set<LoginMethod> allowedMethods, boolean requireMfa)`
  - `ClientAdmissionPolicy.unrestricted()` → 白名单为空、不强制 MFA 的策略
  - `ClientAdmissionPolicy.admits(String loginType)` → `boolean`
  - `ClientAdmissionPolicy.requiresMfa()` → `boolean`
  - `ClientAdmissionPolicy.allowedMethods()` → `Set<LoginMethod>`
  - `IamRegisteredClientRepository.SETTING_ALLOWED_LOGIN_TYPES` → `String` 常量(`"iam.allowed_login_types"`)
  - `IamRegisteredClientRepository.SETTING_REQUIRE_MFA` → `String` 常量(`"iam.require_mfa"`)
  - `IamRegisteredClientRepository.policyOf(RegisteredClient)` → `ClientAdmissionPolicy`(静态读出)

- [ ] **Step 1: 写 ClientAdmissionPolicy 单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/ClientAdmissionPolicyTest.java`:

```java
package club.beenest.iam.authz.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端准入策略判定测试。
 * <p>白名单为空表示"不限制",与"白名单为空集合"是两件事:
 * 前者放行一切,后者放行无物。判反了就是全站拒绝登录。</p>
 */
class ClientAdmissionPolicyTest {

    @Test
    @DisplayName("白名单为空表示不限制,任何方式都放行")
    void unrestrictedAdmitsEverything() {
        ClientAdmissionPolicy policy = ClientAdmissionPolicy.unrestricted();

        assertTrue(policy.admits("PASSWORD"));
        assertTrue(policy.admits("SMS"));
        assertTrue(policy.admits(null));
    }

    @Test
    @DisplayName("白名单命中则放行")
    void admitsListedMethod() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(
                Set.of(LoginMethod.SMS, LoginMethod.PASSWORD), false);

        assertTrue(policy.admits("SMS"));
        assertTrue(policy.admits("PASSWORD"));
        assertFalse(policy.admits("WECHAT"));
    }

    @Test
    @DisplayName("登录方式未知时按不满足处理")
    void unknownMethodNotAdmitted() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(
                Set.of(LoginMethod.PASSWORD), false);

        assertFalse(policy.admits(null));
        assertFalse(policy.admits("UNKNOWN"));
    }

    @Test
    @DisplayName("配置损坏态拒绝一切,不降级为不限制")
    void denyAllAdmitsNothing() {
        // "空集合 = 不限制"与"拒绝一切"是相反的两件事,不能混
        ClientAdmissionPolicy policy = ClientAdmissionPolicy.denyAllPolicy();

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
        assertFalse(policy.admits("SMS"));
        assertFalse(policy.admits(null));
    }

    @Test
    @DisplayName("denyAll 策略下白名单被归一为空,不留自相矛盾的配置")
    void denyAllNormalizesNonEmptyWhitelist() {
        // denyAll 之下白名单没有意义。若构造时不归一,就会出现"既拒绝一切、
        // 又存着一份白名单"的自相矛盾状态;将来有人按白名单反推配置会读出错觉
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(
                Set.of(LoginMethod.SMS), false, true);

        assertTrue(policy.denyAll());
        assertTrue(policy.allowedMethodCodes().isEmpty());
        assertFalse(policy.admits("SMS"));
    }
}
```

- [ ] **Step 2: 写 ClientAdmissionPolicy**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/ClientAdmissionPolicy.java`:

```java
package club.beenest.iam.authz.admission;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端准入策略。
 * <p>只描述"这个客户端接受什么",不描述"怎么认证"——认证方式由域级
 * 决定,准入策略在域级之上做收窄。两层分开的意义在于:换一家 IdP
 * 不会牵动每个客户端的白名单,改一个客户端的策略也不会动到全域。</p>
 *
 * @param allowedMethods 允许的第一因素集合;**空集合表示不限制**(沿用域级可用方式)
 * @param requireMfa     是否强制二步验证
 * @param denyAll        **配置损坏态**:一律拒绝。与"空集合 = 不限制"是相反的两件事
 */
public record ClientAdmissionPolicy(Set<LoginMethod> allowedMethods, boolean requireMfa, boolean denyAll) {

    /** 构造时冻结集合,避免调用方事后改动影响判定结果。 */
    public ClientAdmissionPolicy {
        allowedMethods = allowedMethods == null || allowedMethods.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(allowedMethods));
        // denyAll 之下白名单没有意义:归一为空,免得出现自相矛盾的策略
        if (denyAll) {
            allowedMethods = Set.of();
        }
    }

    /** 常规两参构造:不涉及"配置损坏"。 */
    public ClientAdmissionPolicy(Set<LoginMethod> allowedMethods, boolean requireMfa) {
        this(allowedMethods, requireMfa, false);
    }

    /** 不限制任何方式、也不强制 MFA 的策略(存量客户端的默认)。 */
    public static ClientAdmissionPolicy unrestricted() {
        return new ClientAdmissionPolicy(Set.of(), false, false);
    }

    /**
     * 策略配置损坏:一律拒绝。
     * <p>存了白名单却一个方式都解析不出来时用它,**绝不能降级成 unrestricted**——
     * 那等于把一条本该生效的限制静默解除。最现实的触发不是管理员手滑,
     * 而是将来从 {@link LoginMethod} 里移除或改名某个值:彼时所有限定该方式的
     * 客户端会在无人察觉的情况下变成不设限。</p>
     * <p>方法名带 {@code Policy} 后缀是必须的:record 分量 {@code denyAll}
     * 会自动生成无参访问器 {@code boolean denyAll()},若工厂也叫 {@code denyAll()},
     * 两者同名同参、仅返回类型不同——Java 不允许按返回类型重载,直接编译不过。</p>
     */
    public static ClientAdmissionPolicy denyAllPolicy() {
        return new ClientAdmissionPolicy(Set.of(), false, true);
    }

    /**
     * 会话的认证方式是否被本策略接受。
     * <p>三条规则,顺序不能换:</p>
     * <ol>
     *   <li><b>denyAll</b> → 一律拒绝。这是"配置损坏"态,优先级最高:
     *       存了白名单却解析不出任何方式时,绝不能落回"不限制"</li>
     *   <li>白名单为空 → 全部接受(未配置限制)</li>
     *   <li>白名单非空 → 只接受集合内的方式;登录方式未知(存量会话没有该字段)
     *       按不满足处理——无法证明满足要求时要求重新证明,而不是放行</li>
     * </ol>
     */
    public boolean admits(String loginType) {
        if (denyAll) {
            return false;
        }
        if (allowedMethods.isEmpty()) {
            return true;
        }
        LoginMethod method = LoginMethod.fromCode(loginType);
        return method != null && allowedMethods.contains(method);
    }

    /**
     * 白名单的存储码集合(供管理端回显与前端选项比对)。
     * <p>返回码而非枚举:控制台表单与数据库存的都是码。</p>
     */
    public Set<String> allowedMethodCodes() {
        Set<String> codes = new LinkedHashSet<>();
        allowedMethods.forEach(method -> codes.add(method.code()));
        return Collections.unmodifiableSet(codes);
    }

    /** 是否强制二步验证。 */
    public boolean requiresMfa() {
        return requireMfa;
    }
}
```

- [ ] **Step 3: 写 RegisteredClient 策略下发测试**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/client/IamRegisteredClientRepositoryTest.java`:

```java
package club.beenest.iam.authz.client;

import club.beenest.iam.authz.admission.ClientAdmissionPolicy;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户端仓储的策略下发测试。
 * <p>准入策略靠 RegisteredClient 带到协议链,绕开这一步的策略读不到,
 * 判定会退化成"不限制"——不限制是能跑通的状态,不会被测试发现。</p>
 */
class IamRegisteredClientRepositoryTest {

    private final ClientProvider clientProvider = mock(ClientProvider.class);
    private final RealmProvider realmProvider = mock(RealmProvider.class);
    private final IamRegisteredClientRepository repository =
            new IamRegisteredClientRepository(clientProvider, realmProvider);

    @BeforeEach
    void setUp() {
        // RealmModel 有 11 个分量:末尾的 SubjectMode 与 theme 传 null 表示"用默认"
        RealmModel realm = new RealmModel(1L, "default", "默认域", true, false,
                300L, 1800L, 1800L, 36000L, null, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
    }

    @Test
    @DisplayName("准入策略随 RegisteredClient 下发")
    void carriesAdmissionPolicy() {
        when(clientProvider.findByClientKey(1L, "app")).thenReturn(Optional.of(client(Set.of("SMS"))));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertEquals(Set.of(LoginMethod.SMS.code()), policy.allowedMethodCodes());
        assertTrue(policy.requiresMfa());
    }

    @Test
    @DisplayName("白名单存了值却一个都解析不出来时按拒绝一切处理,绝不放开")
    void unparseableWhitelistDeniesAll() {
        // 最现实的触发不是管理员手滑,而是将来从 LoginMethod 移除/改名某个值——
        // 彼时所有限定该方式的客户端都会走到这里。若落回"不限制",
        // 限制就被静默解除了,而且看起来一切正常
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(client(Set.of("TOTP"))));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
        assertFalse(policy.admits("SMS"));
    }

    @Test
    @DisplayName("白名单留空表示不限制,不是拒绝一切")
    void blankWhitelistMeansUnrestricted() {
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(client(Set.of())));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertFalse(policy.denyAll());
        assertTrue(policy.admits("PASSWORD"));
    }

    @Test
    @DisplayName("客户端对象为空时按拒绝一切处理,绝不落回不限制")
    void nullRegisteredClientDeniesAll() {
        // 理论不可达,但一旦发生,"不知道策略"同样只能按拒绝处理。
        // 这个用例钉住的方向比它现在不可达更重要:将来有人把这里改回
        // unrestricted(),编译通过、测试全绿,闸门就被静默拆掉了
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(null);

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
        assertFalse(policy.admits(null));
    }

    @Test
    @DisplayName("ClientSettings 为空时按拒绝一切处理,绝不落回不限制")
    void nullClientSettingsDeniesAll() {
        // SAS 的 Builder 总会给默认 ClientSettings,正常路径造不出 null;
        // 用替身直接打这个分支,同样是为了把"失败方向"钉死在测试里
        RegisteredClient registered = mock(RegisteredClient.class);
        when(registered.getClientSettings()).thenReturn(null);

        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertTrue(policy.denyAll());
        assertFalse(policy.admits("PASSWORD"));
    }

    @Test
    @DisplayName("白名单混排时保留可识别的,只有全部认不出才算配置损坏")
    void partiallyUnparseableWhitelistKeepsRecognized() {
        // TOTP 是第二因素,不在 LoginMethod 词表里,解析时被忽略——
        // 但 SMS 认得出,说明配置大体有效,不能因此把整条限制也解除
        when(clientProvider.findByClientKey(1L, "app"))
                .thenReturn(Optional.of(client(Set.of("SMS", "TOTP"))));

        RegisteredClient registered = repository.findByClientId("app");
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registered);

        assertFalse(policy.denyAll());
        assertEquals(Set.of(LoginMethod.SMS.code()), policy.allowedMethodCodes());
        assertTrue(policy.admits("SMS"));
        assertFalse(policy.admits("TOTP"));
    }

    /**
     * 构造带准入策略的客户端模型。
     * <p>ClientModel 是 record,分量顺序为(Task 1 已落地,勿改):
     * id, realmId, clientKey, clientName, clientType, secret, redirectUris,
     * postLogoutRedirectUris, backchannelLogoutUri, reuseRefreshTokens, scopes,
     * requirePkce, requireConsent, enabled, subjectMode, accessTokenTtl,
     * refreshTokenTtl, grantTypes, allowedLoginTypes, requireMfa, serviceAccountUserId</p>
     */
    private static ClientModel client(Set<String> allowedLoginTypes) {
        return new ClientModel(
                1L, 1L, "app", "应用", ClientModel.ClientType.PUBLIC, null,
                Set.of("https://app.example.com/cb"), Set.of(), null, true,
                Set.of("openid"), true, false, true, null, null, null,
                Set.of("authorization_code"), allowedLoginTypes, true, null);
    }
}
```

- [ ] **Step 4: 在仓储里下发并读出策略**

`IamRegisteredClientRepository.java`:

新增 import:

```java
import club.beenest.iam.authz.admission.ClientAdmissionPolicy;
import club.beenest.iam.authz.admission.LoginMethod;
```

新增常量与静态读取方法:

```java
    /** 准入白名单在 ClientSettings 中的键名。 */
    public static final String SETTING_ALLOWED_LOGIN_TYPES = "iam.allowed_login_types";
    /** 强制 MFA 在 ClientSettings 中的键名。 */
    public static final String SETTING_REQUIRE_MFA = "iam.require_mfa";

    /**
     * 从 RegisteredClient 读出准入策略。
     * <p>策略随客户端对象下发,判定路径因此不必再查一次库。</p>
     * <p><b>解析不出来时向"拒绝"方向失败,绝不向"放行"方向失败。</b>
     * 这是一道准入闸门:读不懂配置时唯一安全的结论是"不满足",
     * 而不是"没读到限制所以不限制"。后者会把一条本该生效的限制静默解除,
     * 且因为结果看起来一切正常,不会有任何告警。</p>
     */
    public static ClientAdmissionPolicy policyOf(RegisteredClient registeredClient) {
        if (registeredClient == null || registeredClient.getClientSettings() == null) {
            // 理论上不可达(SAS 的 Builder 总会给出默认 ClientSettings;
            // 调用方也都在取到客户端之后才调用本方法)。真发生了说明调用姿势有误,
            // 此时"不知道策略"同样只能按拒绝处理
            log.warn("客户端对象或其 ClientSettings 为空,准入策略按拒绝一切处理");
            return ClientAdmissionPolicy.denyAllPolicy();
        }
        String types = registeredClient.getClientSettings().getSetting(SETTING_ALLOWED_LOGIN_TYPES);
        Set<LoginMethod> methods = new LinkedHashSet<>();
        if (types != null && !types.isBlank()) {
            for (String code : types.split(",")) {
                LoginMethod method = LoginMethod.fromCode(code);
                if (method != null) {
                    methods.add(method);
                } else if (!code.isBlank()) {
                    log.warn("客户端 {} 的准入白名单含无法识别的方式:{},该条已忽略",
                            registeredClient.getClientId(), code);
                }
            }
            if (methods.isEmpty()) {
                // 存了白名单却一个都认不出来:配置损坏,按拒绝一切处理。
                // 这里若落回 unrestricted,就会把限制静默解除——最现实的触发是
                // 将来从 LoginMethod 移除/改名某个值,彼时无人察觉
                log.error("客户端 {} 的准入白名单无法解析,已按拒绝一切处理:{}",
                        registeredClient.getClientId(), types);
                return ClientAdmissionPolicy.denyAllPolicy();
            }
        }
        Boolean requireMfa = registeredClient.getClientSettings().getSetting(SETTING_REQUIRE_MFA);
        return new ClientAdmissionPolicy(methods, Boolean.TRUE.equals(requireMfa));
    }
```

在 `toRegisteredClient` 的 `.clientSettings(...)` 里追加两行:

```java
                        // 准入策略随客户端下发:协议链判定时不必再查库
                        .setting(SETTING_ALLOWED_LOGIN_TYPES, String.join(",", model.allowedLoginTypes()))
                        .setting(SETTING_REQUIRE_MFA, model.requireMfa())
```

> `ClientSettings.Builder.setting(String, Object)` 来自 `AbstractSettings.AbstractBuilder`,写入的值必须可序列化;`String` 与 `Boolean` 满足。`getSetting` 的读取类型必须与写入类型一致,故 `SETTING_REQUIRE_MFA` 写 `boolean` 会被自动装箱为 `Boolean`,读取时用 `Boolean` 即可。

- [ ] **Step 5: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=ClientAdmissionPolicyTest,IamRegisteredClientRepositoryTest`
Expected: PASS

---

## Task 4: loginType 打标与主体承载

**Files:**
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/principal/IamUserPrincipal.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/principal/IamPrincipalFactory.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/authentication/IamAuthenticationProvider.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mfa/MfaPendingService.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/service/AuthService.java` + `service/impl/AuthServiceImpl.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/IamFederationController.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/principal/IamUserPrincipalTest.java`

**Interfaces:**
- Consumes: `LoginMethod`(Task 2)
- Produces:
  - `IamUserPrincipal.loginType()` → `String`(可为 null,表示未知)
  - `IamPrincipalFactory.build(RealmModel, UserModel, String loginType)` → `IamUserPrincipal`(**只保留三参重载**;两参重载删除,理由见下)
  - `MfaPendingService.issue(String realmKey, Long userId, String loginType)` → `String`(由现有的 2 参签名扩为 3 参;两个既有调用点 `IamAuthenticationFailureHandler` 与 `AuthController` 同步更新)
  - `MfaPendingService.consume(String realmKey, String token)` → `Long`(语义不变:取用户主键)
  - `MfaPendingService.consumeTicket(String realmKey, String token)` → `MfaPending`(新增;票据值三段 `userId:sid:loginType`,登录流程 sid 段为空)
  - `MfaRequiredException.loginType()` → `String`(新增字段,供失败处理器签票据时带上)

> **两参 `build(realm, user)` 必须删掉,不能留成"默认 PASSWORD"。** 它有两个既有调用方,语义完全不同:
> - `IamSessionGuardFilter`(权限代次变化时重建主体)→ 必须传 **`principal.getLoginType()`** 保留本次会话的认证方式。若这里写死 `null` 或 `PASSWORD`,一次权限刷新就会把会话的方式改掉(前者导致反复补验,后者是伪造)
> - `ProviderCodeService`(提供方凭据直接换授权码)→ 传 **`null`**,该路径没有第一因素概念,按"未知"处理
>
> 删掉重载后由编译器强制每个调用点表态,避免以后再有人默认一个错误的常量。

- [ ] **Step 1: 写主体单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/principal/IamUserPrincipalTest.java`:

```java
package club.beenest.iam.authz.principal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 主体序列化测试。
 * <p>主体快照存 Redis,升级前签发的主体没有 loginType 字段。
 * 反序列化时该字段必须为 null 而不是抛异常——升级当天所有在线会话
 * 都读不出来,等于把全站用户踢下线。</p>
 */
class IamUserPrincipalTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("存量主体快照缺 loginType 时反序列化为 null")
    void readsLegacyPrincipalWithoutLoginType() throws Exception {
        String legacy = "{\"userId\":1,\"realmId\":1,\"realmKey\":\"default\","
                + "\"username\":\"zhang\",\"nickname\":\"张三\",\"tokenVersion\":0,"
                + "\"authorities\":[],\"attributes\":{},\"permissionEpoch\":3}";

        IamUserPrincipal principal = mapper.readValue(legacy, IamUserPrincipal.class);

        assertNull(principal.getLoginType());
        assertEquals("zhang", principal.getUsername());
    }

    @Test
    @DisplayName("loginType 往返不丢")
    void roundTripsLoginType() throws Exception {
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "LDAP");

        IamUserPrincipal read = mapper.readValue(mapper.writeValueAsString(principal), IamUserPrincipal.class);

        assertEquals("LDAP", read.getLoginType());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=IamUserPrincipalTest`
Expected: FAIL —— 编译错误 "找不到符号: 方法 getLoginType()"

- [ ] **Step 3: 主体加 loginType**

`IamUserPrincipal.java`:

字段区追加:

```java
    /**
     * 本次会话的认证方式(LoginMethod 的存储码)。
     * <p>为准入判定提供依据:客户端可以要求"必须用短信登录",
     * 就得知道当前会话是用什么方式建立的。存量主体没有这个字段,
     * 读出来为 null,判定时按"方式未知"处理——要求补验,而不是放行。</p>
     */
    private final String loginType;
```

`@JsonCreator` 构造器加参数 `loginType`:

```java
    @JsonCreator
    public IamUserPrincipal(@JsonProperty("userId") Long userId,
                            @JsonProperty("realmId") Long realmId,
                            @JsonProperty("realmKey") String realmKey,
                            @JsonProperty("username") String username,
                            @JsonProperty("nickname") String nickname,
                            @JsonProperty("tokenVersion") int tokenVersion,
                            @JsonProperty("authorities") List<String> authorities,
                            @JsonProperty("attributes") Map<String, String> attributes,
                            @JsonProperty("permissionEpoch") long permissionEpoch,
                            @JsonProperty("loginType") String loginType) {
```

构造体中赋值 `this.loginType = loginType;`。

保留原有的 9 参便捷构造器(供大量既有调用点使用),它委托到新构造器并传 `null`:

```java
    public IamUserPrincipal(Long userId, Long realmId, String realmKey, String username, String nickname,
                            int tokenVersion, List<String> authorities, Map<String, String> attributes,
                            long permissionEpoch) {
        this(userId, realmId, realmKey, username, nickname, tokenVersion, authorities, attributes,
                permissionEpoch, null);
    }
```

新增 getter:

```java
    /** 本次会话的认证方式;null 表示未知(存量会话)。 */
    public String getLoginType() {
        return loginType;
    }
```

- [ ] **Step 4: 主体工厂接受 loginType**

`IamPrincipalFactory.java`:

```java
    /**
     * 按用户当前的有效权限构建主体。
     *
     * @param realm     所属身份域
     * @param user      用户
     * @param loginType 本次认证方式(LoginMethod 存储码);未知传 null
     */
    public IamUserPrincipal build(RealmModel realm, UserModel user, String loginType) {
        long epoch = permissionProvider.currentPermissionEpoch(realm.id());
        List<String> permissionCodes = List.copyOf(
                permissionProvider.computePermissionCodes(realm.id(), user.id()));
        return new IamUserPrincipal(
                user.id(), realm.id(), realm.realmKey(), user.username(), user.nickname(),
                user.tokenVersion(), permissionCodes, user.attributes(), epoch, loginType);
    }
```

保留原两参 `build` 委托到新方法并传 `null`(未知方式由调用方显式指定)。

- [ ] **Step 5: 认证 Provider 打标**

`IamAuthenticationProvider.java`:

`authenticate` 方法中,把 LDAP 分支的判定结果记进局部变量:

```java
        boolean passwordAlreadyVerified = false;
        String loginType = LoginMethod.PASSWORD.code();
        if (user == null || !userProvider.hasLocalPassword(user.id())) {
            Optional<UserModel> ldapUser = ldapUserAuthenticator.authenticate(realm, username, password);
            if (ldapUser.isPresent()) {
                user = ldapUser.get();
                passwordAlreadyVerified = true;
                // 目录兜底认证与本地口令是两回事:准入白名单要能区分二者
                loginType = LoginMethod.LDAP.code();
            }
        }
```

`buildAuthentication(RealmModel, UserModel)` 改签名为 `buildAuthentication(RealmModel, UserModel, String loginType)`,并把 loginType 传给工厂:

```java
    public Authentication buildAuthentication(RealmModel realm, UserModel user, String loginType) {
        IamUserPrincipal principal = principalFactory.build(realm, user, loginType);
        return principalFactory.toAuthentication(principal);
    }
```

`authenticate` 的第 10 步改为:

```java
        return buildAuthentication(realm, user, loginType);
```

**不要保留两参重载**。Step 7 之后 MFA 第二步走 `AuthService.completeMfaLogin(realmKey, userId, loginType)`,两参版本没有任何调用方;留着它就等于留了一个"默认按口令"的口子,以后谁接了新登录方式又偷懒调两参版,会话里就记成 PASSWORD 了。

import 增加 `club.beenest.iam.authz.admission.LoginMethod`。

- [ ] **Step 6: MFA 票据携带 loginType**

`MfaPendingService.java`:

```java
    /** 票据值的分隔符(用户主键与认证方式之间)。 */
    private static final String SEPARATOR = ":";

    /**
     * 签发待验证票据。
     *
     * @param realmKey 身份域键
     * @param userId   已通过口令认证的用户主键
     * @param loginType 第一因素认证方式(LoginMethod 存储码)
     * @return 一次性票据
     */
    public String issue(String realmKey, Long userId, String loginType) {
        return issueForSession(realmKey, userId, null, loginType);
    }
```

新增 `consumeTicket`,把原 `consume` 的实现拆成 `consumeRaw`(返回原始串)与 `consume`(返回 userId):

```java
    public Long consume(String realmKey, String token) {
        String value = consumeRaw(realmKey, token);
        if (value == null) {
            return null;
        }
        int index = value.indexOf(SEPARATOR);
        try {
            return Long.valueOf(index < 0 ? value : value.substring(0, index));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取票据原始串并删除票据(一次性)。 */
    private String consumeRaw(String realmKey, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = String.format(IamRedisKeyConstants.MFA_PENDING, realmKey, token);
        String value = redisUtils.get(key);
        if (value == null) {
            return null;
        }
        redisUtils.delete(key);
        return value;
    }
```

> **注意**:票据是一次性的,`consumeRaw` 读完即删。所以不要设计"先取方式、再取主键"的两个方法——第二个调用必然拿到 null。**正确用法**:只保留两个消费入口——`consume`(取用户主键,给既有调用点)与 `consumeTicket`(一次取回主键 + 认证方式,给 MFA 第二步)。本任务实现 `consumeTicket`。

补上:

```java
    /**
     * 消费票据并返回用户主键与认证方式。
     *
     * @param realmKey 身份域键
     * @param token    一次性票据
     * @return 票据内容;票据不存在或已过期返回 null
     */
    public MfaPending consumeTicket(String realmKey, String token) {
        String value = consumeRaw(realmKey, token);
        if (value == null) {
            return null;
        }
        // 统一三段格式:userId:sid:loginType(登录流程的 sid 段为空)
        String[] parts = value.split("\\" + SEPARATOR, 3);
        if (parts.length < 3) {
            return null;
        }
        try {
            // 登录流程不携带 sid,故只取第 1 段与第 3 段;
            // 会话级补验走独立的方法 consumeSession(见 Task 12),那里才需要 sid。
            // 空串归一成 null:票据里用 "" 占位,但 MfaPending 的契约是"未知为 null",
            // 不在这里抹平的话,主体里会存下一个 "" 的登录方式
            return new MfaPending(Long.valueOf(parts[0]), parts[2].isEmpty() ? null : parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

新建 `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mfa/MfaPending.java`:

```java
package club.beenest.iam.authz.mfa;

/**
 * MFA 待验证票据内容。
 *
 * @param userId    已通过第一因素认证的用户主键
 * @param loginType 第一因素认证方式(LoginMethod 存储码);可能为 null
 */
public record MfaPending(Long userId, String loginType) {
}
```

- [ ] **Step 7: MFA 第二步用票据里的 loginType 重建主体**

`AuthService.completeMfaLogin` 增加 loginType 参数:

```java
    /**
     * MFA 第二步完成后重建主体。
     *
     * @param realmKey  身份域标识
     * @param userId    用户主键
     * @param loginType 第一因素认证方式(来自票据);未知传 null
     */
    IamUserPrincipal completeMfaLogin(String realmKey, Long userId, String loginType);
```

实现里把 `principalFactory.build(realm, user)` 改为 `principalFactory.build(realm, user, loginType)`。

`AuthController.loginMfa` 改为:

```java
        MfaPending pending = mfaPendingService.consumeTicket(realmKey, request.mfaToken());
        if (pending == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录票据无效或已过期,请重新登录");
        }
        Long userId = pending.userId();
        ...
        IamUserPrincipal principal = authService.completeMfaLogin(realmKey, userId, pending.loginType());
```

> **主体里的 loginType 必须是票据原值,不能为了"不留空"回落成 PASSWORD。** 主体是准入判定的输入(`AdmissionDecision.decide` 读它):票据缺方式说明来源不明,回落成 PASSWORD 会让"只允许口令"的客户端接纳一个来历不明的会话。**回落只允许发生在审计与会话行这类展示字段上**,见下一步。

同一方法里的两处 `establishSession(..., "PASSWORD")` 与 MFA 失败事件里的 `"PASSWORD"`,都要改成实际方式:

```java
        // 会话记录与登录事件的 login_type 要跟主体一致:
        // LDAP 口令 + MFA 的会话若记成 PASSWORD,管理端看到的登录方式就是假的。
        // 只有主体确实没有方式时才回落到 PASSWORD(本端点第一因素必是凭据)
        String loginType = pending.loginType() == null ? LoginMethod.PASSWORD.code() : pending.loginType();
```

`/login` 方法同理:用 `principal.getLoginType()`,为 null 时回落 `LoginMethod.PASSWORD.code()`。

`IamAuthenticationProvider` 抛出 `MfaRequiredException` 的地方,把 loginType 一并带上:`MfaRequiredException` 增加 `loginType` 字段,`IamAuthenticationFailureHandler` 签发票据时用 `mfaPendingService.issue(realmKey, userId, loginType)`。

- [ ] **Step 8: 联邦按提供方类型打标**

`IamFederationController.java` 的回调方法中,把 `"FEDERATION"` 换成按提供方类型推导的方式:

```java
        // 3. 建立本平台登录态(与口令登录同一套主体与权限公式)
        IamUserPrincipal principal = buildPrincipal(realm, user);
        loginSessionService.establish(request, response, principal, loginTypeOf(realm, alias));
```

新增私有方法:

```java
    /**
     * 本次三方登录对应的第一因素方式。
     * <p>按提供方类型推导:微信(小程序 / 扫码)归并为 WECHAT,
     * 外部 OIDC 与 SAML 各自独立——准入白名单要能区分"用企业 IdP 进的"
     * 与"用微信进的"。</p>
     */
    private String loginTypeOf(RealmModel realm, String alias) {
        LoginMethod method = identityProviderStore.findByIdp(realm.id(), alias)
                .map(IdentityProviderModel::providerId)
                .map(LoginMethodCatalog::toLoginMethod)
                .orElse(null);
        // 认不出提供方类型时退回历史标签 FEDERATION,而不是 null:
        // 内置只有 wechat-mini/wechat-web/oidc/saml,外置扩展包可以注册任意 providerId。
        // iam_user_session.login_type 是 NOT NULL DEFAULT 'PASSWORD',
        // 传 null 会让这些会话被记成"口令登录"——那是个错误的事实。
        // 准入判定不受影响:fromCode("FEDERATION") 仍是 null,非空白名单下照样要求补验
        return method == null ? FEDERATION_LOGIN_TYPE : method.code();
    }
```

类里补一个常量:

```java
    /** 无法归入具体第一因素时的历史标签(保留既有会话与登录事件的取值口径)。 */
    private static final String FEDERATION_LOGIN_TYPE = "FEDERATION";
```

`buildPrincipal` 里改用 `principalFactory.build(realm, user, loginType)`,并把 loginType 作为参数传入。

登录事件的 `loginType` 也从 `"FEDERATION"` 改为同一个值(事件里要能看出是哪种方式)。

- [ ] **Step 9: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am`
Expected: 全绿。若既有测试因 `IamUserPrincipal` 构造器签名变化而编译失败,给它们补 `null` 或 `LoginMethod.PASSWORD.code()`。

---

## Task 5: 会话 mfaVerified 与会话升级

**Files:**
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/session/IamSessionPayload.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/session/IamUserSessionService.java`
- Modify: `beenest-iam-core/src/main/java/club/beenest/iam/core/spi/provider/UserSessionStore.java`
- Modify: `beenest-iam-persistence/src/main/java/club/beenest/iam/persistence/manager/IamUserSessionManager.java`
- Modify: `beenest-iam-persistence/src/main/java/club/beenest/iam/persistence/mapper/UserSessionMapper.java`
- Modify: `beenest-iam-persistence/src/main/resources/mapper/system/UserSessionMapper.xml`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/session/IamSessionPayloadTest.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/session/IamUserSessionServiceUpgradeTest.java`

**Interfaces:**
- Consumes: `IamUserPrincipal.getLoginType()`(Task 4)
- Produces:
  - `IamSessionPayload.mfaVerified()` → `Boolean`
  - `IamSessionPayload.withAuthentication(String loginType, Boolean mfaVerified)` → `IamSessionPayload`(返回新实例,不改原对象)
  - `IamUserSessionService.upgradeAuthentication(String sid, String loginType, Boolean mfaVerified)` → `void`
  - `IamUserSessionService.loadSession(String sid)` → `IamSessionPayload`(既有)
  - `UserSessionStore.updateLoginType(String sid, String loginType)` → `void`(新增;`loginType` 为 null 时不改库)<br>**注**:`UserSessionStore` 现在只有 `insert` / `findBySid` / `updateState` / `touch` / `listByUser` / `expireIfActive` / `listActive`,**没有**通用的 `update(UserSessionModel)`(后者是 record,也没有 setter)。spec §5.3 要求"同步更新 `iam_user_session` 行的 `login_type`",因此本任务新增这一个窄方法,不做通用更新。

- [ ] **Step 1: 写会话快照兼容测试**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/session/IamSessionPayloadTest.java`:

```java
package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话快照序列化测试。
 * <p>会话快照存 Redis,升级前签发的快照没有 mfaVerified 字段。
 * 用原始类型 boolean 会让 Jackson 在反序列化时直接抛异常——
 * 升级当天所有在线会话读不出来,全站掉登录。</p>
 */
class IamSessionPayloadTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("存量会话快照缺 mfaVerified 时反序列化为 null,语义为未完成")
    void readsLegacyPayload() throws Exception {
        String legacy = "{\"principal\":null,\"createdTime\":1000,\"maxLifetimeSeconds\":3600,"
                + "\"idleSeconds\":1800,\"lastTouchMillis\":2000}";

        IamSessionPayload payload = mapper.readValue(legacy, IamSessionPayload.class);

        assertNull(payload.mfaVerified());
        assertFalse(Boolean.TRUE.equals(payload.mfaVerified()));
    }

    @Test
    @DisplayName("升级只改认证字段,存续起算点与时长原样保留")
    void upgradeKeepsLifetime() {
        // principal 必须非空:withAuthentication 对 null 主体是"原样保留",
        // 传 null 会在断言 principal().getLoginType() 时 NPE,测不到想测的东西
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        IamSessionPayload payload = new IamSessionPayload(principal, 1000L, 3600L, 1800L, 2000L, null);

        IamSessionPayload upgraded = payload.withAuthentication("SMS", Boolean.TRUE);

        assertEquals(1000L, upgraded.createdTime());
        assertEquals(3600L, upgraded.maxLifetimeSeconds());
        assertEquals(1800L, upgraded.idleSeconds());
        assertEquals(2000L, upgraded.lastTouchMillis());
        assertEquals("SMS", upgraded.principal().getLoginType());
        assertTrue(upgraded.mfaVerified());
    }

    @Test
    @DisplayName("只补二步验证时不清掉已有的第一因素方式")
    void nullLoginTypeKeepsExistingMethod() {
        // 客户端级 requireMfa 的补验只补第二因素,loginType 传 null;
        // 若这时把主体重建成"方式未知",该会话下一次准入判定又会被要求补验第一因素
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        IamSessionPayload payload = new IamSessionPayload(principal, 1000L, 3600L, 1800L, 2000L, null);

        IamSessionPayload upgraded = payload.withAuthentication(null, Boolean.TRUE);

        assertEquals("PASSWORD", upgraded.principal().getLoginType());
        assertTrue(upgraded.mfaVerified());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=IamSessionPayloadTest`
Expected: FAIL —— 编译错误 "找不到符号: 方法 mfaVerified()"

- [ ] **Step 3: 会话快照加 mfaVerified**

`IamSessionPayload.java` 的 record 分量追加 `Boolean mfaVerified`:

```java
public record IamSessionPayload(
        IamUserPrincipal principal,
        long createdTime,
        long maxLifetimeSeconds,
        long idleSeconds,
        long lastTouchMillis,
        /**
         * 本会话是否已完成二步验证。
         * <p>用包装类型:存量快照没有这个字段,原始类型反序列化会抛异常。
         * null 与 false 同义——"未完成二步验证"。</p>
         */
        Boolean mfaVerified
) {
```

新增:

```java
    /**
     * 补验后的新快照。
     * <p>只改认证相关的两个字段,存续起算点与时长原样保留:
     * 补验是"证明更强的身份",不是"重新开始计时"——
     * 保留原值才能保证补验不会成为续期后门。</p>
     */
    public IamSessionPayload withAuthentication(String loginType, Boolean mfaVerified) {
        // loginType 为 null 表示"不改第一因素方式"(只补二步验证态),
        // 此时保留原主体,不能把已有方式抹成未知
        IamUserPrincipal upgradedPrincipal = principal;
        if (principal != null && loginType != null) {
            upgradedPrincipal = new IamUserPrincipal(
                    principal.getUserId(), principal.getRealmId(), principal.getRealmKey(),
                    principal.getUsername(), principal.getNickname(), principal.getTokenVersion(),
                    principal.getAuthorities(), principal.getAttributes(),
                    principal.getPermissionEpoch(), loginType);
        }
        return new IamSessionPayload(upgradedPrincipal, createdTime, maxLifetimeSeconds,
                idleSeconds, lastTouchMillis, mfaVerified);
    }
```

> **两个既有构造点的处理不一样,不能一律补 `null`:**
> - `startSession`(`IamUserSessionService.java:82`):新会话尚未做二步验证,补 `null`。
> - `refresh`(`IamUserSessionService.java:162`,活跃时间刷新):**必须补 `payload.mfaVerified()`**。这里重建快照是为了改 `lastTouchMillis`;若照抄 `null`,`mfaVerified` 会在每次活跃刷新时(默认每分钟一次)被悄悄清空——用户刚补验完,下一次请求就退回未验证,表现为"怎么也过不去"。
>
> 实现时全局搜索 `new IamSessionPayload(`,**逐个判断**该保留还是该置空,不要机械地全补 null。

- [ ] **Step 4: 会话升级方法**

`IamUserSessionService.java` 新增:

```java
    /**
     * 补验成功:升级会话的认证方式与二步验证完成态。
     * <p>不新建会话:保留同一个 sid 与存续起算点,浏览器侧无感。
     * TTL 按剩余时长重算,不满额重置——否则补验会把会话寿命重新拉满,
     * 反复补验就成了无限续期的后门。</p>
     *
     * @param sid         会话标识
     * @param loginType   新的第一因素认证方式;**null 表示不改**(只补第二因素时用)
     * @param mfaVerified 二步验证完成态;**null 表示重置为未验证**(与 loginType 的 null 语义不同,别按直觉套用)
     */
    public void upgradeAuthentication(String sid, String loginType, Boolean mfaVerified) {
        if (sid == null || sid.isBlank()) {
            return;
        }
        IamSessionPayload payload = loadSession(sid);
        if (payload == null) {
            return;
        }
        IamSessionPayload upgraded = payload.withAuthentication(loginType, mfaVerified);
        long now = System.currentTimeMillis();
        long idleSeconds = payload.idleSeconds();
        long maxSeconds = payload.maxLifetimeSeconds();
        // TTL 取剩余的空闲时长与剩余的最大存续时长,取小者。
        // 时长为 0 表示该维度未配置、不构成约束(与 ttlSeconds / exceededXxx 的语义一致),
        // 按"无限"参与取小;直接相减会得到负数,把会话压成 1 秒即失效
        long remainingIdle = idleSeconds > 0
                ? idleSeconds - (now - payload.lastTouchMillis()) / 1000L
                : Long.MAX_VALUE;
        long remainingMax = maxSeconds > 0
                ? maxSeconds - (now - payload.createdTime()) / 1000L
                : Long.MAX_VALUE;
        long ttl = Math.max(1L, Math.min(remainingIdle, remainingMax));
        redisUtils.setObject(sessionKey(sid), upgraded, Duration.ofSeconds(ttl));
        // 同步数据库行:管理端"在线会话"读的是 iam_user_session,不是 Redis。
        // loginType 为 null 时本方法内部会跳过,不把已有方式抹成未知
        userSessionStore.updateLoginType(sid, loginType);
    }
```

配套的三个窄改动:

`UserSessionStore.java`(core SPI)新增:

```java
    /**
     * 更新会话记录的登录方式(补验升级后同步)。
     * <p>会话的运行期状态在 Redis,但管理端"在线会话"读的是数据库行;
     * 不在这里同步,页面上的登录方式会一直停在首次登录时的值。</p>
     *
     * @param sid       会话标识
     * @param loginType 新的登录方式;为 null 表示本次不改(只补第二因素时)
     */
    void updateLoginType(String sid, String loginType);
```

`IamUserSessionManager.java`(persistence 实现)新增:

```java
    @Override
    public void updateLoginType(String sid, String loginType) {
        // null 表示只补第二因素,不动第一因素:用 null 覆盖会把已有的方式抹掉
        if (loginType == null) {
            return;
        }
        userSessionMapper.updateLoginTypeBySid(sid, loginType);
    }
```

`UserSessionMapper.java` 新增:

```java
    /** 更新会话记录的登录方式(补验升级后同步)。 */
    int updateLoginTypeBySid(@Param("sid") String sid, @Param("loginType") String loginType);
```

`UserSessionMapper.xml` 新增:

```xml
    <update id="updateLoginTypeBySid">
        UPDATE iam_user_session
        SET login_type = #{loginType}, updated_time = CURRENT_TIMESTAMP
        WHERE sid = #{sid} AND deleted = FALSE
    </update>
```

> `iam_user_session.login_type` 是 `VARCHAR(20) NOT NULL DEFAULT 'PASSWORD'`(V1.0.0 建表即有),六个方式码最长 8 字符,放得下。

- [ ] **Step 5: 写升级单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/session/IamUserSessionServiceUpgradeTest.java`:

```java
package club.beenest.iam.authz.session;

import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.util.RedisUtils;
import club.beenest.iam.core.spi.provider.UserSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话升级测试。
 * <p>补验走"升级"而非"新建"的核心原因:新建会让存续时长重新计时,
 * 反复补验就成了无限续期的后门。这里把这条不变式钉死。</p>
 */
class IamUserSessionServiceUpgradeTest {

    private final UserSessionStore store = mock(UserSessionStore.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final IamUserSessionService service = new IamUserSessionService(store, redisUtils);

    @Test
    @DisplayName("升级保留存续起算点与最大时长,TTL 按剩余时长算")
    void upgradeKeepsLifetimeAndDoesNotResetTtl() {
        long now = System.currentTimeMillis();
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        // 已空闲 1500 秒:剩余空闲只剩约 300 秒,与满额的 1800 / 3600 差距足够大,
        // 才能区分"按剩余时长算"与"满额重置"
        IamSessionPayload payload = new IamSessionPayload(
                principal, now - 60_000L, 3600L, 1800L, now - 1_500_000L, null);
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(payload);

        service.upgradeAuthentication("sid-1", "SMS", Boolean.TRUE);

        ArgumentCaptor<IamSessionPayload> captor = ArgumentCaptor.forClass(IamSessionPayload.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisUtils).setObject(eq("iam:session:sid-1"), captor.capture(), ttlCaptor.capture());
        IamSessionPayload upgraded = captor.getValue();
        assertEquals(now - 60_000L, upgraded.createdTime());
        assertEquals(3600L, upgraded.maxLifetimeSeconds());
        assertEquals("SMS", upgraded.principal().getLoginType());
        assertTrue(Boolean.TRUE.equals(upgraded.mfaVerified()));
        // 补验不能成为续期后门:TTL 必须是剩余时长(约 300 秒),
        // 不是 1800(空闲满额)也不是 3600(最大存续满额)
        long ttlSeconds = ttlCaptor.getValue().toSeconds();
        assertTrue(ttlSeconds >= 295 && ttlSeconds <= 300, "TTL 应为剩余时长,实际 " + ttlSeconds);
        // 数据库行也要同步,否则管理端"在线会话"还显示首次登录的方式
        verify(store).updateLoginType("sid-1", "SMS");
    }

    @Test
    @DisplayName("会话不存在时静默返回,不写库也不写 Redis")
    void missingSessionIsNoop() {
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(null);

        service.upgradeAuthentication("sid-1", "SMS", Boolean.TRUE);

        verify(redisUtils, never()).setObject(any(), any(), any(Duration.class));
        verify(store, never()).updateLoginType(any(), any());
    }

    @Test
    @DisplayName("活跃刷新保留 MFA 完成态,不把它清空")
    void touchSessionKeepsMfaVerified() {
        // touchSession 会重建整份快照。若重建时把 mfaVerified 补成 null,
        // 用户补验成功后,下一次请求(默认每分钟一次刷新)就会退回未验证状态,
        // 表现为"补验永远过不去"。这条用例钉住那个静默回退
        long now = System.currentTimeMillis();
        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "SMS");
        // lastTouchMillis 距今超过节流窗口(60 秒),才会真正走重建分支
        IamSessionPayload payload = new IamSessionPayload(
                principal, now - 600_000L, 3600L, 1800L, now - 120_000L, Boolean.TRUE);
        when(redisUtils.getObject(any(), eq(IamSessionPayload.class))).thenReturn(payload);

        service.touchSession("sid-1");

        ArgumentCaptor<IamSessionPayload> captor = ArgumentCaptor.forClass(IamSessionPayload.class);
        verify(redisUtils).setObject(eq("iam:session:sid-1"), captor.capture(), any(Duration.class));
        assertTrue(Boolean.TRUE.equals(captor.getValue().mfaVerified()));
        assertEquals("SMS", captor.getValue().principal().getLoginType());
    }
}
```

> Redis key 的实际拼法以 `IamUserSessionService.sessionKey(sid)` 的实现为准(常量 `USER_SESSION = PREFIX + ":session:%s"`,即 `iam:session:sid-1`)。**实现前先确认**。

- [ ] **Step 6: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=IamSessionPayloadTest,IamUserSessionServiceUpgradeTest`
Expected: PASS

---

## Task 6: 管理端 API 与白名单收敛校验

**Files:**
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/dto/ClientCreateRequest.java`
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/dto/ClientUpdateRequest.java`
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/dto/ClientResponse.java`
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/converter/ClientDtoConverter.java`
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/service/impl/ClientServiceImpl.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/admin/service/impl/ClientServiceImplAdmissionTest.java`

**Interfaces:**
- Consumes: `LoginMethodCatalog.availableInRealm(Long, String)`(Task 2)、`ClientModel` 新字段(Task 1)
- Produces:
  - `ClientCreateRequest.allowedLoginTypes()` → `List<String>`;`ClientCreateRequest.requireMfa()` → `Boolean`
  - `ClientUpdateRequest.allowedLoginTypes()` → `List<String>`;`ClientUpdateRequest.requireMfa()` → `Boolean`
  - `ClientResponse.allowedLoginTypes()` → `Set<String>`;`ClientResponse.requireMfa()` → `Boolean`
  - `ClientServiceImpl` 保存时抛 `BusinessException(ErrorCode.PARAM_INVALID, ...)`

- [ ] **Step 1: 写服务单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/admin/service/impl/ClientServiceImplAdmissionTest.java`:

```java
package club.beenest.iam.admin.service.impl;

import club.beenest.iam.admin.converter.ClientDtoConverter;
import club.beenest.iam.admin.dto.ClientCreateRequest;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.persistence.converter.ClientConverter;
import club.beenest.iam.persistence.manager.IamClientManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 客户端准入策略的保存校验测试。
 * <p>白名单与域级可用方式无交集时必须拒绝保存:
 * 这种配置在运行期表现为"永远登不进去",但配置期完全看不出来。
 * 与其留到客户接入时才发现,不如在保存时挡下。</p>
 */
class ClientServiceImplAdmissionTest {

    private static final Long REALM_ID = 1L;

    private final IamClientManager clientManager = mock(IamClientManager.class);
    private final ClientConverter clientConverter = new ClientConverter();
    private final ClientDtoConverter dtoConverter = new ClientDtoConverter(clientConverter);
    private final LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);

    private ClientServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClientServiceImpl(clientManager, dtoConverter, mock(club.beenest.iam.persistence.manager.IamUserManager.class),
                catalog);
        IamUserPrincipal operator = new IamUserPrincipal(
                2L, REALM_ID, "default", "admin", "管理员", 0, List.of(), Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(operator, null));
        when(catalog.availableInRealm(REALM_ID, "default")).thenReturn(Set.of(LoginMethod.PASSWORD));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("白名单与域级可用方式无交集时拒绝创建")
    void rejectsWhitelistWithNoAvailableMethod() {
        ClientCreateRequest request = createRequest(List.of("SMS"));

        BusinessException thrown = assertThrows(BusinessException.class, () -> service.create(request));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("本域"));
    }

    @Test
    @DisplayName("白名单含未知码时拒绝创建")
    void rejectsUnknownLoginType() {
        // 词表之外的码绝不能静默入库:入库后它永远匹配不上任何登录方式,
        // 表现为"这个客户端就是登不进去",而配置页看起来一切正常
        ClientCreateRequest request = createRequest(List.of("TOTP"));

        BusinessException thrown = assertThrows(BusinessException.class, () -> service.create(request));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("未知的登录方式"));
    }

    /**
     * 构造新建请求。
     * <p>{@link ClientCreateRequest} 加两个准入分量后的分量顺序(共 18 个):
     * clientKey, clientName, clientType, redirectUris, postLogoutRedirectUris,
     * backchannelLogoutUri, reuseRefreshTokens, subjectMode, scopes, requirePkce,
     * requireConsent, accessTokenTtl, refreshTokenTtl, description,
     * allowedLoginTypes, requireMfa, grantTypes, serviceAccountUserId</p>
     */
    private static ClientCreateRequest createRequest(List<String> allowedLoginTypes) {
        return new ClientCreateRequest(
                "app", "应用", "CONFIDENTIAL", List.of("https://app.example.com/cb"),
                List.of(), null, null, null, List.of("openid"), null, null, null,
                null, null, allowedLoginTypes, null, null, null);
    }
}
```

> 已核对签名:`ClientServiceImpl` 当前是 3 参构造(`clientManager`, `dtoConverter`, `userManager`),本任务加第 4 个 `loginMethodCatalog`;`ClientDtoConverter` 是单参构造(`clientConverter`);`LoginMethodCatalog.availableInRealm(Long, String)` 是 `@Component`,`IamUserPrincipal.getRealmKey()` 已存在。以上都不需要再"以实际为准"地猜。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=ClientServiceImplAdmissionTest`
Expected: FAIL —— 编译错误(构造器不匹配 / DTO 无新分量)

- [ ] **Step 3: DTO 加字段**

`ClientCreateRequest.java` 追加两个分量(放在 `grantTypes` 之前):

```java
        /**
         * 允许的第一因素集合;空表示不限制。
         */
        List<@Pattern(regexp = "PASSWORD|LDAP|SMS|WECHAT|OIDC|SAML",
                message = "登录方式只能是 PASSWORD / LDAP / SMS / WECHAT / OIDC / SAML") String> allowedLoginTypes,

        /**
         * 是否强制二步验证。
         */
        Boolean requireMfa
```

`ClientUpdateRequest.java` 追加(**补上与 create 相同的 `@Pattern`**,否则同一个码在 POST 被拒、在 PUT 却被归一化入库,两个端点契约不一致):

```java
        List<@Pattern(regexp = "PASSWORD|LDAP|SMS|WECHAT|OIDC|SAML",
                message = "登录方式只能是 PASSWORD / LDAP / SMS / WECHAT / OIDC / SAML") String> allowedLoginTypes,
        Boolean requireMfa
```

并补 Javadoc 说明"传空数组表示清空白名单(不限制)"。

`ClientResponse.java` 追加:

```java
        Set<String> allowedLoginTypes,
        Boolean requireMfa,
        Set<String> grantTypes,
        Long serviceAccountUserId
```

即两个新分量插在 `grantTypes` 之前、`serviceAccountUserId` 保持末尾 —— 与两个请求 DTO 的位置一致。

- [ ] **Step 4: 转换器映射**

`ClientDtoConverter.toResponse` 的 `new ClientResponse(...)` 是位置构造,两个实参必须插在 `grantTypesOf(...)` **之前**:

```java
                // 准入策略:白名单空集合表示不限制
                clientConverter.splitToSet(client.getAllowedLoginTypes()),
                Boolean.TRUE.equals(client.getRequireMfa()),
                clientConverter.grantTypesOf(client.getGrantTypes()),
                client.getServiceAccountUserId());
```

`ClientDtoConverter.toEntity` 追加:

```java
        // 归一化在写侧:库里只存规范大写码,读侧保持不做任何加工
        client.setAllowedLoginTypes(clientConverter.toCommaSeparated(
                normalizeLoginTypes(request.allowedLoginTypes())));
        client.setRequireMfa(Boolean.TRUE.equals(request.requireMfa()));
```

并在 `ClientDtoConverter` 里加归一化辅助方法:

```java
    /**
     * 登录方式取值归一化:大写 + 去空白 + 去重。
     * <p>放在写侧而不是读侧:库里只应存规范大写码,非法值由服务层的
     * {@code validateAdmissionWhitelist} 在 API 边界拒绝,不在转换层兜底。</p>
     */
    private static List<String> normalizeLoginTypes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }
```

import 补 `java.util.LinkedHashSet` 与 `java.util.Locale`。

`ClientDtoConverter.applyUpdate` 追加:

```java
        // 白名单支持"显式清空":传空数组要写成空串而不是 null,
        // 否则 mapper 的 <if test="... != null"> 会跳过更新,运营清空后旧值仍在
        if (request.allowedLoginTypes() != null) {
            String joined = clientConverter.toCommaSeparated(
                    normalizeLoginTypes(request.allowedLoginTypes()));
            client.setAllowedLoginTypes(joined == null ? "" : joined);
        }
        if (request.requireMfa() != null) {
            client.setRequireMfa(request.requireMfa());
        }
```

- [ ] **Step 5: 服务层校验**

`ClientServiceImpl` 注入 `LoginMethodCatalog`:

```java
    private final LoginMethodCatalog loginMethodCatalog;

    public ClientServiceImpl(IamClientManager clientManager, ClientDtoConverter dtoConverter,
                              IamUserManager userManager, LoginMethodCatalog loginMethodCatalog) {
```

`create` 中在 `validateClientCredentials(...)` 之后加:

```java
        // 4. 准入白名单收敛校验:与域级可用方式无交集的配置在运行期永远登不进去,
        //    必须在配置期就挡下并说明原因
        validateAdmissionWhitelist(request.allowedLoginTypes(), realmId);
```

(随后原有的"落库"注释序号顺延为 `// 5.`。)

`update` 中在 `validateLogoutTargets(...)` 之后加:

```java
        // 3. 更新时同样校验收敛性(白名单可能被改成与域级无交集)。
        //    update 作用域里没有 realmId 变量,用刚取到的 client 的所属域
        validateAdmissionWhitelist(request.allowedLoginTypes(), client.getRealmId());
```

新增私有方法:

```java
    /**
     * 校验准入白名单与域级可用方式有交集。
     * <p>交集为空意味着这个客户端谁都登不进去,而且报错发生在客户接入之后,
     * 排查成本极高。配置期挡下,并在提示里点名是哪个方式不可用。</p>
     */
    private void validateAdmissionWhitelist(List<String> allowedLoginTypes, Long realmId) {
        if (allowedLoginTypes == null || allowedLoginTypes.isEmpty()) {
            return;
        }
        Set<LoginMethod> requested = new LinkedHashSet<>();
        for (String code : allowedLoginTypes) {
            // null / 空白元素按"没写"处理:写侧的 normalizeLoginTypes 本来就会把它们丢掉,
            // 这里若报"未知的登录方式: null",同一个请求在两个环节得到两种结论
            if (code == null || code.isBlank()) {
                continue;
            }
            LoginMethod method = LoginMethod.fromCode(code);
            if (method == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "未知的登录方式: " + code);
            }
            requested.add(method);
        }
        if (requested.isEmpty()) {
            return;
        }
        // 归一化在写侧完成:库里只应存规范大写码。
        // 不在读侧归一化,是为了让"库里存了非法值"这件事在 API 边界就被拒,
        // 而不是静默入库后再也匹配不上任何登录方式
        Set<LoginMethod> available = loginMethodCatalog.availableInRealm(realmId, currentRealmKey());
        // 判据是 spec §2.4 的"交集非空",不是"每个都可用":
        // 运营完全可能先把白名单配好、事后才在某域打开短信通道;
        // 逐个拒绝会把这种正常配置也挡在门外。
        // 只有在"一个都用不了"时才拒绝——那才是"配了但永远登不进去"的死配置
        boolean anyUsable = requested.stream().anyMatch(available::contains);
        if (!anyUsable) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "白名单里的登录方式在本域都不可用(域级未开启或未配置):"
                            + String.join(",", requested.stream().map(LoginMethod::code).toList())
                            + ",该客户端将无人能登录");
        }
    }
```

`currentRealmKey()` 直接取 `CurrentUserHolder.require().getRealmKey()`(该访问器已存在,无需新增),与既有的 `currentRealmId()` 并列放一个私有方法即可。

- [ ] **Step 6: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=ClientServiceImplAdmissionTest`
Expected: PASS

---

## Task 7: 控制台 multiselect 与客户端表单

**Files:**
- Modify: `beenest-iam-web/frontend/src/components/SchemaForm.vue`
- Modify: `beenest-iam-web/frontend/src/views/ClientsView.vue`
- Modify: `beenest-iam-web/frontend/src/lib/types.ts`(给 `FieldType` 加 `'multiselect'`,否则 `vue-tsc --noEmit` 直接报错)
- Create: `beenest-iam-web/frontend/src/lib/clientAdmission.ts`
- Create: `beenest-iam-web/frontend/src/lib/clientAdmission.test.ts`

**Interfaces:**
- Consumes: `ClientCreateRequest.allowedLoginTypes` / `requireMfa`(Task 6)
- Produces:
  - `SchemaForm` 新字段类型 `'multiselect'`,字段定义支持 `options: [{value,label}]`,值为 `string[]`
  - `clientAdmission.ts` 导出 `LOGIN_METHOD_OPTIONS`、`normalizeAllowedLoginTypes(values)`、`buildClientAdmissionPayload(values)`

- [ ] **Step 1: 写前端单测**

Create `beenest-iam-web/frontend/src/lib/clientAdmission.test.ts`:

```ts
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  LOGIN_METHOD_OPTIONS,
  normalizeAllowedLoginTypes,
  buildClientAdmissionPayload,
} from './clientAdmission.ts';

/**
 * 客户端准入字段的表单组装。
 * <p>白名单是"择一"语义:留空表示不限制,不能发成 [""] 或 [null],
 * 后端拿到空串与空数组的行为不同,发错会让"不限制"变成"谁都登不进去"。</p>
 */

test('登录方式选项覆盖全部六个第一因素', () => {
  assert.deepEqual(
    LOGIN_METHOD_OPTIONS.map((o) => o.value).sort(),
    ['LDAP', 'OIDC', 'PASSWORD', 'SAML', 'SMS', 'WECHAT'],
  );
});

test('归一化:去掉空值与重复,保持选项顺序', () => {
  assert.deepEqual(normalizeAllowedLoginTypes(['SMS', '', 'PASSWORD', 'SMS']), ['SMS', 'PASSWORD']);
});

test('归一化:全空表示不限制,得到空数组', () => {
  assert.deepEqual(normalizeAllowedLoginTypes([]), []);
  assert.deepEqual(normalizeAllowedLoginTypes(undefined), []);
});

test('提交体:白名单为空时发空数组,强制 MFA 发布尔', () => {
  const payload = buildClientAdmissionPayload({ allowedLoginTypes: [], requireMfa: true });
  assert.deepEqual(payload, { allowedLoginTypes: [], requireMfa: true });
});

test('提交体:未知选项被丢弃,不原样发给后端', () => {
  const payload = buildClientAdmissionPayload({
    allowedLoginTypes: ['SMS', 'HACK'],
    requireMfa: false,
  });
  assert.deepEqual(payload.allowedLoginTypes, ['SMS']);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd beenest-iam-web/frontend && npm test`
Expected: FAIL —— 找不到模块 `./clientAdmission.ts`

- [ ] **Step 3: 写 clientAdmission.ts**

Create `beenest-iam-web/frontend/src/lib/clientAdmission.ts`:

```ts
/**
 * 客户端准入策略的表单组装。
 * <p>白名单是"择一"语义:留空表示不限制(沿用域级可用方式)。
 * 提交前必须归一化——空串、null、重复项都要清掉,
 * 否则后端会把 [""] 当成一个非法登录方式而拒绝保存。</p>
 */

/** 全部第一因素选项(与后端 LoginMethod 词表一一对应)。 */
export const LOGIN_METHOD_OPTIONS = [
  { value: 'PASSWORD', label: '账号口令' },
  { value: 'LDAP', label: '域目录(LDAP/AD)' },
  { value: 'SMS', label: '短信验证码' },
  { value: 'WECHAT', label: '微信(小程序/扫码)' },
  { value: 'OIDC', label: '外部 OIDC' },
  { value: 'SAML', label: '外部 SAML' },
];

/** 表单取值。 */
export type ClientAdmissionValues = {
  allowedLoginTypes?: unknown;
  requireMfa?: unknown;
};

/** 准入字段的提交体。 */
export interface ClientAdmissionPayload {
  allowedLoginTypes: string[];
  requireMfa: boolean;
}

/** 合法登录方式代码集合。 */
const VALID_CODES = new Set(LOGIN_METHOD_OPTIONS.map((o) => o.value));

/**
 * 归一化白名单:只保留合法代码,去重,保持出现顺序。
 * <p>顺序保留而不是排序:控制台里管理员勾选的顺序本身就是他的表达,
 * 排序会让回显与提交对不上。</p>
 */
export function normalizeAllowedLoginTypes(values: unknown): string[] {
  if (!Array.isArray(values)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  (values as unknown[]).forEach((raw) => {
    const code = String(raw ?? '').trim().toUpperCase();
    if (!VALID_CODES.has(code) || seen.has(code)) return;
    seen.add(code);
    out.push(code);
  });
  return out;
}

/** 组装准入字段的提交体。 */
export function buildClientAdmissionPayload(values: ClientAdmissionValues): ClientAdmissionPayload {
  return {
    allowedLoginTypes: normalizeAllowedLoginTypes(values.allowedLoginTypes),
    requireMfa: Boolean(values.requireMfa),
  };
}
```

- [ ] **Step 4: SchemaForm 加 multiselect 类型**

> 已核对该组件:`model` 是 `const model = reactive<Record<string, any>>({})`,**不是 ref**,取值写 `model[name]`;没有 `toValue`/`normalize` 这两个函数,初始化与取值分别由 `initialValue(field)` 与 `read()` 承担;模板里 `field.options` 已用 vue 的 `toValue()` 解包。

`lib/types.ts` 的 `FieldType` 联合体加一项:

```ts
  | 'multiselect'
```

`SchemaForm.vue` 三处都要改:

1. `initialValue(field)`(第 27 行)里,`props.values` 分支与 `defaultValue` 分支各加一行:

```ts
    if (field.type === 'multiselect') return Array.isArray(value) ? [...value] : [];
```

```ts
    if (field.type === 'multiselect') return [...((field.defaultValue as string[]) ?? [])];
```

2. `read()` 里,紧跟 `tags` 分支之后加:

```ts
    if (field.type === 'multiselect') {
      // 空数组合法:白名单留空表示不限制。不能落到下面的空串分支,
      // 后端对 [] 与 "" 的处理不同
      out[field.name] = Array.isArray(value) ? [...value] : [];
      continue;
    }
```

> **不做必填校验**。白名单留空表示"不限制",空数组是合法值;给 `multiselect` 加"至少选一项"会让运营无法表达"这个客户端不限登录方式"。`validate()` 不需要改动。

3. 模板(第 258 行 `<select>` 之前)加多选渲染:

```html
        <div
          v-else-if="field.type === 'multiselect'"
          :id="fieldId(field)"
          class="input multiselect"
          :class="{ 'is-invalid': fieldErrors[field.name] }"
        >
          <label v-for="option in toValue(field.options) || []" :key="String(option.value)" class="multiselect-item">
            <input
              type="checkbox"
              :value="String(option.value)"
              :checked="(model[field.name] || []).includes(String(option.value))"
              :disabled="field.disabled"
              @change="toggleMulti(field.name, String(option.value), $event)"
            />
            <span>{{ option.label }}</span>
          </label>
        </div>
```

并在 `beenest-iam-web/frontend/src/styles/console.css` 里补样式(该组件的样式都在这个文件,`SchemaForm.vue` 没有 `<style>` 块;`.input` 是固定 38px 高的单行控件,多选会换行,必须放开高度):

```css
/* 多选:复用 .input 的边框与配色,但要允许换行、不要固定高度 */
.input.multiselect {
  height: auto;
  min-height: 38px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  padding: 7px 11px;
}

.multiselect-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  user-select: none;
}
```

并在 `<script setup>` 里加:

```ts
/** 多选字段的勾选切换。 */
function toggleMulti(name: string, value: string, event: Event) {
  const target = event.target as HTMLInputElement;
  const current: string[] = Array.isArray(model[name]) ? [...model[name]] : [];
  if (target.checked) {
    if (!current.includes(value)) current.push(value);
  } else {
    const index = current.indexOf(value);
    if (index >= 0) current.splice(index, 1);
  }
  model[name] = current;
}
```

- [ ] **Step 5: ClientsView 加字段**

> 已核对该视图:字段都挂在 `config.form` 下,键名是 **`fields`** / **`editFields`** / **`defaults`** / **`fromRow`**(没有 `createFields` 这个键);提交体的定制钩子是 `ResourceForm` 的 **`toCreate`** / **`toUpdate`**。

在 `form.fields` 与 `form.editFields` 里各加一项:

```ts
      {
        name: 'allowedLoginTypes',
        label: '允许的登录方式',
        type: 'multiselect',
        full: true,
        options: LOGIN_METHOD_OPTIONS,
        help: '留空表示不限制(沿用身份域可用方式);选定后,会话认证方式不在其中时转入补验流程',
      },
      { name: 'requireMfa', label: '强制二次验证', type: 'switch', onText: '强制' },
```

import 增加 `import { LOGIN_METHOD_OPTIONS, buildClientAdmissionPayload } from '@/lib/clientAdmission';`(`@` 别名指向前端工程的 `src`)。

`form.defaults` 追加:

```ts
      allowedLoginTypes: [],
      requireMfa: false,
```

`form.fromRow` 追加:

```ts
      allowedLoginTypes: [...(row.allowedLoginTypes || [])],
      requireMfa: Boolean(row.requireMfa),
```

`form` 上新增两个提交钩子(**不加这两个,`clientAdmission.ts` 就是死代码,白名单根本发不出去**):

```ts
    // SchemaForm.read() 已经按字段产出数组,这里再归一一次是为了挡住
    // 选项被删改后残留的旧值——发一个后端不认识的码过去,整单都会被拒
    toCreate: (values) => ({ ...values, ...buildClientAdmissionPayload(values) }),
    toUpdate: (values) => ({ ...values, ...buildClientAdmissionPayload(values) }),
```

- [ ] **Step 6: 跑前端测试与构建**

Run: `cd beenest-iam-web/frontend && npm test && npm run build`
Expected: 测试全过,`vue-tsc --noEmit` 无类型错误,构建成功

---

## Task 8: 挑战票据 AdmissionChallengeService

**Files:**
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/AdmissionChallenge.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/AdmissionChallengeService.java`
- Modify: `beenest-iam-common/src/main/java/club/beenest/iam/common/constant/IamRedisKeyConstants.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/AdmissionChallengeServiceTest.java`

**Interfaces:**
- Consumes: `ClientAdmissionPolicy`(Task 3)、`RedisUtils`
- Produces:
  - `AdmissionChallenge(String sid, String clientId, Set<String> allowedLoginTypes, boolean requireMfa, String returnTo, String challengeToken, long createdAt)`
  - `AdmissionChallenge.allows(String loginType)` → `boolean`
  - `AdmissionChallengeService.issue(String realmKey, String sid, String clientId, ClientAdmissionPolicy policy, String returnTo)` → `AdmissionChallenge`
  - `AdmissionChallengeService.resolve(String realmKey, String challengeToken, String sid)` → `AdmissionChallenge`
  - `AdmissionChallengeService.consume(String realmKey, AdmissionChallenge)` → `void`

- [ ] **Step 1: 写服务单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/AdmissionChallengeServiceTest.java`:

```java
package club.beenest.iam.authz.admission;

import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挑战票据测试。
 * <p>票据是一次性的:补验成功后必须消费掉,否则用户可以在同一张票据上
 * 反复尝试不同的方式,等于把准入判定降级成"多试几次总能过"。</p>
 */
class AdmissionChallengeServiceTest {

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final AdmissionChallengeService service = new AdmissionChallengeService(redisUtils);

    @Test
    @DisplayName("签发后能按 sid 找回,按 token 也能找回")
    void issuesAndFinds() {
        when(redisUtils.getObject(any(), eq(AdmissionChallenge.class))).thenReturn(null);

        AdmissionChallenge challenge = service.issue("default", "sid-1", "app",
                new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false),
                "/realms/default/oauth2/authorize?client_id=app");

        assertNotNull(challenge);
        assertEquals("sid-1", challenge.sid());
        assertEquals("app", challenge.clientId());
        assertTrue(challenge.allows("SMS"));
        assertFalse(challenge.allows("PASSWORD"));
        // 两条索引必须都写:只写 sid 的话,SAML 的跨站 POST 回跳(不带 Cookie)
        // 就永远找不到票据,联邦用户会在补验后被当成新登录
        verify(redisUtils).setObject(eq("iam:default:admission:sid:sid-1"),
                any(AdmissionChallenge.class), any(Duration.class));
        verify(redisUtils).setObject(eq("iam:default:admission:token:" + challenge.challengeToken()),
                any(AdmissionChallenge.class), any(Duration.class));
    }

    @Test
    @DisplayName("消费后票据被删除")
    void consumeDeletesTicket() {
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);

        service.consume("default", challenge);

        verify(redisUtils).delete(eq("iam:default:admission:sid:sid-1"));
        verify(redisUtils).delete(eq("iam:default:admission:token:tok"));
    }

    @Test
    @DisplayName("回跳地址不是本域授权端点时拒绝签发")
    void rejectsForeignReturnTo() {
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false);

        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "https://evil.example.com/steal"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "//evil.example.com/steal"));
        // 反斜杠在部分浏览器里等价于 "//":不钉住的话,以后改判断条件很容易把它放过
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "/\\evil.example.com/steal"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "/realms/other/oauth2/authorize"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "sid-1", "app", policy, "/realms/default/evil"));
    }

    @Test
    @DisplayName("没有会话标识时不签发票据")
    void rejectsMissingSid() {
        // 无 sid 的票据会被任意会话凭 token 采用,补验就会去升级别人的会话
        ClientAdmissionPolicy policy = new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false);

        assertThrows(BusinessException.class, () -> service.issue(
                "default", null, "app", policy, "/realms/default/oauth2/authorize"));
        assertThrows(BusinessException.class, () -> service.issue(
                "default", "  ", "app", policy, "/realms/default/oauth2/authorize"));
    }

    // 不写"消费之后再查取不到"的用例:在 mock 的 RedisUtils 上,getObject 的返回值
    // 完全由 when(...) 决定,consume 改成空实现也能通过——那是永不失败的重言式测试。
    // "两条索引都被删"由 consumeDeletesTicket 的 verify(delete) 精确钉住。

    @Test
    @DisplayName("本域授权端点的回跳地址放行")
    void acceptsOwnAuthorizeEndpoint() {
        assertTrue(AdmissionChallengeService.isSafeReturnTo(
                "/realms/default/oauth2/authorize?client_id=app", "default"));
    }

    @Test
    @DisplayName("只有令牌没有 sid 时也能定位:跨站联邦回跳不带 Cookie 的情形")
    void resolvesByTokenWithoutSid() {
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
        when(redisUtils.getObject(eq("iam:default:admission:token:tok"), eq(AdmissionChallenge.class)))
                .thenReturn(challenge);

        assertEquals(challenge, service.resolve("default", "tok", null));
    }

    @Test
    @DisplayName("令牌不匹配时定位不到")
    void rejectsWrongToken() {
        when(redisUtils.getObject(eq("iam:default:admission:token:bad"), eq(AdmissionChallenge.class)))
                .thenReturn(null);

        assertNull(service.resolve("default", "bad", null));
    }
}
```

补 import:`import static org.junit.jupiter.api.Assertions.assertThrows;`、`import static org.junit.jupiter.api.Assertions.assertNull;`、`import club.beenest.iam.common.exception.BusinessException;`

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=AdmissionChallengeServiceTest`
Expected: FAIL —— 找不到符号

- [ ] **Step 3: Redis key 常量**

`IamRedisKeyConstants.java` 追加:

```java
    /** 客户端准入挑战票据(按会话标识索引):iam:{realm}:admission:sid:{sid} */
    public static final String ADMISSION_CHALLENGE_SID = PREFIX + ":%s:admission:sid:%s";
    /** 客户端准入挑战票据(按一次性令牌索引):iam:{realm}:admission:token:{token} */
    public static final String ADMISSION_CHALLENGE_TOKEN = PREFIX + ":%s:admission:token:%s";
```

两条索引并存的原因见 spec §6.1:登录路径按 sid 找,跨站联邦回跳(SAML 走 HTTP-POST)没有 Cookie,只能按 token 找。

- [ ] **Step 4: 写 AdmissionChallenge**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/AdmissionChallenge.java`:

```java
package club.beenest.iam.authz.admission;

import java.util.Set;

/**
 * 一次待处理的准入补验挑战。
 * <p>挑战的含义是"当前会话的认证方式不满足目标客户端的要求,
 * 请用被接受的方式再证明一次"。</p>
 *
 * @param sid               会话标识(挑战归属的会话)
 * @param clientId          目标客户端
 * @param allowedLoginTypes 被接受的第一因素存储码集合
 * @param requireMfa        目标客户端是否还要求二步验证
 * @param returnTo          补验成功后的回跳地址(本域授权端点路径)
 * @param challengeToken    一次性令牌(挑战页表单回传,用于防 CSRF)
 * @param createdAt         签发时刻(毫秒时间戳)
 */
public record AdmissionChallenge(
        String sid,
        String clientId,
        Set<String> allowedLoginTypes,
        boolean requireMfa,
        String returnTo,
        String challengeToken,
        long createdAt
) {
    /** 构造时冻结集合。 */
    public AdmissionChallenge {
        allowedLoginTypes = allowedLoginTypes == null
                ? Set.of() : java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(allowedLoginTypes));
    }

    /**
     * 本次认证方式是否被挑战接受。
     * <p>空集合表示不限制(与客户端策略同义),任何方式都接受。
     * 只用于"仅要求二步验证"的挑战——那种挑战不限定第一因素。
     * <b>配置损坏态(denyAll)不会走到这里</b>:它的白名单同样为空,
     * 若允许签发就会变成"放行一切",所以 {@code AdmissionChallengeService.issue}
     * 对 denyAll 直接拒绝签发。</p>
     */
    public boolean allows(String loginType) {
        if (allowedLoginTypes.isEmpty()) {
            return true;
        }
        return loginType != null && allowedLoginTypes.contains(loginType);
    }
}
```

- [ ] **Step 5: 写 AdmissionChallengeService**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/AdmissionChallengeService.java`:
```java
package club.beenest.iam.authz.admission;

import club.beenest.iam.common.constant.IamRedisKeyConstants;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.util.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 准入挑战票据服务。
 * <p>票据按会话标识索引:挑战必然发生在"用户已有会话"的前提下
 * (没有会话时准入判定直接放行,交给登录页),因此 sid 一定存在。
 * 按 sid 索引让登录路径无需在表单里传递任何票据参数——
 * 建立会话的那一步自己就能查到"本会话是否有待处理的挑战"。</p>
 * <p>票据是一次性的:补验成功后必须消费掉,否则用户可以在同一张票据上
 * 反复尝试不同方式,准入判定就退化成"多试几次总能过"。</p>
 */
@Service
public class AdmissionChallengeService {

    /** 票据有效期(5 分钟):需覆盖用户查看挑战页并完成认证的时间。 */
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(AdmissionChallengeService.class);

    private final RedisUtils redisUtils;

    public AdmissionChallengeService(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    /**
     * 签发挑战票据。
     *
     * @param realmKey 身份域标识
     * @param sid      会话标识
     * @param clientId 目标客户端
     * @param policy   目标客户端的准入策略
     * @param returnTo 补验成功后的回跳地址
     */
    public AdmissionChallenge issue(String realmKey, String sid, String clientId,
                                    ClientAdmissionPolicy policy, String returnTo) {
        // 配置损坏态根本不签发挑战:denyAll 下白名单被归一为空,
        // 而 AdmissionChallenge.allows() 把空集合解释成"不限制"——
        // 签发出去就等于把一条本该生效的限制变成放行一切。
        // 调用方(准入过滤器)本就应先判 denyAll 并直接 403,这里是兜底
        if (policy == null || policy.denyAll()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "客户端准入策略不可用,无法发起验证");
        }
        // 票据必须挂在某个会话上:挑战只发生在"用户已登录"的前提下(没有会话时判定直接放行)。
        // 允许签发无 sid 的票据会造出一种"任何会话凭 token 都能采用"的票,
        // 补验随后会去升级票据里的 sid——那条路不该存在
        if (sid == null || sid.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少会话标识,无法发起验证");
        }
        // 回跳地址必须是本域授权端点:这条路径最终要重定向浏览器,
        // 放任任意地址等于把挑战页变成开放重定向的跳板
        if (!isSafeReturnTo(returnTo, realmKey)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "回跳地址非法");
        }
        Set<String> codes = new LinkedHashSet<>();
        policy.allowedMethods().forEach(method -> codes.add(method.code()));
        String token = UUID.randomUUID().toString().replace("-", "");
        AdmissionChallenge challenge = new AdmissionChallenge(
                sid, clientId, codes, policy.requiresMfa(), returnTo, token, System.currentTimeMillis());
        // 两条索引都要写:登录路径按 sid 找,跨站联邦回跳按 token 找(那时没有 Cookie)
        redisUtils.setObject(sidKey(realmKey, sid), challenge, CHALLENGE_TTL);
        redisUtils.setObject(tokenKey(realmKey, token), challenge, CHALLENGE_TTL);
        return challenge;
    }

    /**
     * 回跳地址是否为本域授权端点。
     * <p>必须以单个斜杠开头:协议相对地址("//evil.com")与反斜杠("/\evil.com")
     * 都会被浏览器解析成外部站点;再要求路径以本域授权端点前缀开头,
     * 把范围收到"只可能回本站授权端点"。</p>
     */
    public static boolean isSafeReturnTo(String returnTo, String realmKey) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        if (!returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.startsWith("/\\")) {
            return false;
        }
        return returnTo.startsWith("/realms/" + realmKey + "/oauth2/authorize");
    }

    /**
     * 定位挑战票据。
     * <p>显式令牌优先:联邦走 HTTP-POST 绑定回跳时(如 SAML)浏览器不带会话
     * Cookie,此时 sid 根本拿不到,只有随回跳带回来的令牌能定位到票据。
     * 令牌索引与 sid 索引并存,两条路径的定位方式不同。</p>
     *
     * @param realmKey       身份域标识
     * @param challengeToken 显式令牌;可为空
     * @param sid           会话标识;可为空
     */
    public AdmissionChallenge resolve(String realmKey, String challengeToken, String sid) {
        if (challengeToken != null && !challengeToken.isBlank()) {
            AdmissionChallenge byToken = redisUtils.getObject(
                    tokenKey(realmKey, challengeToken), AdmissionChallenge.class);
            if (byToken != null) {
                // 令牌必须属于本次请求的会话:否则拿到别人的票据就能改写别人的会话状态
                // (补验会按票据里的 sid 升级)。跨站回跳本来就没有 sid,那种情况 sid 为 null,
                // 只靠令牌定位,不受这条约束影响
                if (sid != null && byToken.sid() != null && !byToken.sid().equals(sid)) {
                    log.warn("挑战令牌与会话不匹配,已忽略: realm={}, tokenSid={}, currentSid={}",
                            realmKey, byToken.sid(), sid);
                } else {
                    return byToken;
                }
            }
        }
        return resolveBySid(realmKey, sid);
    }

    /** 按会话标识读取票据。 */
    public AdmissionChallenge resolveBySid(String realmKey, String sid) {
        if (sid == null || sid.isBlank()) {
            return null;
        }
        return redisUtils.getObject(sidKey(realmKey, sid), AdmissionChallenge.class);
    }

    /** 消费票据(两条索引一起删)。 */
    public void consume(String realmKey, AdmissionChallenge challenge) {
        if (challenge == null) {
            return;
        }
        if (challenge.sid() != null) {
            redisUtils.delete(sidKey(realmKey, challenge.sid()));
        }
        if (challenge.challengeToken() != null) {
            redisUtils.delete(tokenKey(realmKey, challenge.challengeToken()));
        }
    }

    private static String sidKey(String realmKey, String sid) {
        return String.format(IamRedisKeyConstants.ADMISSION_CHALLENGE_SID, realmKey, sid);
    }

    private static String tokenKey(String realmKey, String token) {
        return String.format(IamRedisKeyConstants.ADMISSION_CHALLENGE_TOKEN, realmKey, token);
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=AdmissionChallengeServiceTest`
Expected: PASS

---

## Task 9: 准入判定与 ClientAdmissionFilter

**Files:**
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/AdmissionDecision.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/ClientAdmissionFilter.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/config/IamSecurityConfiguration.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/AdmissionDecisionTest.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/mfa/MfaPendingServiceSessionTest.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mfa/MfaSessionPending.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mfa/MfaPendingService.java`(会话级票据:`issueForSession` / `consumeSession`)
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/integration/AdmissionFilterOrderTest.java`

**Interfaces:**
- Consumes: `ClientAdmissionPolicy`、`AdmissionChallengeService`、`MfaPendingService.issueForSession`、`IamRegisteredClientRepository.policyOf`、`IamRedisSecurityContextRepository.currentPayload/currentSessionId`、`LoginEventEvent`
- Produces:
  - `AdmissionDecision` 工厂方法:`allow()` / `requireFactor(Set<String> allowed)` / `requireMfa()` / `deny()`
  - `ClientAdmissionFilter.decide(ClientAdmissionPolicy, String sessionLoginType, boolean mfaVerified)` → `AdmissionDecision`(包级可见静态方法)
  - `ClientAdmissionFilter` 构造参数:`(RegisteredClientRepository, AdmissionChallengeService, MfaPendingService, LoginEventPublisher)`
  - `MfaPendingService.issueForSession(String realmKey, Long userId, String sid, String loginType)` → `String`(本任务就实现:过滤器要签会话级 MFA 票据,不能等到 Task 12)
  - `MfaPendingService.consumeSession(String realmKey, String token)` → `MfaSessionPending`(record:`userId` / `sid` / `loginType`)
  - **票据格式统一为三段** `userId:sid:loginType`,登录流程的 sid 段留空。`MfaPendingService` 现有 `issue`/`consume`/`consumeTicket` 都按这个格式读**写**(Task 4 已把 `consumeTicket` 改成三段;`issue` 委托给 `issueForSession`)

- [ ] **Step 1: 写判定单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/admission/AdmissionDecisionTest.java`:

```java
package club.beenest.iam.authz.admission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 准入判定测试。
 * <p>判定规则是整个特性的核心,每条分支都要能钉住:
 * 尤其"登录方式未知"(存量会话)必须按不满足处理——
 * 读不到方式就放行,等于给所有升级前的在线会话开了后门。</p>
 */
class AdmissionDecisionTest {

    @Test
    @DisplayName("白名单为空且不强制 MFA 时放行")
    void allowsWhenUnrestricted() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                ClientAdmissionPolicy.unrestricted(), "PASSWORD", false);

        assertTrue(decision.allowed());
        assertFalse(decision.requiresMfa());
    }

    @Test
    @DisplayName("会话方式命中白名单时放行")
    void allowsMatchingMethod() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.SMS, LoginMethod.PASSWORD), false),
                "SMS", false);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("会话方式不在白名单时要求补验")
    void challengesOnMismatch() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.SMS), false),
                "PASSWORD", false);

        assertFalse(decision.allowed());
        assertEquals(Set.of("SMS"), decision.allowedLoginTypes());
    }

    @Test
    @DisplayName("存量会话的登录方式未知时要求补验,不因读不到方式而放行")
    void challengesWhenLoginTypeUnknown() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.PASSWORD), false),
                null, false);

        assertFalse(decision.allowed());
        assertEquals(Set.of("PASSWORD"), decision.allowedLoginTypes());
    }

    @Test
    @DisplayName("客户端强制 MFA 且会话未完成二步验证时要求 MFA 补验")
    void challengesMfa() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.PASSWORD), true),
                "PASSWORD", false);

        assertFalse(decision.allowed());
        assertTrue(decision.requiresMfa());
    }

    @Test
    @DisplayName("会话已完成二步验证时不再要求 MFA")
    void allowsWhenMfaAlreadyDone() {
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                new ClientAdmissionPolicy(Set.of(LoginMethod.PASSWORD), true),
                "PASSWORD", true);

        assertTrue(decision.allowed());
    }

    @Test
    @DisplayName("配置损坏态直接拒绝,不退回放行")
    void deniesWhenPolicyBroken() {
        // denyAll 之下白名单被归一为空。若判定先看"白名单为空就放行",
        // 这条本该生效的限制会被静默解除——必须最先判 denyAll
        AdmissionDecision decision = ClientAdmissionFilter.decide(
                ClientAdmissionPolicy.denyAllPolicy(), "PASSWORD", true);

        assertFalse(decision.allowed());
        assertFalse(decision.requiresMfa());
        assertTrue(decision.allowedLoginTypes().isEmpty());
    }
}
```

- [ ] **Step 2: 写 AdmissionDecision**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/AdmissionDecision.java`:

```java
package club.beenest.iam.authz.admission;

import java.util.Set;

/**
 * 准入判定结果。
 *
 * @param allowed       是否放行
 * @param allowedLoginTypes 被要求的第一因素存储码集合;空且非放行时表示"仅要求二步验证"
 * @param requiresMfa   是否要求二步验证
 */
public record AdmissionDecision(boolean allowed, Set<String> allowedLoginTypes, boolean requiresMfa) {

    /** 放行。 */
    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, Set.of(), false);
    }

    /** 需要用指定的第一因素补验。 */
    public static AdmissionDecision requireFactor(Set<String> allowedLoginTypes) {
        return new AdmissionDecision(false, allowedLoginTypes, false);
    }

    /** 需要补二步验证。 */
    public static AdmissionDecision requireMfa() {
        return new AdmissionDecision(false, Set.of(), true);
    }

    /**
     * 直接拒绝:无法通过补验解决。
     * <p>用于客户端策略处于"配置损坏"态(白名单存了值却一个都解析不出来)。
     * 这种情形下让用户去补验毫无意义——补验完仍然读不懂配置,
     * 只会把用户送进一个死循环。与 {@link #requireFactor} 的区别靠
     * "不允许 + 不要求 MFA + 白名单为空"三者同时成立来区分。</p>
     */
    public static AdmissionDecision deny() {
        return new AdmissionDecision(false, Set.of(), false);
    }
}
```

- [ ] **Step 3: 写判定逻辑(放在 ClientAdmissionFilter 的静态方法里,便于单测)**

`ClientAdmissionFilter` 提供一个包级可见的静态判定方法:

```java
    /**
     * 判定当前会话是否满足客户端的准入要求。
     * <p>抽成静态方法是为了能脱离 Web 容器单测——这条规则是整个特性的核心,
     * 每一条分支都要能被钉住。</p>
     */
    static AdmissionDecision decide(ClientAdmissionPolicy policy, String sessionLoginType,
                                    boolean mfaVerified) {
        // 1. 配置损坏态直接拒绝。必须放在最前:denyAll 之下白名单被归一为空,
        //    若先判断"白名单为空",这里会落到最后一行 allow()——
        //    一条本该生效的限制就被静默解除了
        if (policy.denyAll()) {
            return AdmissionDecision.deny();
        }
        // 2. 第一因素:白名单不空时,会话的认证方式必须命中
        if (!policy.allowedMethods().isEmpty()) {
            LoginMethod method = LoginMethod.fromCode(sessionLoginType);
            // 登录方式未知(存量会话)按不命中处理:无法证明满足要求时要求重新证明
            if (method == null || !policy.allowedMethods().contains(method)) {
                Set<String> codes = new LinkedHashSet<>();
                policy.allowedMethods().forEach(m -> codes.add(m.code()));
                return AdmissionDecision.requireFactor(codes);
            }
        }
        // 3. 二步验证:客户端要求且会话尚未完成
        if (policy.requiresMfa() && !mfaVerified) {
            return AdmissionDecision.requireMfa();
        }
        return AdmissionDecision.allow();
    }
```

- [ ] **Step 4: 写 ClientAdmissionFilter**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/admission/ClientAdmissionFilter.java`:

```java
package club.beenest.iam.authz.admission;

import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.authz.web.IamRedisSecurityContextRepository;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.authz.audit.LoginEventPublisher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 客户端准入过滤器。
 * <p>挂在授权端点之前:请求带 client_id 时按目标客户端的准入策略判定,
 * 不满足则签发挑战票据并转向补验页,而不是直接拒绝——
 * 联邦登录先建会话后知客户端,直接拒绝的表现是"明明登录了又被踢"。</p>
 * <p>判定必须在服务端完成:登录页的展示过滤只是体验优化,
 * 真正的闸门在这里。</p>
 */
public class ClientAdmissionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientAdmissionFilter.class);

    /** 授权端点路径的后缀。 */
    private static final String AUTHORIZE_SUFFIX = "/oauth2/authorize";
    /** 挑战页路径。 */
    private static final String CHALLENGE_PAGE = "/login-challenge";

    private final RegisteredClientRepository registeredClientRepository;
    private final AdmissionChallengeService challengeService;
    private final MfaPendingService mfaPendingService;
    private final LoginEventPublisher loginEventPublisher;

    public ClientAdmissionFilter(RegisteredClientRepository registeredClientRepository,
                                  AdmissionChallengeService challengeService,
                                  MfaPendingService mfaPendingService,
                                  LoginEventPublisher loginEventPublisher) {
        this.registeredClientRepository = registeredClientRepository;
        this.challengeService = challengeService;
        this.mfaPendingService = mfaPendingService;
        this.loginEventPublisher = loginEventPublisher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 1. 只拦授权端点,但 GET 与 POST 都要拦。
        //    SAS 的 RequestMatcher 对两种方法都匹配:POST 携带 response_type/redirect_uri
        //    时同样是一次完整的授权请求(见 OAuth2AuthorizationEndpointFilter 的
        //    createDefaultRequestMatcher)。只拦 GET 的话,直接 POST 就能绕过整个准入闸门,
        //    连 denyAll 的 403 也一并绕过。
        //    路径用 servletPath 而不是 requestURI:部署在 /iam 之类子路径时 requestURI 带前缀
        String method = request.getMethod();
        boolean authorizeRequest = ("GET".equals(method) || "POST".equals(method))
                && servletPath(request).endsWith(AUTHORIZE_SUFFIX);
        if (!authorizeRequest) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientId = request.getParameter("client_id");
        if (clientId == null || clientId.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
        if (registeredClient == null) {
            // 客户端不存在由协议栈自己报错(不泄露是否存在),这里不介入
            filterChain.doFilter(request, response);
            return;
        }
        ClientAdmissionPolicy policy = IamRegisteredClientRepository.policyOf(registeredClient);
        // denyAll 不能走快路径:它的白名单同样为空,漏掉这一项就把配置损坏态放行了
        if (policy.allowedMethods().isEmpty() && !policy.requiresMfa() && !policy.denyAll()) {
            // 无约束:绝大多数客户端走这条快路径
            filterChain.doFilter(request, response);
            return;
        }
        // 2. 取当前会话(未登录则交给登录页处理,那里按策略过滤展示)
        var payload = IamRedisSecurityContextRepository.currentPayload(request);
        if (payload == null || payload.principal() == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String sessionLoginType = payload.principal().getLoginType();
        boolean mfaVerified = Boolean.TRUE.equals(payload.mfaVerified());
        AdmissionDecision decision = decide(policy, sessionLoginType, mfaVerified);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        // 3. 发起挑战:票据挂在当前会话上,补验成功后升级同一个会话
        RealmContext realmContext = RealmContextHolder.get();
        if (realmContext == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String realmKey = realmContext.realm().realmKey();
        String sid = IamRedisSecurityContextRepository.currentSessionId(request);
        // 配置损坏态:补验解决不了(补验完仍然读不懂配置),直接拒绝并留痕。
        // 靠"不允许 + 不要求 MFA + 白名单为空"三点同时成立来识别
        if (!decision.requiresMfa() && decision.allowedLoginTypes().isEmpty()) {
            log.error("客户端 {} 的准入策略无法解析,拒绝授权请求: realm={}, sid={}", clientId, realmKey, sid);
            loginEventPublisher.publish(realmKey, payload.principal().getUserId(), payload.principal().getUsername(),
                    LoginEventEvent.CLIENT_ADMISSION_DENIED, sessionLoginType,
                    request.getRemoteAddr(), request.getHeader("User-Agent"),
                    "客户端准入配置损坏,无法判定");
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "客户端准入配置异常,请联系管理员");
            return;
        }
        // 回跳地址:路径用 servletPath(见上),参数从参数表重新编码——
        // POST 的授权参数在表单体里,getQueryString() 是空的,照抄会让回跳丢失 client_id 等必填项
        String returnTo = servletPath(request) + "?" + encodeQuery(request.getParameterMap());
        AdmissionChallenge challenge = challengeService.issue(
                realmKey, sid, clientId, policy, returnTo);
        loginEventPublisher.publish(realmKey, payload.principal() == null ? null : payload.principal().getUserId(),
                payload.principal() == null ? null : payload.principal().getUsername(),
                LoginEventEvent.CLIENT_ADMISSION_CHALLENGE, sessionLoginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"),
                "需要补验:" + (decision.requiresMfa() ? "MFA" : String.join(",", decision.allowedLoginTypes())));
        // 4. 判定为"需要二步验证"时,签一张会话级 MFA 票据再跳挑战页:
        //    用户已登录,不该被要求重新走一遍完整登录
        if (decision.requiresMfa()) {
            String mfaToken = mfaPendingService.issueForSession(
                    realmKey, payload.principal().getUserId(), sid, sessionLoginType);
            response.sendRedirect(CHALLENGE_PAGE + "?realm=" + realmKey
                    + "&token=" + challenge.challengeToken() + "&mfa=" + mfaToken);
            return;
        }
        response.sendRedirect(CHALLENGE_PAGE + "?realm=" + realmKey + "&token=" + challenge.challengeToken());
    }

    /** 上下文内路径:与 RealmContextFilter 的取法一致,部署在子路径下也正确。 */
    private static String servletPath(HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || path.isBlank() ? request.getRequestURI() : path;
    }

    /** 把请求参数重新编码成查询串(跳过空值)。 */
    private static String encodeQuery(Map<String, String[]> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((name, values) -> {
            for (String value : values) {
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if (!sb.isEmpty()) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return sb.toString();
    }
}
```

- [ ] **Step 5: 协议链接线**

`IamSecurityConfiguration.authorizationServerFilterChain` 的 `.with(authorizationServerConfigurer, ...)` 之前或之后加过滤器:

```java
                .addFilterBefore(new ClientAdmissionFilter(
                                registeredClientRepository, admissionChallengeService,
                                mfaPendingService, loginEventPublisher),
                        org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter.class)
```

并给 `authorizationServerFilterChain` 方法签名补三个参数:`RegisteredClientRepository registeredClientRepository`、`AdmissionChallengeService admissionChallengeService`、`MfaPendingService mfaPendingService`(`LoginEventPublisher` 协议链已有同名 bean,直接注入即可)。

> **排序约束**:`ClientAdmissionFilter` 必须排在 `RealmContextFilter` 之后、`OAuth2AuthorizationEndpointFilter` 之前——前者提供域上下文(`IamRegisteredClientRepository.findByClientId` 依赖它),后者是它要拦截的端点。**排错的失败是静默的**:过滤器照样注册成功、应用照常启动,只是永远轮不到它执行,准入判定形同不存在。
>
> 因此在同一过滤器链上注册时用 `addFilterAfter(clientAdmissionFilter, RealmContextFilter.class)`(`RealmContextFilter` 已通过 `addFilterBefore(..., AuthorizationFilter.class)` 注册过,Spring Security 的 `FilterComparator` 认得出它的次序)。

- [ ] **Step 5b: 钉住过滤器次序(集成测试)**

Create `beenest-iam-server/src/test/java/club/beenest/iam/integration/AdmissionFilterOrderTest.java`:

```java
package club.beenest.iam.integration;

import club.beenest.iam.authz.admission.ClientAdmissionFilter;
import club.beenest.iam.authz.tenant.RealmContextFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.server.authorization.web.OAuth2AuthorizationEndpointFilter;
import org.springframework.security.web.FilterChainProxy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 准入过滤器的链上次序。
 * <p>排错的失败是静默的:过滤器注册成功、应用正常启动,只是永远不执行。
 * 只有把次序钉进测试,才有人能发现"准入闸门其实没装"。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdmissionFilterOrderTest extends IamIntegrationTestBase {

    // 容器与属性的声明照抄 IamWechatLoginIntegrationTest 的 @Container / @DynamicPropertySource
    // (基类不能代劳:Testcontainers 的扩展在声明它的类结束时停容器)

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Test
    @DisplayName("准入过滤器排在域上下文之后、授权端点之前")
    void admissionFilterRunsBetweenRealmContextAndAuthorizeEndpoint() {
        List<String> filters = filterChainProxy.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .map(filter -> filter.getClass().getName())
                .toList();

        int realmContext = indexOf(filters, RealmContextFilter.class);
        int admission = indexOf(filters, ClientAdmissionFilter.class);
        int authorizeEndpoint = indexOf(filters, OAuth2AuthorizationEndpointFilter.class);

        assertTrue(realmContext >= 0, "找不到域上下文过滤器");
        assertTrue(admission >= 0, "找不到准入过滤器:它根本没被注册进任何链");
        assertTrue(authorizeEndpoint >= 0, "找不到授权端点过滤器");
        assertTrue(realmContext < admission, "准入过滤器必须晚于域上下文(否则拿不到域,查不到客户端)");
        assertTrue(admission < authorizeEndpoint, "准入过滤器必须早于授权端点(否则拦不住请求)");
    }

    private static int indexOf(List<String> names, Class<?> type) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(type.getName())) {
                return i;
            }
        }
        return -1;
    }
}
```

- [ ] **Step 6: 登录事件常量**

`LoginEventEvent.java` 追加:

```java
    /** 客户端准入补验发起 */
    public static final String CLIENT_ADMISSION_CHALLENGE = "CLIENT_ADMISSION_CHALLENGE";
    /** 客户端准入补验失败或票据失效 */
    public static final String CLIENT_ADMISSION_DENIED = "CLIENT_ADMISSION_DENIED";
    /** 客户端强制 MFA 补验发起 */
    public static final String CLIENT_ADMISSION_MFA_CHALLENGE = "CLIENT_ADMISSION_MFA_CHALLENGE";
```

- [ ] **Step 6b: 会话级 MFA 票据的单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/mfa/MfaPendingServiceSessionTest.java`:

```java
package club.beenest.iam.authz.mfa;

import club.beenest.iam.common.util.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话 MFA 票据测试。
 * <p>票据要带上 sid:补验针对的是"已有会话",不是一次新登录。
 * 丢了 sid 就只能新建会话,那等于把补验变成重新登录。</p>
 */
class MfaPendingServiceSessionTest {

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final MfaPendingService service = new MfaPendingService(redisUtils);

    @Test
    @DisplayName("票据消费后带回 sid 与认证方式")
    void consumeReturnsSessionContext() {
        when(redisUtils.get(anyString())).thenReturn("7:sid-9:SMS");

        MfaSessionPending pending = service.consumeSession("default", "tok");

        assertEquals(7L, pending.userId());
        assertEquals("sid-9", pending.sid());
        assertEquals("SMS", pending.loginType());
    }

    @Test
    @DisplayName("票据不存在时返回 null")
    void missingTicket() {
        when(redisUtils.get(anyString())).thenReturn(null);

        assertNull(service.consumeSession("default", "tok"));
    }
}
```

- [ ] **Step 6c: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=MfaPendingServiceSessionTest`
Expected: FAIL —— 找不到符号

- [ ] **Step 6d: 会话级票据的消费方法**

> `issueForSession` **已经存在**(Task 4 让 `issue` 委托给它,不实现它 Task 4 就编译不过),格式也已是三段 `userId:sid:loginType`。本步**只补消费侧**:新增 `MfaSessionPending` 与 `consumeSession`,不要重复定义 `issueForSession`。

新建 `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mfa/MfaSessionPending.java`:

```java
package club.beenest.iam.authz.mfa;

/**
 * 会话级 MFA 待验证票据内容。
 *
 * @param userId    用户主键
 * @param sid       会话标识(补验后升级这个会话)
 * @param loginType 第一因素认证方式
 */
public record MfaSessionPending(Long userId, String sid, String loginType) {
}
```

`MfaPendingService.java` 新增:

```java
    /**
     * 消费会话级 MFA 票据。
     *
     * @return 票据内容;不存在或已过期返回 null
     */
    public MfaSessionPending consumeSession(String realmKey, String token) {
        String value = consumeRaw(realmKey, token);
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\" + SEPARATOR, 3);
        // 只认三段:会话级票据是本轮新增的,不存在两段的历史值;
        // 放宽到两段会把两段值的第二段误当成 sid
        if (parts.length < 3) {
            return null;
        }
        try {
            // 与 consumeTicket 同一口径:票据里用 "" 占位,契约上"未知为 null"
            String loginType = parts[2].isEmpty() ? null : parts[2];
            return new MfaSessionPending(Long.valueOf(parts[0]), parts[1].isEmpty() ? null : parts[1], loginType);
        } catch (NumberFormatException e) {
            return null;
        }
    }
```

- [ ] **Step 7: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=AdmissionDecisionTest`
Expected: PASS

---

## Task 10: establish 单点升级

**Files:**
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/session/IamLoginSessionService.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/IamAuthenticationSuccessHandler.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/AuthController.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/LoginRequest.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/SmsLoginRequest.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/session/IamLoginSessionServiceUpgradeTest.java`

**Interfaces:**
- Consumes: `AdmissionChallengeService.resolve` / `consume`、`IamUserSessionService.upgradeAuthentication`、`IamSessionCookie.read`
- Produces:
  - `IamLoginSessionService.establish(request, response, principal, loginType)` → 保留,内部委托
  - `IamLoginSessionService.establish(request, response, principal, loginType, challengeToken)` → 新增重载
  - `IamLoginSessionService.establish` 返回 `boolean`:**true = 走了升级路径**(调用方据此决定回跳目标)

- [ ] **Step 1: 写单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/session/IamLoginSessionServiceUpgradeTest.java`:

```java
package club.beenest.iam.authz.session;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话升级的入口测试。
 * <p>补验成功的标志是"没有新建会话",而是同一个 sid 被升级。
 * 断言 userSessionService.startSession 没被调用,才能防止实现悄悄退回新建。</p>
 */
class IamLoginSessionServiceUpgradeTest {

    private final IamUserSessionService userSessionService = mock(IamUserSessionService.class);
    private final AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);

    private IamLoginSessionService service() {
        // 构造参数取自现有实现,只把 challengeService 插在第 2 位:
        // (userSessionService, challengeService, loginEventPublisher, securityContextRepository, cookieSecure)
        return new IamLoginSessionService(userSessionService, challengeService,
                mock(club.beenest.iam.authz.audit.LoginEventPublisher.class),
                mock(org.springframework.security.web.context.SecurityContextRepository.class), false);
    }

    @Test
    @DisplayName("挑战中且方式命中时升级会话,不新建")
    void upgradesInsteadOfCreating() {
        IamLoginSessionService service = service();
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
        when(challengeService.resolve(anyString(), any(), any())).thenReturn(challenge);

        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean upgraded = service.establish(request, response, principal, "SMS", null);

        assertTrue(upgraded);
        verify(userSessionService).upgradeAuthentication(eq("sid-1"), eq("SMS"), any());
        verify(userSessionService, never()).startSession(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("挑战中但方式不命中时按普通登录处理")
    void fallsBackToNormalLogin() {
        IamLoginSessionService service = service();
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS"), false, "/realms/default/oauth2/authorize", "tok", 1L);
        when(challengeService.resolve(anyString(), any(), any())).thenReturn(challenge);

        IamUserPrincipal principal = new IamUserPrincipal(
                1L, 1L, "default", "zhang", "张三", 0, List.of(), Map.of(), 3L, "PASSWORD");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean upgraded = service.establish(request, response, principal, "PASSWORD", null);

        assertEquals(false, upgraded);
        verify(userSessionService).startSession(any(), eq("PASSWORD"), any(), any());
    }
}
```

> `IamLoginSessionService` 的构造器签名与 `establish` 的实际形态以实现为准。`startSession` 的参数个数按 `IamUserSessionService.startSession(principal, loginType, ip, userAgent)` 为 4 个。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=IamLoginSessionServiceUpgradeTest`
Expected: FAIL —— 编译错误(方法不存在 / 构造器不匹配)

- [ ] **Step 3: establish 加重载**

`IamLoginSessionService.java`(先给类加上 `AdmissionChallengeService` 依赖,构造参数顺序为
`(userSessionService, challengeService, loginEventPublisher, securityContextRepository, cookieSecure)`,并在 `establish` 上方加 import):

```java
    /**
     * 建立登录态。
     * <p>统一入口:表单登录、REST 登录、联邦回调都走这里。
     * 本方法负责识别"本会话是否有待处理的准入挑战",并据此决定
     * 是升级原会话还是新建会话。</p>
     *
     * @param request       当前请求
     * @param response      当前响应
     * @param principal     登录主体
     * @param loginType     本次认证方式(LoginMethod 存储码)
     * @return true 表示走了升级路径(补验成功并升级了原会话),false 表示新建了会话
     */
    public boolean establish(HttpServletRequest request, HttpServletResponse response,
                             IamUserPrincipal principal, String loginType) {
        return establish(request, response, principal, loginType, null);
    }

    /**
     * 建立登录态(可携带挑战令牌)。
     * <p>挑战令牌用于跨站联邦回跳:平台会话 Cookie 是 SameSite=Lax,
     * SAML 走 HTTP-POST 绑定回跳时是跨站 POST,浏览器不带 Cookie,
     * 此时只能靠随回跳带回来的令牌定位挑战。</p>
     *
     * @param challengeToken 挑战令牌;可为空
     */
    public boolean establish(HttpServletRequest request, HttpServletResponse response,
                             IamUserPrincipal principal, String loginType, String challengeToken) {
        // 1. 定位本会话待处理的准入挑战。
        //    域键取自主体而不是 RealmContextHolder:联邦回跳等路径的线程上下文未必就绪,
        //    而主体里的域键一定是本次登录的域
        String realmKey = principal.getRealmKey();
        String sid = IamSessionCookie.read(request);
        AdmissionChallenge challenge = challengeService.resolve(realmKey, challengeToken, sid);
        if (challenge != null && challenge.allows(loginType)) {
            // 2. 方式命中:消费挑战并升级原会话(保留 sid 与存续起算点,不写新 Cookie)
            challengeService.consume(realmKey, challenge);
            IamSessionPayload payload = userSessionService.loadSession(challenge.sid());
            Boolean mfaVerified = payload == null ? null : payload.mfaVerified();
            userSessionService.upgradeAuthentication(challenge.sid(), loginType, mfaVerified);
            loginEventPublisher.publish(realmKey, principal.getUserId(), principal.getUsername(),
                    LoginEventEvent.LOGIN_SUCCESS, loginType,
                    request.getRemoteAddr(), request.getHeader("User-Agent"), null);
            return true;
        }
        // 3. 无挑战,或方式不命中:按普通登录处理。
        //    方式不命中时保留票据:用户可能正要去访问门户而非受限应用,不该被拦
        String newSid = userSessionService.startSession(principal, loginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
        request.setAttribute(IamRedisSecurityContextRepository.SESSION_ID_ATTRIBUTE, newSid);
        IamSessionCookie.write(response, newSid, cookieSecure, Duration.ofSeconds(maxLifetimeSeconds()));
        loginEventPublisher.publish(realmKey, principal.getUserId(), principal.getUsername(),
                LoginEventEvent.LOGIN_SUCCESS, loginType,
                request.getRemoteAddr(), request.getHeader("User-Agent"), null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(toAuthentication(principal));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return false;
    }
```

> 上面的代码是**把现有 `establish` 的方法体原样搬进新重载**,只多了第 1、2 步。现有实现里的 `startSession` / `SESSION_ID_ATTRIBUTE` / `IamSessionCookie.write` / `maxLifetimeSeconds()` / `toAuthentication` / `saveContext` 都照抄,不要另造 `ip(request)`、`recordLoginEvent`、`sessionMaxAge` 这类不存在的辅助方法。
>
> **返回类型由 `IamUserPrincipal` 改为 `boolean`**:三个既有调用方(`IamAuthenticationSuccessHandler:70`、`AuthController:390`、`IamFederationController:203`)都是语句调用、不接收返回值,因此只改签名即可,不需要改调用点;Task 13 会用到返回值。
>
> **本任务不碰 `IamFederationController`**:联邦那条路径由 Task 13 统一改(state 载荷扩段 + 回跳目标 + 令牌传递)。本任务只把 `establish` 的新重载准备好,联邦调用点暂时仍走 4 参重载(令牌为 null),编译与行为都不受影响。

- [ ] **Step 4: 三条路径接入 challengeToken**

`IamAuthenticationSuccessHandler.onAuthenticationSuccess` 里,把 `loginSessionService.establish(request, response, principal, "PASSWORD")` 改为传入请求参数里的挑战令牌。**注意不要顺手把方式又写死回 `"PASSWORD"`**——Task 4 已把它改成取自主体:

```java
        if (authentication.getPrincipal() instanceof IamUserPrincipal principal) {
            String loginType = principal.getLoginType() == null || principal.getLoginType().isBlank()
                    ? LoginMethod.PASSWORD.code() : principal.getLoginType();
            loginSessionService.establish(request, response, principal, loginType,
                    request.getParameter("challengeToken"));
        }
```

`AuthController.establishSession(...)` 增加一个 `challengeToken` 参数,`/api/v1/auth/login` 与 `/api/v1/auth/login-sms` 从请求体读取:

```java
    private CurrentUserResponse establishSession(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
                                                 IamUserPrincipal principal, String loginType,
                                                 String challengeToken) {
        loginSessionService.establish(httpRequest, httpResponse, principal, loginType, challengeToken);
        return CurrentUserResponse.from(principal);
    }
```

`LoginRequest` / `SmsLoginRequest` DTO 加 `String challengeToken` 字段(可为空,不加校验注解)。

`establishSession` 共三处调用,**第三处 `loginMfa` 传 `null`**:

| 调用点 | 传什么 |
|---|---|
| `/api/v1/auth/login`(约 :162) | `request.challengeToken()` |
| `/api/v1/auth/login-mfa`(约 :196) | `null` —— 这是登录期 MFA,不是准入挑战;补验的票据在这里按 sid 定位,浏览器仍带着会话 Cookie |
| `/api/v1/auth/login-sms`(约 :254) | `request.challengeToken()` |

- [ ] **Step 5: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am`
Expected: 全绿

---

## Task 11: 挑战页与补验流程

**Files:**
- Create: `beenest-iam-web/src/main/java/club/beenest/iam/web/controller/AdmissionChallengeController.java`
- Create: `beenest-iam-web/src/main/resources/themes/nestid/login/challenge.html`
- Modify: `beenest-iam-web/src/main/java/club/beenest/iam/web/config/IamWebConfiguration.java`(若需要放行路由)
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/config/IamSecurityConfiguration.java`(业务链放行 `/login-challenge`)
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/AdmissionChallengeControllerTest.java`

**Interfaces:**
- Consumes: `AdmissionChallengeService`、`LoginMethodCatalog`、`ClientAdmissionPolicy`
- Produces:
  - `GET /login-challenge?realm=&token=` → 渲染挑战页
  - `AdmissionChallengeController` 方法 `challengePage(...)`

- [ ] **Step 1: 写控制器单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/AdmissionChallengeControllerTest.java`:

```java
package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.service.AuthService;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 挑战页控制器测试。
 * <p>挑战页只展示"白名单 ∩ 域级可用方式"里的方式:
 * 多展示一个用户就会去试,而试错的过程本身会泄露"这个应用接受什么"。</p>
 */
class AdmissionChallengeControllerTest {

    @Test
    @DisplayName("挑战页只展示白名单与域级可用方式的交集")
    void showsOnlyIntersection() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(
                new RealmModel(1L, "default", "默认域", true, false, 300L, 1800L,
                        1800L, 36000L, null, null)));
        when(themeSelector.view(anyString())).thenReturn("login/challenge");
        when(catalog.availableInRealm(1L, "default")).thenReturn(Set.of(LoginMethod.PASSWORD, LoginMethod.SMS));

        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of("SMS", "WECHAT"), false,
                "/realms/default/oauth2/authorize", "tok", 1L);
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);

        AdmissionChallengeController controller = new AdmissionChallengeController(
                challengeService, catalog, realmProvider, themeSelector,
                mock(AuthService.class),
                mock(club.beenest.iam.authz.session.IamLoginSessionService.class),
                mock(club.beenest.iam.authz.mfa.MfaPendingService.class),
                mock(club.beenest.iam.core.spi.provider.IdentityProviderStore.class));
        Model model = mock(Model.class);

        controller.challengePage("default", "tok", null, model, null);

        verify(model).addAttribute("allowedMethods", Set.of("SMS"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=AdmissionChallengeControllerTest`
Expected: FAIL —— 找不到符号

- [ ] **Step 3: 写控制器**

Create `beenest-iam-web/src/main/java/club/beenest/iam/web/controller/AdmissionChallengeController.java`:

```java
package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.web.config.IamThemeSelector;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 准入补验页控制器。
 * <p>挑战页只展示"白名单 ∩ 域级可用方式"里的登录方式:
 * 多展示一个,用户就会去试,而试错过程本身会泄露"这个应用接受什么"。</p>
 */
@Controller
public class AdmissionChallengeController {

    private final AdmissionChallengeService challengeService;
    private final LoginMethodCatalog loginMethodCatalog;
    private final RealmProvider realmProvider;
    private final IamThemeSelector themeSelector;
    private final AuthService authService;
    private final IamLoginSessionService loginSessionService;
    private final MfaPendingService mfaPendingService;

    public AdmissionChallengeController(AdmissionChallengeService challengeService,
                                        LoginMethodCatalog loginMethodCatalog,
                                        RealmProvider realmProvider,
                                        IamThemeSelector themeSelector,
                                        AuthService authService,
                                        IamLoginSessionService loginSessionService,
                                        MfaPendingService mfaPendingService) {
        this.challengeService = challengeService;
        this.loginMethodCatalog = loginMethodCatalog;
        this.realmProvider = realmProvider;
        this.themeSelector = themeSelector;
        this.authService = authService;
        this.loginSessionService = loginSessionService;
        this.mfaPendingService = mfaPendingService;
    }

    /**
     * 渲染补验页。
     *
     * @param realm    身份域标识
     * @param token    挑战令牌
     * @param model    视图模型
     * @param csrfToken CSRF 令牌
     */
    @GetMapping("/login-challenge")
    public String challengePage(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                @RequestParam(value = "token", required = false) String token,
                                @RequestParam(value = "mfa", required = false) String mfaToken,
                                Model model,
                                CsrfToken csrfToken) {
        AdmissionChallenge challenge = challengeService.resolve(realm, token, null);
        if (challenge == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
        }
        Long realmId = realmProvider.findByRealmKey(realm)
                .map(realmModel -> realmModel.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "身份域不存在: " + realm));
        // 白名单 ∩ 域级可用方式
        Set<LoginMethod> available = loginMethodCatalog.availableInRealm(realmId, realm);
        Set<String> allowed = new LinkedHashSet<>();
        for (String code : challenge.allowedLoginTypes()) {
            LoginMethod method = LoginMethod.fromCode(code);
            if (method != null && available.contains(method)) {
                allowed.add(code);
            }
        }
        model.addAttribute("realmKey", realm);
        model.addAttribute("challengeToken", token);
        model.addAttribute("allowedMethods", allowed);
        model.addAttribute("requireMfa", challenge.requireMfa());
        model.addAttribute("mfaToken", mfaToken == null ? "" : mfaToken);
        model.addAttribute("_csrf", csrfToken);
        return themeSelector.view("login/challenge");
    }

    /**
     * 提交口令补验。
     * <p>口令校验复用平台的 {@code AuthenticationManager}(内部走 IamAuthenticationProvider),
     * 不另写一套:两套口令校验迟早会漂移,而漂移发生在认证路径上。</p>
     * <p>账号已绑定 TOTP 时 Provider 会抛 MfaRequiredException——初始登录已完成二步验证,
     * 这里改走会话级 MFA 补验(见 {@code MfaPendingService.issueForSession})。</p>
     */
    @PostMapping("/login-challenge")
    public String submitChallenge(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                  @RequestParam(value = "token", required = false) String token,
                                  @RequestParam(value = "username", required = false) String username,
                                  @RequestParam(value = "password", required = false) String password,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        // 1. 定位票据。这里带上网关会话标识:票据与会话绑定,
        //    别人拿着泄露的令牌也无法把凭据验证的结果升级到另一个会话上
        String sid = IamSessionCookie.read(request);
        AdmissionChallenge challenge = challengeService.resolve(realm, token, sid);
        if (challenge == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
        }
        // 2. 本页只处理凭据补验;缺字段说明不是这个表单提交的(如 MFA 表单)
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写账号与口令");
        }
        try {
            // 3. 复用统一认证服务:账号状态、失败计数、权限计算与口令登录完全一致
            IamUserPrincipal principal = authService.authenticate(username, password);
            String loginType = principal.getLoginType();
            // 4. 方式不在白名单里就不升级:establish 在"方式不命中"时会退回新建会话,
            //    那会让一次不该被接受的验证变成一次普通登录
            if (!challenge.allows(loginType)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "该登录方式不被此应用接受");
            }
            // 5. 升级原会话:challengeToken 命中票据,establish 走升级分支而非新建
            loginSessionService.establish(request, response, principal, loginType, token);
            return "redirect:" + challenge.returnTo();
        } catch (MfaRequiredException e) {
            // 该账号绑定了 TOTP:签会话级 MFA 票据,回挑战页的 MFA 形态
            String mfaToken = mfaPendingService.issueForSession(
                    realm, e.getUserId(), sid, e.getLoginType());
            return "redirect:/login-challenge?realm=" + realm + "&token=" + token + "&mfa=" + mfaToken;
        } catch (AuthenticationException e) {
            // 账密错误、账号锁定、失败次数触发的验证码要求,都交给登录页处理:
            // 登录页会按失败计数展示验证码框并给出错误提示,本页没有这些能力。
            // 登录成功后会按 sid 重新找到本票据并升级会话,用户不会丢流程
            String returnTo = UriUtils.encodeQueryParam(challenge.returnTo(), StandardCharsets.UTF_8);
            return "redirect:/login?realm=" + realm + "&return_to=" + returnTo;
        }
    }
}
```

> `MfaRequiredException` 的访问器是 `getUserId()` / `getLoginType()` / `isEnrollmentRequired()`(已核实);`currentSid` 不需要另写,直接用 `IamSessionCookie.read(request)`。
>
> 需要的 import:`club.beenest.iam.authz.authentication.MfaRequiredException`、`club.beenest.iam.authz.principal.IamUserPrincipal`、`club.beenest.iam.authz.service.AuthService`、`club.beenest.iam.authz.session.IamLoginSessionService`、`club.beenest.iam.authz.session.IamSessionCookie`、`club.beenest.iam.authz.mfa.MfaPendingService`、`org.springframework.security.core.AuthenticationException`、`org.springframework.web.util.UriUtils`、`java.nio.charset.StandardCharsets`、`jakarta.servlet.http.HttpServletRequest/HttpServletResponse`、`org.springframework.web.bind.annotation.PostMapping`。
>
> **MFA 表单的提交分支属于 Task 12**,本任务只渲染表单不处理它。本任务里该表单提交会落到第 2 步的"请填写账号与口令",不会 NPE——这是有意的中间态,Task 12 紧接着补上分支。

- [ ] **Step 4: 挑战页模板与入口**

> 挑战页必须让用户**真的能把每一种允许的方式走完**,否则白名单只收短信/微信的客户端会卡在一个"没有可用方式"的死页上。四条路径各不相同:
> - PASSWORD / LDAP → 本页表单 `POST /login-challenge`
> - MFA → 本页表单 `POST /login-challenge`(带 `mfaToken`)
> - WECHAT / OIDC / SAML → 链到 `/broker/{realm}/{alias}/login?challengeToken=…`(带令牌:跨站回跳时浏览器不带 Cookie,见 Task 13)
> - SMS → 链到 `/login?realm=…`:短信登录是同站流程,会话 Cookie 在,登录成功后 `establish` 会按 sid 找到挑战并升级会话,不需要额外传令牌

控制器补一个模型属性:按允许的联邦方式列出本域已启用的提供方。

```java
        // 联邦入口:白名单里出现 WECHAT / OIDC / SAML 时,列出本域可用的提供方
        // (同一类型可能有多个别名,必须逐个给出,不能只按类型给一个)
        List<ChallengeIdp> idps = new ArrayList<>();
        identityProviderStore.listByRealm(realmId).stream()
                .filter(IdentityProviderModel::enabled)
                .map(idp -> Map.entry(idp, LoginMethodCatalog.toLoginMethod(idp.providerId())))
                .filter(entry -> entry.getValue() != null && allowed.contains(entry.getValue().code()))
                .forEach(entry -> idps.add(new ChallengeIdp(entry.getKey().alias(),
                        entry.getKey().displayName(), entry.getValue().code())));
        model.addAttribute("idps", idps);
        model.addAttribute("smsAllowed", allowed.contains(LoginMethod.SMS.code()));
```

```java
/** 挑战页上的联邦入口(提供方别名 + 展示名 + 对应方式)。 */
public record ChallengeIdp(String alias, String displayName, String method) {
}
```

Create `beenest-iam-web/src/main/resources/themes/nestid/login/challenge.html`,参照 `themes/nestid/login/login.html` 的结构与样式类:

```html
<!DOCTYPE html>
<html lang="zh-CN" xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <title>补充验证 · 蜂巢身份</title>
    <link rel="stylesheet" th:href="@{/theme-static/css/iam.css}">
</head>
<body class="iam-auth">
<div class="auth-shell">
    <section class="auth-card">
        <h1 class="auth-title">补充验证</h1>

        <!-- MFA 挑战:只展示二步验证,不再展示第一因素(用户已经登录过了) -->
        <th:block th:if="${requireMfa}">
            <p class="auth-subtitle">该应用要求二次验证,请输入动态口令。</p>
            <form method="post" th:action="@{/login-challenge}" class="auth-form">
                <input type="hidden" name="realm" th:value="${realmKey}"/>
                <input type="hidden" name="token" th:value="${challengeToken}"/>
                <input type="hidden" name="mfaToken" th:value="${mfaToken}"/>
                <input type="hidden" th:name="${_csrf?.parameterName}" th:value="${_csrf?.token}"/>
                <label class="auth-label" for="challengeCode">动态口令</label>
                <input id="challengeCode" name="code" class="auth-input" inputmode="numeric"
                       autocomplete="one-time-code"/>
                <button type="submit" class="auth-submit">完成验证</button>
            </form>
        </th:block>

        <!-- 第一因素挑战 -->
        <th:block th:unless="${requireMfa}">
            <p class="auth-subtitle">该应用对登录方式有要求,请用下列方式之一完成验证。</p>

            <!-- 凭据补验:只有白名单含 PASSWORD / LDAP 时才展示 -->
            <form th:if="${allowedMethods.contains('PASSWORD') or allowedMethods.contains('LDAP')}"
                  method="post" th:action="@{/login-challenge}" class="auth-form">
                <input type="hidden" name="realm" th:value="${realmKey}"/>
                <input type="hidden" name="token" th:value="${challengeToken}"/>
                <input type="hidden" th:name="${_csrf?.parameterName}" th:value="${_csrf?.token}"/>
                <label class="auth-label" for="challengeUsername">账号</label>
                <input id="challengeUsername" name="username" class="auth-input" autocomplete="username"/>
                <label class="auth-label" for="challengePassword">口令</label>
                <input id="challengePassword" name="password" type="password" class="auth-input"
                       autocomplete="current-password"/>
                <button type="submit" class="auth-submit">完成验证</button>
            </form>

            <!-- 短信:同站流程,回到登录页完成即可;会话在,登录成功后按 sid 升级 -->
            <p th:if="${smsAllowed}" class="auth-alt">
                <a th:href="@{/login(realm=${realmKey})}">改用短信验证码登录</a>
            </p>

            <!-- 联邦:必须带 challengeToken,跨站回跳时 Cookie 带不回来 -->
            <div th:if="${!idps.isEmpty()}" class="auth-alt">
                <a th:each="idp : ${idps}"
                   th:href="@{'/broker/' + ${realmKey} + '/' + ${idp.alias} + '/login'(challengeToken=${challengeToken})}"
                   class="auth-idp"
                   th:text="${idp.displayName}">微信登录</a>
            </div>
        </th:block>

        <p th:if="${allowedMethods.isEmpty() and !requireMfa}" class="auth-error">
            当前没有可用于该应用的登录方式,请联系管理员。
        </p>
    </section>
</div>
</body>
</html>
```

> 样式类名(`auth-shell` / `auth-card` / `auth-title` / `auth-alt` / `auth-idp`)以 `themes/nestid/login/login.html` 的实际类名为准。**实现时先读该文件**,复用它的类,不要新造一套样式。

控制器构造器再补一个依赖:`IdentityProviderStore identityProviderStore`。

- [ ] **Step 5: 放行挑战页路由**

`IamSecurityConfiguration.businessFilterChain` 的 `authorizeHttpRequests` 里加:

```java
                        .requestMatchers("/login-challenge").permitAll()
```

**不要**把 `/login-challenge` 加进 CSRF 豁免列表。挑战表单是同站提交,会话 Cookie 在,CSRF 令牌可用(页面已用 `${_csrf?.parameterName}` 渲染隐藏域);豁免它只会让"一次凭据认证的提交"失去 CSRF 防护,换不来任何东西。

- [ ] **Step 6: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=AdmissionChallengeControllerTest`
Expected: PASS

---

## Task 12: 客户端强制 MFA 的会话挑战

**Files:**
- Modify: `beenest-iam-web/src/main/java/club/beenest/iam/web/controller/AdmissionChallengeController.java`
- Modify: `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/AdmissionChallengeControllerTest.java`(构造参数 +2)
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/AdmissionChallengeMfaTest.java`

**Interfaces:**
- Consumes: `MfaSessionPending` / `MfaPendingService.consumeSession`(Task 9 已实现)、`MfaService.verifyLoginCode`、`IamUserSessionService.upgradeAuthentication`、`AdmissionChallengeService.consume`
- Produces: `AdmissionChallengeController.submitChallenge` 增加 `mfaToken` + `code` 两个参数,MFA 分支成功后重定向到 `challenge.returnTo()`

> 本任务**只做控制器这一侧**。票据的签发与解析(`issueForSession` / `consumeSession` / `MfaSessionPending`)已在 Task 9 完成——Task 9 的过滤器要用它签票据,不能留到这里。

- [ ] **Step 1: 挑战页补 MFA 分支**

> **为什么不再开一个 REST 端点**:挑战页是服务端渲染的表单,浏览器按 `application/x-www-form-urlencoded` 提交;`@RequestBody` 只收 JSON,直接对上会 415。为一次表单提交引入前端 fetch + CSRF 头不值当。因此 MFA 补验走与口令补验同一个 `POST /login-challenge`,控制器按"有没有 mfaToken"分流。

`AdmissionChallengeController` 的构造器再加两个依赖:`IamUserSessionService userSessionService`、`MfaService mfaService`(顺序放在 `mfaPendingService` 之后)。**同时把 Task 11 那个单测的构造调用补上这两个 mock**,否则编译不过。

`submitChallenge` 改为(**保留 Task 11 定下的四点**:票据按 sid 绑定、复用 `AuthService`、`allows()` 拦截、`AuthenticationException` 回落登录页):

```java
    @PostMapping("/login-challenge")
    public String submitChallenge(@RequestParam(value = "realm", defaultValue = "default") String realm,
                                  @RequestParam(value = "token", required = false) String token,
                                  @RequestParam(value = "username", required = false) String username,
                                  @RequestParam(value = "password", required = false) String password,
                                  @RequestParam(value = "mfaToken", required = false) String mfaToken,
                                  @RequestParam(value = "code", required = false) String code,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        // 1. 定位票据(带会话标识,票据与会话绑定)
        String sid = IamSessionCookie.read(request);
        AdmissionChallenge challenge = challengeService.resolve(realm, token, sid);
        if (challenge == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
        }
        // 2. 二步验证补验:票据 + 动态口令
        if (mfaToken != null && !mfaToken.isBlank()) {
            MfaSessionPending pending = mfaPendingService.consumeSession(realm, mfaToken);
            if (pending == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求已过期,请重新发起登录");
            }
            // 二步验证只能升级它自己签发的那个会话:两张票据来自不同会话时,
            // 说明至少有一张不是在本次挑战里产生的,直接拒绝
            if (pending.sid() == null || !pending.sid().equals(challenge.sid())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "验证请求与会话不匹配,请重新发起登录");
            }
            if (!mfaService.verifyLoginCode(realm, pending.userId(), code)) {
                throw new BusinessException(ErrorCode.MFA_CODE_INVALID);
            }
            // 只置位二步验证态:不改第一因素方式,也不动存续时长
            userSessionService.upgradeAuthentication(pending.sid(), null, Boolean.TRUE);
            // 这条分支不经过 establish,票据要在这里自己消费
            challengeService.consume(realm, challenge);
            return "redirect:" + challenge.returnTo();
        }
        // 3. 第一因素补验:缺字段说明不是这个表单提交的
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写账号与口令");
        }
        try {
            // 4. 复用统一认证服务:账号状态、失败计数、权限计算与口令登录完全一致
            IamUserPrincipal principal = authService.authenticate(username, password);
            String loginType = principal.getLoginType();
            // 5. 方式不在白名单里就不升级:establish 在"方式不命中"时会退回新建会话,
            //    票据留着,让用户换一种方式再试
            if (!challenge.allows(loginType)) {
                return "redirect:/login-challenge?realm=" + realm + "&token=" + token;
            }
            // 6. 升级分支内部已消费票据(见 Task 10),成功路径不必再 consume
            loginSessionService.establish(request, response, principal, loginType, token);
            return "redirect:" + challenge.returnTo();
        } catch (MfaRequiredException e) {
            // 账号绑定了 TOTP:签会话级票据,回挑战页的 MFA 形态
            String newMfaToken = mfaPendingService.issueForSession(
                    realm, e.getUserId(), sid, e.getLoginType());
            return "redirect:/login-challenge?realm=" + realm + "&token=" + token + "&mfa=" + newMfaToken;
        } catch (AuthenticationException e) {
            // 账密错误、账号锁定、验证码要求都交给登录页:它按失败计数展示验证码并给出提示。
            // 登录成功后按 sid 重新找到本票据并升级会话,流程不丢
            String returnTo = UriUtils.encodeQueryParam(challenge.returnTo(), StandardCharsets.UTF_8);
            return "redirect:/login?realm=" + realm + "&return_to=" + returnTo;
        }
    }
```

- [ ] **Step 2: 补 MFA 分支的控制器用例**

Create `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/AdmissionChallengeMfaTest.java`:

```java
package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.AdmissionChallenge;
import club.beenest.iam.authz.admission.AdmissionChallengeService;
import club.beenest.iam.authz.mfa.MfaPendingService;
import club.beenest.iam.authz.mfa.MfaSessionPending;
import club.beenest.iam.authz.mfa.MfaService;
import club.beenest.iam.authz.session.IamUserSessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话级 MFA 补验测试。
 * <p>补验成功的标志是同一个 sid 被置上二步验证完成态,且票据被消费;
 * 断言"没新建会话、没改第一因素"才能防止实现退回成一次普通登录。</p>
 */
class AdmissionChallengeMfaTest {

    @Test
    @DisplayName("动态口令正确时升级原会话并消费票据")
    void upgradesSessionOnValidCode() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of(), true,
                "/realms/default/oauth2/authorize", "tok", 1L);
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-1", "PASSWORD"));
        when(mfaService.verifyLoginCode("default", 7L, "123456")).thenReturn(true);

        AdmissionChallengeController controller = new AdmissionChallengeController(
                challengeService, mock(club.beenest.iam.authz.admission.LoginMethodCatalog.class),
                mock(club.beenest.iam.core.spi.provider.RealmProvider.class),
                mock(club.beenest.iam.web.config.IamThemeSelector.class),
                mock(club.beenest.iam.authz.service.AuthService.class),
                mock(club.beenest.iam.authz.session.IamLoginSessionService.class),
                mfaPendingService, mock(club.beenest.iam.core.spi.provider.IdentityProviderStore.class),
                userSessionService, mfaService);

        String view = controller.submitChallenge("default", "tok", null, null,
                "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse());

        assertEquals("redirect:/realms/default/oauth2/authorize", view);
        verify(userSessionService).upgradeAuthentication("sid-1", null, Boolean.TRUE);
        verify(challengeService).consume("default", challenge);
    }

    @Test
    @DisplayName("MFA 票据属于另一个会话时拒绝,不升级任何会话")
    void rejectsTicketFromAnotherSession() {
        AdmissionChallengeService challengeService = mock(AdmissionChallengeService.class);
        MfaPendingService mfaPendingService = mock(MfaPendingService.class);
        MfaService mfaService = mock(MfaService.class);
        IamUserSessionService userSessionService = mock(IamUserSessionService.class);
        AdmissionChallenge challenge = new AdmissionChallenge(
                "sid-1", "app", Set.of(), true,
                "/realms/default/oauth2/authorize", "tok", 1L);
        when(challengeService.resolve("default", "tok", null)).thenReturn(challenge);
        when(mfaPendingService.consumeSession("default", "mfa-1"))
                .thenReturn(new MfaSessionPending(7L, "sid-2", "PASSWORD"));

        AdmissionChallengeController controller = new AdmissionChallengeController(
                challengeService, mock(club.beenest.iam.authz.admission.LoginMethodCatalog.class),
                mock(club.beenest.iam.core.spi.provider.RealmProvider.class),
                mock(club.beenest.iam.web.config.IamThemeSelector.class),
                mock(club.beenest.iam.authz.service.AuthService.class),
                mock(club.beenest.iam.authz.session.IamLoginSessionService.class),
                mfaPendingService, mock(club.beenest.iam.core.spi.provider.IdentityProviderStore.class),
                userSessionService, mfaService);

        org.junit.jupiter.api.Assertions.assertThrows(
                club.beenest.iam.common.exception.BusinessException.class,
                () -> controller.submitChallenge("default", "tok", null, null,
                        "mfa-1", "123456", new MockHttpServletRequest(), new MockHttpServletResponse()));
        verify(userSessionService, never()).upgradeAuthentication(any(), any(), any());
    }
}
```

> `MfaService` 在哪个包、`verifyLoginCode` 的签名以现有代码为准(参考 `AuthController.loginMfa` 的调用)。若 `MfaService` 是具体类而非接口,`mock` 同样可用。

- [ ] **Step 3: `IamSessionPayload.withAuthentication` 的 null 语义**

`upgradeAuthentication(sid, null, TRUE)` 传 `null` 表示"不改第一因素方式"。**必须保证 `withAuthentication` 在这时保留原主体**:

```java
    public IamSessionPayload withAuthentication(String loginType, Boolean mfaVerified) {
        IamUserPrincipal upgradedPrincipal = principal;
        if (principal != null && loginType != null) {
            upgradedPrincipal = new IamUserPrincipal(
                    principal.getUserId(), principal.getRealmId(), principal.getRealmKey(),
                    principal.getUsername(), principal.getNickname(), principal.getTokenVersion(),
                    principal.getAuthorities(), principal.getAttributes(),
                    principal.getPermissionEpoch(), loginType);
        }
        return new IamSessionPayload(upgradedPrincipal, createdTime, maxLifetimeSeconds,
                idleSeconds, lastTouchMillis, mfaVerified);
    }
```

（Task 5 已按此实现;本步骤确认它与会话级 MFA 的用法一致,若已一致则跳过。）

- [ ] **Step 4: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest='AdmissionChallengeMfaTest,MfaPendingServiceSessionTest'`
Expected: PASS

---

## Task 13: 联邦挑战上下文传递

**Files:**
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/FederationStatePayload.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/IamFederationController.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/authz/web/FederationChallengeContextTest.java`

**Interfaces:**
- Consumes: `IamLoginSessionService.establish(..., challengeToken)`、`AdmissionChallengeService.resolve`
- Produces:
  - `FederationStatePayload(String realmKey, String alias, String challengeToken, String target)`(包级 record)
  - `FederationStatePayload.encode()` → `String`;`FederationStatePayload.decode(String)` → `FederationStatePayload`
  - state 载荷格式扩为 `realmKey|alias|challengeToken|target`(令牌段可为空,但分隔符保留;目标地址在末段,可含 `|`)

- [ ] **Step 1: 写载荷单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/authz/web/FederationChallengeContextTest.java`
(包名 `club.beenest.iam.authz.web`,与 `FederationStatePayload` 同包才能访问包级类型):

```java
package club.beenest.iam.authz.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 联邦 state 载荷格式测试。
 * <p>载荷从三段扩到四段:{@code realmKey|alias|challengeToken|target}。
 * 联邦回跳时浏览器未必带得回会话 Cookie(SAML 走跨站 POST),挑战令牌是那时唯一的定位线索。</p>
 * <p>令牌放在**第三段**而不是末段:目标地址是唯一可能含分隔符的部分,
 * 让它占据最后一段(split 有上限),地址里出现 "|" 也不会把令牌切碎。</p>
 * <p>老格式(三段)必须仍能解析,否则上线即打断存量三方登录。</p>
 */
class FederationChallengeContextTest {

    @Test
    @DisplayName("四段载荷往返不丢挑战令牌,目标地址含分隔符也不受影响")
    void roundTripsChallengeToken() {
        String encoded = new FederationStatePayload("default", "wx",
                "tok-123", "/portal?tab=a|b").encode();

        FederationStatePayload decoded = FederationStatePayload.decode(encoded);

        assertEquals("default", decoded.realmKey());
        assertEquals("wx", decoded.alias());
        assertEquals("/portal?tab=a|b", decoded.target());
        assertEquals("tok-123", decoded.challengeToken());
    }

    @Test
    @DisplayName("三段老载荷仍可解析,挑战令牌为空")
    void parsesLegacyPayload() {
        FederationStatePayload decoded =
                FederationStatePayload.decode("default|wx|https://app.example.com/cb");

        assertEquals("https://app.example.com/cb", decoded.target());
        assertNull(decoded.challengeToken());
    }

    @Test
    @DisplayName("第三段为空串表示无挑战,解析为 null 而不是空串")
    void emptyTokenPartMeansNoChallenge() {
        // 拼接时无挑战会留一个空段;若这里返回 "" 而不是 null,
        // 回跳侧会拿空令牌去查票据,永远查不到
        FederationStatePayload decoded = FederationStatePayload.decode("default|wx||/portal");

        assertEquals("/portal", decoded.target());
        assertNull(decoded.challengeToken());
    }

    @Test
    @DisplayName("段数不足时拒绝解析")
    void rejectsMalformedPayload() {
        assertThrows(IllegalArgumentException.class, () -> FederationStatePayload.decode("default|wx"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=FederationChallengeContextTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL —— 找不到符号 `FederationStatePayload`

- [ ] **Step 3: 写 FederationStatePayload**

Create `beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/FederationStatePayload.java`:

```java
package club.beenest.iam.authz.web;

/**
 * 三方登录 state 的载荷。
 * <p>四段:{@code realmKey|alias|challengeToken|target}。挑战令牌在无挑战时为空,
 * 但分隔符必须保留——否则回跳侧解析时会把 target 当成第三段。</p>
 * <p>令牌放第三段、目标地址放末段:目标地址是唯一可能含分隔符的部分,
 * 放在最后一段(split 有上限)时,地址里的 "|" 不会把令牌切碎。</p>
 * <p>三段老载荷仍能解析(挑战令牌为 null):上线不能打断存量三方登录。</p>
 */
record FederationStatePayload(String realmKey, String alias, String challengeToken, String target) {

    /** 载荷分隔符 */
    static final String SEPARATOR = "|";

    /** 拼成 state 载荷。 */
    String encode() {
        return String.join(SEPARATOR, realmKey, alias,
                challengeToken == null ? "" : challengeToken, target);
    }

    /**
     * 解析 state 载荷。
     * <p>段数决定形态:三段是老格式(无令牌),四段是新格式。
     * 老格式的 target 里若含分隔符会被多切出一段,那种载荷只可能来自上线前的
     * 在途请求(存活期不超过 state TTL),误读后取到的是被切碎的目标地址,
     * 回跳侧还会再过一次 {@code safeTarget},不会变成开放重定向。</p>
     *
     * @throws IllegalArgumentException 段数不足(说明 payload 被篡改或来自未知版本)
     */
    static FederationStatePayload decode(String payload) {
        String[] parts = payload.split("\\" + SEPARATOR, 4);
        if (parts.length < 3) {
            throw new IllegalArgumentException("state 载荷格式非法");
        }
        if (parts.length == 3) {
            // 老格式:realmKey|alias|target
            return new FederationStatePayload(parts[0], parts[1], null, parts[2]);
        }
        String challengeToken = parts[2].isBlank() ? null : parts[2];
        return new FederationStatePayload(parts[0], parts[1], challengeToken, parts[3]);
    }
}
```

- [ ] **Step 4: 控制器改用载荷类型**

`IamFederationController`:

1. 注入 `AdmissionChallengeService challengeService`(构造器补一个参数)。
2. 发起处替换原 `String.join` 拼接:

```java
        // 载荷:realmKey|alias|challengeToken|target(目标地址放末段,可含分隔符)
        String state = stateService.issue(new FederationStatePayload(
                realmKey, alias, challengeToken, safeTarget(target)).encode());
```

3. 回跳处替换原 `payload.split`。**目标地址要再校验一次**:签发处校验过,但这里拿到的是经过 Redis 往返的值,回跳前再跑一遍 `safeTarget` 才是闭环:

```java
        FederationStatePayload state_ = FederationStatePayload.decode(payload);
        if (!realmKey.equals(state_.realmKey()) || !alias.equals(state_.alias())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录请求与身份提供方不匹配");
        }
        String target = safeTarget(state_.target());
        String challengeToken = state_.challengeToken();
```

4. **挑战票据要在 `establish` 之前读**:`establish` 在升级分支里会消费票据,
   之后 `resolve` 必然返回 null,`returnTo` 就丢了。

```java
        // 先取回跳地址:establish 的升级分支会消费票据,之后再 resolve 只能拿到 null
        AdmissionChallenge challenge = challengeToken == null
                ? null : challengeService.resolve(realmKey, challengeToken, null);
        String challengeReturnTo = challenge == null ? null : challenge.returnTo();
        boolean upgraded = loginSessionService.establish(request, response, principal,
                loginTypeOf(realm, alias), challengeToken);
        // 补验成功:回到客户端准入要求的授权端点,而不是发起时的目标
        String finalTarget = upgraded && challengeReturnTo != null ? challengeReturnTo : target;
        response.sendRedirect(finalTarget);
```

- [ ] **Step 5: 发起入口收下挑战令牌**

挑战页的联邦链接形如 `/broker/{realmKey}/{alias}/login?challengeToken=…`(Task 11 的模板已按此渲染)。现有的发起方法只多一个参数,不是新方法:

```java
    @GetMapping("/broker/{realmKey}/{alias}/login")
    public void login(@PathVariable String realmKey,
                      @PathVariable String alias,
                      @RequestParam(required = false) String target,
                      @RequestParam(required = false) String challengeToken,
                      HttpServletRequest request,
                      HttpServletResponse response) throws java.io.IOException {
```

`challengeToken` 直接写进 state 载荷(见 Step 4 第 2 条)。它不需要在这里校验:回跳时 `resolve(realmKey, token, sid)` 会判令牌与会话是否匹配,查不到的令牌等同于没有挑战。

- [ ] **Step 6: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=FederationChallengeContextTest`
Expected: PASS。随后再跑一次全量 `./mvnw -o -q test -pl beenest-iam-server -am`(先确认没有别的 Maven 进程)。

---

## Task 14: 登录页与提供方入口的展示过滤

**Files:**
- Modify: `beenest-iam-web/src/main/java/club/beenest/iam/web/controller/LoginPageController.java`
- Modify: `beenest-iam-web/src/main/resources/themes/nestid/login/login.html`(页面 JS 拉 `/idps` 时要带上 `client_id`)
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/IamAuthenticationEntryPoint.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/AuthController.java`( `/idps` 过滤)
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/LoginPageControllerAdmissionTest.java`

**Interfaces:**
- Consumes: `LoginMethodCatalog`、`ClientProvider`、`IamRegisteredClientRepository.policyOf`
- Produces:
  - `LoginPageController` 视图模型新增 `clientId` / `allowedMethods` / `passwordLoginVisible`;`smsLoginEnabled` 改为与白名单取交集
  - `GET /login` 接受 `client_id` 参数
  - `GET /api/v1/auth/idps` 接受可选 `client_id`,按白名单过滤
- **注意**:提供方入口是**页面 JS 拉 `/api/v1/auth/idps` 渲染的**(`login.html:371-373`),不是服务端 `th:each`。所以过滤的落点在 `/idps` 端点,`LoginPageController` **不需要**往模型里放 `idpEntries`(放了也没人读);模板侧只需让这段 fetch 带上 `client_id`。

- [ ] **Step 1: 写单测**

Create `beenest-iam-server/src/test/java/club/beenest/iam/web/controller/LoginPageControllerAdmissionTest.java`:

```java
package club.beenest.iam.web.controller;

import club.beenest.iam.authz.admission.LoginMethod;
import club.beenest.iam.authz.admission.LoginMethodCatalog;
import club.beenest.iam.authz.client.IamRegisteredClientRepository;
import club.beenest.iam.common.web.IamOneTimeMessageService;
import club.beenest.iam.core.model.ClientModel;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.spi.provider.ClientProvider;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.persistence.manager.IamSecurityPolicyManager;
import club.beenest.iam.web.config.IamThemeSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.ui.Model;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录页展示过滤测试。
 * <p>白名单不含 PASSWORD 时必须隐藏口令表单:留着表单让用户输完密码
 * 再被弹走,比一开始就不给入口更糟。</p>
 * <p>用例里没有设置 RealmContext:仓储的 {@code currentRealm()} 在无上下文时
 * 回落 default 域,而本用例正好 stub 了 default 域,所以映射路径能跑通。</p>
 */
class LoginPageControllerAdmissionTest {

    @Test
    @DisplayName("白名单不含口令时隐藏口令表单")
    void hidesPasswordFormWhenNotAllowed() {
        IamOneTimeMessageService messageService = mock(IamOneTimeMessageService.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        ClientProvider clientProvider = mock(ClientProvider.class);

        RealmModel realm = new RealmModel(1L, "default", "默认域", true, false,
                300L, 1800L, 1800L, 36000L, null, null);
        when(realmProvider.findByRealmKey("default")).thenReturn(Optional.of(realm));
        when(themeSelector.view(anyString())).thenReturn("login/login");
        when(catalog.availableInRealm(1L, "default")).thenReturn(Set.of(LoginMethod.SMS));
        // 客户端白名单只收短信:口令表单必须隐藏,否则用户输对了密码也会被弹走
        when(clientProvider.findByClientKey(1L, "app")).thenReturn(Optional.of(clientWithWhitelist()));

        RegisteredClientRepository repository = new IamRegisteredClientRepository(
                clientProvider, realmProvider);
        LoginPageController controller = new LoginPageController(
                messageService, themeSelector, realmProvider, policyManager, catalog, repository);
        Model model = mock(Model.class);

        controller.loginPage("default", null, "app", null, null, null, model, null);

        verify(model).addAttribute("passwordLoginVisible", false);
    }

    @Test
    @DisplayName("没有客户端上下文时不过滤,口令表单照常展示")
    void showsPasswordFormWithoutClientContext() {
        IamOneTimeMessageService messageService = mock(IamOneTimeMessageService.class);
        IamThemeSelector themeSelector = mock(IamThemeSelector.class);
        RealmProvider realmProvider = mock(RealmProvider.class);
        IamSecurityPolicyManager policyManager = mock(IamSecurityPolicyManager.class);
        LoginMethodCatalog catalog = mock(LoginMethodCatalog.class);
        when(themeSelector.view(anyString())).thenReturn("login/login");

        LoginPageController controller = new LoginPageController(
                messageService, themeSelector, realmProvider, policyManager, catalog,
                mock(RegisteredClientRepository.class));
        Model model = mock(Model.class);

        controller.loginPage("default", null, null, null, null, null, model, null);

        // 门户直达没有 client_id:此时不限制,口令表单是唯一保底入口
        verify(model).addAttribute("passwordLoginVisible", true);
    }

    /** 构造一个白名单只含 SMS 的客户端模型。 */
    private static ClientModel clientWithWhitelist() {
        return new ClientModel(
                1L, 1L, "app", "应用", ClientModel.ClientType.PUBLIC, null,
                Set.of("https://app.example.com/cb"), Set.of(), null, true,
                Set.of("openid"), true, false, true, null, null, null,
                Set.of("authorization_code"), Set.of("SMS"), false, null);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=LoginPageControllerAdmissionTest`
Expected: FAIL —— 构造器不匹配

- [ ] **Step 3: 登录页加 client_id 与过滤**

`LoginPageController` 的 `loginPage` 增加 `client_id` 参数,并计算可展示集合:

```java
    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "realm", defaultValue = "default") String realm,
                            @RequestParam(value = "return_to", required = false) String returnTo,
                            @RequestParam(value = "client_id", required = false) String clientId,
                            @RequestParam(value = "error", required = false) String error,
                            @RequestParam(value = "username", required = false) String username,
                            @RequestParam(value = IamWebConstants.LOGIN_ERROR_TICKET_PARAM,
                                    required = false) String errToken,
                            Model model,
                            CsrfToken csrfToken) {
        ...
        // 准入过滤:客户端声明了白名单时,只展示它接受的方式
        Set<String> allowed = resolveAllowedMethods(realm, clientId);
        model.addAttribute("clientId", clientId == null ? "" : clientId);
        model.addAttribute("allowedMethods", allowed);
        // 白名单为空表示不限制,口令表单照常展示
        model.addAttribute("passwordLoginVisible",
                allowed.isEmpty() || allowed.contains(LoginMethod.PASSWORD.code())
                        || allowed.contains(LoginMethod.LDAP.code()));
        // 短信 Tab:域级开启 且 客户端允许。
        // 域级判定沿用现有的 isSmsLoginEnabled(realm),它自己处理了策略缺失时的回落;
        // 不要在上游另取一次 LoginPolicy——那个变量在方法里并不存在
        model.addAttribute("smsLoginEnabled",
                isSmsLoginEnabled(realm)
                        && (allowed.isEmpty() || allowed.contains(LoginMethod.SMS.code())));
        ...
    }
```

新增私有方法:

```java
    /**
     * 当前客户端允许展示的登录方式。
     * <p>没有客户端上下文(门户直达)或客户端未配置白名单时返回空集合,
     * 空集合在调用方一律解释为"不限制"。</p>
     */
    private Set<String> resolveAllowedMethods(String realmKey, String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Set.of();
        }
        RegisteredClient registered = registeredClientRepository.findByClientId(clientId);
        if (registered == null) {
            return Set.of();
        }
        return IamRegisteredClientRepository.policyOf(registered).allowedMethodCodes();
    }
```

> `IamRegisteredClientRepository.findByClientId` 依赖 `RealmContextHolder`。登录页走业务链,`RealmContextFilter` 已在该链上(`addFilterBefore(..., AuthorizationFilter.class)`),且它解析域时**回落到 `realm` 请求参数**,`/login?realm=default` 因此有域上下文——不需要另写查库路径。

- [ ] **Step 4: 入口点带 client_id**

`IamAuthenticationEntryPoint` 已有 `UriComponentsBuilder target`,**不要改成字符串拼接**(现有注释解释了为什么不用 `UriComponentsBuilder` 去解析回跳串,那是另一回事;这里只是往登录页 URL 再加一个参数):在 `target.queryParam("return_to", returnTo)` 之后追加

```java
        // 目标客户端的准入策略决定登录页展示哪些方式:入口点是唯一还能拿到
        // 原始请求参数的位置,回跳时 client_id 早已被编码进 return_to 里
        String clientId = request.getParameter("client_id");
        if (clientId != null && !clientId.isBlank()) {
            target.queryParam("client_id", clientId);
        }
```

`build().encode()` 会负责转义。

> **不从 `return_to` 里解析 client_id**:`return_to` 是站内相对路径,解析它等于把登录页的入参面扩大;`client_id` 由平台自己在入口点解析,来源可控。

- [ ] **Step 5: 提供方入口按客户端过滤**

`AuthController` 的 `/idps` 增加可选 `client_id` 参数,按白名单过滤返回的提供方:

```java
    @GetMapping("/idps")
    public Result<List<IdentityProviderEntry>> identityProviders(
            @RequestParam(value = "client_id", required = false) String clientId) {
        RealmContext context = RealmContextHolder.get();
        Long realmId = context == null ? null : context.realm().id();
        if (realmId == null) {
            return Result.success(List.of());
        }
        // 客户端声明白名单时只留下它接受的提供方;白名单为空(或客户端查不到)按不限制处理,
        // 与登录页"空集合=不限制"的口径保持一致
        Set<LoginMethod> allowed = allowedMethodsOf(clientId);
        List<IdentityProviderEntry> entries = identityProviderStore.listByRealm(realmId).stream()
                .filter(model -> model.enabled())
                .filter(model -> allowed.isEmpty()
                        || allowed.contains(LoginMethodCatalog.toLoginMethod(model.providerId())))
                .map(model -> new IdentityProviderEntry(
                        model.alias(), model.displayName(), model.providerId(),
                        "/broker/" + context.realmKey() + "/" + model.alias() + "/login",
                        identityProviderRegistry.supportsRedirect(model.providerId())))
                .toList();
        return Result.success(entries);
    }
```

配套私有方法(与登录页共用同一判定来源):

```java
    /** 客户端允许的第一因素;无 client_id、客户端不存在或未配白名单时返回空集合(=不限制)。 */
    private Set<LoginMethod> allowedMethodsOf(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Set.of();
        }
        var registered = realmClientRepository.findByClientId(clientId);
        return registered == null
                ? Set.of() : IamRegisteredClientRepository.policyOf(registered).allowedMethods();
    }
```

（`AuthController` 注入现有的 `IamRegisteredClientRepository`(`@Component`,可直接按类型注入)。`findByClientId` 内部按 `RealmContextHolder` 取域,与本方法拿到的是同一个上下文。）

- [ ] **Step 5b: 登录页的 fetch 带上 client_id**

`login.html:255` 已经有 `var realmKey = /*[[${realmKey}]]*/ 'default';` 的写法,照此加一行:

```js
        var clientId = /*[[${clientId}]]*/ '';
```

再把 `login.html:373` 的 fetch 改为:

```js
            fetch('/api/v1/auth/idps?realm=' + encodeURIComponent(realmKey)
                + (clientId ? '&client_id=' + encodeURIComponent(clientId) : ''),
                {credentials: 'same-origin'})
```

`client_id` 由 `LoginPageController` 放进模型(`clientId`),**不要**从 `return_to` 里解析。

- [ ] **Step 6: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=LoginPageControllerAdmissionTest`
Expected: PASS

---

## Task 15: 集成测试

**Files:**
- Create: `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamClientAdmissionIntegrationTest.java`

**Interfaces:**
- Consumes: 全部前置任务的产出
- Produces: 端到端回归保护

- [ ] **Step 1: 写集成测试**

Create `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamClientAdmissionIntegrationTest.java`。
容器声明与 `login()` / `cookieHeaders()` / `queryParam()` 等辅助方法**照搬** `IamOidcFlowIntegrationTest`
(该类同目录、同基类;Testcontainers 的容器必须在每个测试类里各自声明,基类代劳会被提前停掉)。
与它不同的只有两点:建客户端时要能带准入字段,取授权端点响应时要看 Location 而不假定一定有授权码。

```java
package club.beenest.iam.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端准入策略的端到端回归。
 * <p>单测覆盖得了判定分支,覆盖不了"过滤器有没有真的排在授权端点之前"——
 * 那类问题只会表现为"策略配了但没生效"。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamClientAdmissionIntegrationTest extends IamIntegrationTestBase {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("iam.realm-key.master-secret", () -> "integration-test-master-secret");
        registry.add("iam.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("iam.bootstrap.enabled", () -> "true");
    }

    @Test
    @DisplayName("白名单命中时照常走授权码流程")
    void allowsMatchingMethod() throws Exception {
        TestSession session = login();
        createClient(session, "admit-ok", Map.of("allowedLoginTypes", List.of("PASSWORD")));

        String location = authorizeLocation(session, "admit-ok");

        assertTrue(location.contains("code="), "口令登录 + 只允许 PASSWORD:应当直接发放授权码: " + location);
    }

    @Test
    @DisplayName("白名单不匹配时转补验页,而不是直接失败")
    void challengesOnMismatch() throws Exception {
        TestSession session = login();
        createClient(session, "admit-sms", Map.of("allowedLoginTypes", List.of("SMS")));

        String location = authorizeLocation(session, "admit-sms");

        assertTrue(location.contains("/login-challenge"), "应当跳补验页: " + location);
        assertTrue(location.contains("token="), "应当携带挑战令牌: " + location);
        assertFalse(location.contains("code="), "不得在补验前发放授权码: " + location);
    }

    @Test
    @DisplayName("强制 MFA 的客户端在会话未完成二步验证时要求补验")
    void challengesMfa() throws Exception {
        TestSession session = login();
        createClient(session, "admit-mfa", Map.of("requireMfa", true));

        String location = authorizeLocation(session, "admit-mfa");

        assertTrue(location.contains("/login-challenge"), "应当跳补验页: " + location);
        assertTrue(location.contains("mfa="), "MFA 挑战要同时签会话级票据: " + location);
    }

    @Test
    @DisplayName("没有会话时不发起挑战,交给登录页")
    void anonymousIsLeftToLoginPage() throws Exception {
        TestSession session = login();
        createClient(session, "admit-anon", Map.of("allowedLoginTypes", List.of("SMS")));
        // 不带 Cookie 访问:没有会话就没有"当前认证方式",准入判定直接放行,
        // 由登录页按策略展示过滤——在授权端点上拦匿名用户会把人踢进死循环
        String location = authorizeLocation(null, "admit-anon");

        assertFalse(location.contains("/login-challenge"), "匿名访问不应进入补验页: " + location);
    }

    /** 带准入字段登记机密客户端。 */
    private void createClient(TestSession session, String clientKey, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientKey", clientKey);
        body.put("clientName", "准入集成测试客户端");
        body.put("clientType", "CONFIDENTIAL");
        body.put("redirectUris", List.of("http://127.0.0.1:9999/cb"));
        body.put("scopes", List.of("openid", "profile"));
        body.putAll(extra);
        ResponseEntity<String> response = rest.exchange("/api/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders(session)), String.class);
        assertEquals(200, response.getStatusCode().value(), "登记客户端应当成功: " + response.getBody());
    }

    /** 访问授权端点,返回 Location(不假定一定带授权码)。 */
    private String authorizeLocation(TestSession session, String clientKey) {
        URI uri = UriComponentsBuilder.fromPath("/realms/default/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientKey)
                .queryParam("scope", "openid profile")
                .queryParam("redirect_uri", "http://127.0.0.1:9999/cb")
                .queryParam("state", "it-state")
                .build().encode().toUri();
        HttpHeaders headers = session == null ? new HttpHeaders() : cookieHeaders(session);
        ResponseEntity<String> response = rest.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(null, headers), String.class);
        assertEquals(302, response.getStatusCode().value(),
                "授权端点应当重定向: " + response.getStatusCode() + " " + response.getBody());
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "重定向必须带 Location");
        return location;
    }
}
```

> `TestSession` / `login()` / `jsonHeaders(TestSession)` / `cookieHeaders(TestSession)` 从 `IamOidcFlowIntegrationTest`
> 复制过来(它们是私有辅助方法,不能直接复用);若想避免重复,可把它们提到 `IamIntegrationTestBase`——
> 那会动到既有测试,超出本任务范围,**先复制**。

- [ ] **Step 2: 跑测试**

Run: `./mvnw -o -q test -pl beenest-iam-server -am -Dtest=IamClientAdmissionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。若 `challengesOnMismatch` 没跳转,先查 `AdmissionFilterOrderTest`——多半是过滤器次序排错。

- [ ] **Step 3: 全量回归**

Run: `./mvnw -o clean test`
Expected: 全部测试通过,0 失败

---

## Task 16: 文档债清理

**Files:**
- Modify: `readme.md`
- Modify: `docs/keycloak-comparison.html`

**Interfaces:**
- Consumes: 无
- Produces: 与仓库现状一致的对外材料

- [ ] **Step 1: 修 readme 的已知能力边界表**

`readme.md` 的「已知能力边界」表里,删除这两行(两项能力已落地):

```
| 组织架构(部门/岗位/汇报线) | 用户组的层级只表达**归属与归类**,不承载岗位、职级、汇报关系;也没有"按岗位授权" | 按部门/岗位授权只能靠建组 + 挂角色近似 |
| API 文档(OpenAPI) | 无;接入方以 `docs/client-integration.md` 为准 | 客户端联调依赖人工阅读文档 |
```

并在「企业级能力」表里补一行:

```
| **客户端准入策略** | 客户端可声明**允许的第一因素集合**(账号口令 / 域目录 LDAP / 短信 / 微信 / 外部 OIDC / 外部 SAML)与**是否强制 MFA**;授权端点按目标客户端判定,不满足时转补验页(用被接受的方式再证明一次),补验成功**升级原会话**而非新建;白名单与域级可用方式无交集时管理端拒绝保存 |
```

- [ ] **Step 2: 更新对比文档的 P0/P1 状态**

`docs/keycloak-comparison.html`:

1. 执行摘要的第 2 条,把已落地项从"最痛的 5 项差距"里移出,并改写为"当前剩余的阻断级差距"
2. 第三节的 P0 卡片:删除 LDAP/AD、短信验证码、租户级品牌主题、OpenAPI 四张(已落地),保留 SAML 并把标题改为「SAML 2.0 IdP 侧签发断言」,同时把落地方案从 `spring-security-saml2-service-provider` 改为 OpenSAML 直接构造断言(SP 侧已实现,IdP 侧仍缺)
3. 第三节的 P1 卡片:删除「组织架构(部门/岗位/汇报线)」(已落地)
4. 第二节矩阵里对应的行改状态:LDAP/AD 用户联合、短信/邮件 OTP、租户品牌/主题、多语言之外的 OpenAPI 行
5. 头部基线信息里的测试数改为**实测值**:先跑 `grep -rho '@Test' --include=*.java beenest-iam-*/src/test | wc -l`(本轮结束时约 260),并注明该数字是仓库实际 `@Test` 数量、非 surefire 计数

- [ ] **Step 3: 校验文档仍可渲染**

Run: `python3 -c "import html.parser,sys; p=html.parser.HTMLParser(); p.feed(open('docs/keycloak-comparison.html',encoding='utf-8').read()); print('ok')"`
Expected: 输出 `ok`(标签闭合无误)

- [ ] **Step 4: 全量回归**

Run: `./mvnw -o clean test && cd beenest-iam-web/frontend && npm test && npm run build`
Expected: 全部通过

---

## 附:自检清单(每个 Task 收尾时对照)

- [ ] `./mvnw -o -q test -pl beenest-iam-server -am` 全绿
- [ ] 新增/修改的类都有中文类级 Javadoc,说明了"为什么"而非"是什么"
- [ ] 没有新增 Maven 依赖
- [ ] 新增的实体/模型字段用包装类型
- [ ] Redis key 都经 `RedisUtils`,常量都进 `IamRedisKeyConstants`
- [ ] 涉及策略变更的操作有 `@LogAudit`
- [ ] 涉及登录行为的操作有登录事件
- [ ] 对照「Review Focus」五条确认本次改动没有引入对应的漏洞
