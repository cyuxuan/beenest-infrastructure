# 微信登录（小程序 + 扫码登录 · 热插拔）实施计划

> **实施记录（2026-10-22）**：本计划在实施中经过三轮架构调整，最终形态与初稿有实质差异。**以规格文档 `docs/superpowers/specs/2026-10-21-wechat-login-design.md` 为准**，逐条裁决与原因见 `.superpowers/sdd/2026-10-21-wechat-login/progress.md`。
>
> 与初稿的三处关键差异：
> 1. **新增外置扩展包装载**（用户要求：第三方 jar 丢进目录即扩展，不必改 Maven 依赖、不必重新打包服务端）→ 新增 `ExternalProviderLoader` 与 `iam.provider.dir` 配置。
> 2. **SPI 去掉写死的场景**（用户要求：联邦架构不应强绑定「小程序」，应由 provider 自己定义）→ 删除 `MiniProgramIdentityProvider`，改为 `ProviderLoginMode` 形态声明 + 通用凭据 map。
> 3. **凭据类登录改为「平台签授权码、令牌交回协议栈签发」**（自签令牌的路与协议栈内部耦合过深，ID 令牌声明在授权存储投影里会丢）→ 新增 `POST /api/v1/auth/provider-code`，之后走标准 `authorization_code` 流程。
>
> 另有一处**实现期发现的平台缺陷**：容器里凡实现 Spring `AuthenticationConverter` 的 bean，都可能被资源服务器挑去当 Bearer 令牌的提取器，导致所有 Bearer 请求 401。扩展包与平台新增此类 bean 时必须避开该接口。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付微信登录扩展包：小程序登录（自动注册用户并记录 openid，签发标准令牌）与微信开放平台扫码登录，并把提供方类型元数据打通到管理端动态表单，实现真正的热插拔。

**Architecture:** 新建独立扩展包 `beenest-iam-provider-wechat`（只依赖 federation + core），注册 `wechat-mini`（小程序类，走 `MiniProgramIdentityProvider`）与 `wechat-web`（浏览器跳转类，走 `OAuth2IdentityProvider`）两个提供方类型。扫码登录完全复用既有 `/broker` 跳转链路；小程序登录新增平台级端点 `POST /api/v1/auth/login/mini-program`，完成客户端认证 → 联邦换身份 → 自动注册 → 签发访问/刷新令牌（复用平台的 `JwtGenerator` 与 `RedisOAuth2AuthorizationService`）。提供方类型元数据由 `IdentityProviderRegistry` 统一暴露，经管理端接口下发给控制台，动态渲染类型下拉与配置表单。

**Tech Stack:** Java 21、Spring Boot 4.0.8、Spring Security 7.0.7、Spring Authorization Server 7.0.7、Redis 7、PostgreSQL 17、MyBatis、JUnit 5 + Testcontainers、Vue 3 + TypeScript（控制台）。

**Spec:** `docs/superpowers/specs/2026-10-21-wechat-login-design.md`

## Global Constraints

- 注释一律中文业务语义，禁止出现「对齐 XX 框架」「类似 XX 的设计」等开源项目引用与 AI 味表述；公共方法写 Javadoc，复杂逻辑用编号步骤注释（`// 1. …`）。
- 依赖方向不得反向：扩展包 → federation → core；扩展包**不得**依赖 persistence。控制台在 `beenest-iam-web/frontend`，不在 `beenest-iam-console`。
- **多实例约束**：任何跨请求状态一律放 Redis；不得依赖进程内存或会话亲和。小程序登录不产生服务端会话，刷新与吊销依赖 Redis 授权存储，任意实例都能服务。
- 机密配置：管理端写入时加密落库、读取时解密、响应只回键名。机密键判定规则见 Task 2。
- 第三方调用失败统一归一为 `ErrorCode.IDP_UNAVAILABLE`（HTTP 502），不得冒泡成 500。
- 新增对外端点必须显式加入 `IamSecurityConfiguration` 的放行名单，未放行则一律要求已认证。
- 现有测试全绿是每一步的前提：`./mvnw -q clean test`。
- 扩展包引入方式：`beenest-iam-server/pom.xml` 加依赖即启用，移除即下线；父 pom 需同时登记 `<module>` 与 `<dependencyManagement>` 条目。

## Review Focus

以下五类输入/状态最容易被忽略，每个都要有对应的测试钉住：

1. **微信 code2session 返回 errcode（如 40029 code 已使用、40163 code been used）** —— 必须归一为「上游不可用/凭据失效」，不能当成 500，也不能把 `errcode` 原文当身份解析。
2. **unionid 缺失**（小程序未绑定开放平台账号）—— 外部标识必须回落到 openid，且不得因为 unionid 缺失而建出空账号。
3. **开放平台扫码授权地址的微信专有约束** —— `appid` 必须在首位、地址必须以 `#wechat_redirect` 结尾、否则微信不渲染二维码。
4. **小程序登录时客户端密钥错误** —— 必须拒绝且不泄露「客户端是否存在」以外的信息；失败不得消耗掉用户的 jsCode（重试语义）。
5. **openid 是否真的落到了用户记录里** —— 用户提出的硬要求，属性必须出现在 `iam_user.attributes` 中，而不只是绑定表里。

---

### Task 1: 提供方类型元数据（core + federation + admin）

**Files:**
- Modify: `beenest-iam-core/src/main/java/club/beenest/iam/core/spi/federation/IdentityProviderFactory.java`
- Create: `beenest-iam-core/src/main/java/club/beenest/iam/core/spi/federation/ProviderTypeInfo.java`
- Modify: `beenest-iam-federation/src/main/java/club/beenest/iam/federation/registry/IdentityProviderRegistry.java`
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/controller/IdentityProviderController.java`
- Test: `beenest-iam-federation/src/test/java/club/beenest/iam/federation/registry/IdentityProviderRegistryTest.java`

**Interfaces:**
- Consumes: 现有 `IdentityProviderFactory`（`getProviderId()` / `getName()` / `getConfigProperties()`）。
- Produces:
  - `boolean IdentityProviderFactory.supportsBrowserLogin()` —— 默认方法，默认返回 `true`。
  - `record ProviderTypeInfo(String providerId, String name, boolean browserLogin, List<ProviderConfigProperty> configProperties)` —— 位于 `club.beenest.iam.core.spi.federation`。
  - `List<ProviderTypeInfo> IdentityProviderRegistry.listProviderTypes()`。
  - `GET /api/v1/admin/identity-providers/provider-types` → `Result<List<ProviderTypeInfo>>`，权限 `idp:view`。

- [ ] **Step 1: 写失败的测试**

创建 `beenest-iam-federation/src/test/java/club/beenest/iam/federation/registry/IdentityProviderRegistryTest.java`：

```java
package club.beenest.iam.federation.registry;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderTypeInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提供方类型注册表测试。
 * <p>类型元数据是热插拔的入口:管理端据此渲染类型下拉与配置表单,
 * 元数据缺失或能力位错误,控制台就会把小程序类提供方当成可跳转项渲染出来。</p>
 */
class IdentityProviderRegistryTest {

    @Test
    @DisplayName("类型元数据按 providerId 汇总,含浏览器跳转能力位与配置项声明")
    void listProviderTypesExposesMetadata() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(
                new StubFactory("oidc", true), new StubFactory("wechat-mini", false)));

        List<ProviderTypeInfo> types = registry.listProviderTypes();

        assertEquals(2, types.size());
        ProviderTypeInfo oidc = types.stream()
                .filter(t -> "oidc".equals(t.providerId())).findFirst().orElseThrow();
        assertTrue(oidc.browserLogin(), "浏览器跳转类提供方应当支持浏览器登录");
        assertEquals("测试 OIDC", oidc.name());
        assertEquals(1, oidc.configProperties().size());

        ProviderTypeInfo mini = types.stream()
                .filter(t -> "wechat-mini".equals(t.providerId())).findFirst().orElseThrow();
        assertFalse(mini.browserLogin(), "小程序类提供方不能出现在浏览器跳转入口里");
    }

    @Test
    @DisplayName("未注册的类型不在元数据里")
    void unregisteredTypeAbsent() {
        IdentityProviderRegistry registry = new IdentityProviderRegistry(List.of(new StubFactory("oidc", true)));
        assertTrue(registry.listProviderTypes().stream()
                .noneMatch(t -> "wechat-web".equals(t.providerId())));
    }

    /** 桩工厂:只实现元数据相关的三个方法。 */
    private static final class StubFactory implements IdentityProviderFactory<Object> {
        private final String providerId;
        private final boolean browserLogin;

        StubFactory(String providerId, boolean browserLogin) {
            this.providerId = providerId;
            this.browserLogin = browserLogin;
        }

        @Override
        public String getProviderId() {
            return providerId;
        }

        @Override
        public String getName() {
            return "测试 " + providerId;
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return List.of(new ProviderConfigProperty("clientId", "客户端 ID",
                    ProviderConfigProperty.ConfigType.STRING, true, false, null, ""));
        }

        @Override
        public boolean supportsBrowserLogin() {
            return browserLogin;
        }

        @Override
        public Object create(IdentityProviderConfig config) {
            throw new UnsupportedOperationException("测试不需要真实实例");
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-federation -am test -Dtest=IdentityProviderRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，报 `ProviderTypeInfo` 找不到 / `listProviderTypes()` 不存在。

- [ ] **Step 3: 实现类型元数据**

`beenest-iam-core/.../core/spi/federation/IdentityProviderFactory.java` 增加默认方法：

```java
    /**
     * 该类型是否支持浏览器跳转登录。
     * <p>默认支持:绝大多数三方登录都是"浏览器跳到外部 IdP 再跳回来"。
     * 小程序类提供方没有浏览器跳转地址,只能由客户端直连换取身份,
     * 这类提供方返回 false,登录页据此不渲染跳转按钮。</p>
     */
    default boolean supportsBrowserLogin() {
        return true;
    }
```

新建 `beenest-iam-core/.../core/spi/federation/ProviderTypeInfo.java`：

```java
package club.beenest.iam.core.spi.federation;

import java.util.List;

/**
 * 提供方类型元数据。
 * <p>描述"这一类提供方需要配什么、能做什么",供管理端渲染配置表单、
 * 登录页决定是否渲染跳转入口。新增提供方类型时由工厂自报,
 * 核心与管理端都不需要为它写任何分支。</p>
 *
 * @param providerId      提供方类型标识
 * @param name            展示名
 * @param browserLogin    是否支持浏览器跳转登录
 * @param configProperties 该类型支持的配置项声明
 */
public record ProviderTypeInfo(
        String providerId,
        String name,
        boolean browserLogin,
        List<ProviderConfigProperty> configProperties
) {
}
```

`beenest-iam-federation/.../registry/IdentityProviderRegistry.java` 增加：

```java
    /**
     * 列出容器内已注册的全部提供方类型。
     * <p>按类型标识排序,保证管理端展示顺序稳定。</p>
     */
    public List<ProviderTypeInfo> listProviderTypes() {
        return factoriesByProviderId.values().stream()
                .map(factory -> new ProviderTypeInfo(
                        factory.getProviderId(), factory.getName(),
                        factory.supportsBrowserLogin(), factory.getConfigProperties()))
                .sorted(java.util.Comparator.comparing(ProviderTypeInfo::providerId))
                .toList();
    }
```

（补 import：`club.beenest.iam.core.spi.federation.ProviderTypeInfo`。）

`beenest-iam-admin/.../controller/IdentityProviderController.java` 增加端点（放在 `list()` 之后）：

```java
    /**
     * 查询容器内已注册的提供方类型及其配置项声明。
     * <p>管理端据此渲染类型下拉与配置表单:新增提供方只需引入扩展包,
     * 不必改动控制台前端。</p>
     */
    @GetMapping("/provider-types")
    @PreAuthorize("hasAuthority('idp:view')")
    public Result<List<ProviderTypeInfo>> providerTypes() {
        return Result.success(identityProviderRegistry.listProviderTypes());
    }
```

该控制器需新增构造参数 `IdentityProviderRegistry identityProviderRegistry`（import `club.beenest.iam.federation.registry.IdentityProviderRegistry` 与 `club.beenest.iam.core.spi.federation.ProviderTypeInfo`）。admin 模块已依赖 persistence，federation 由 authz 传递引入——**先确认 `beenest-iam-admin/pom.xml` 是否已依赖 `beenest-iam-federation`；若无则补上该依赖**。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-federation -am test -Dtest=IdentityProviderRegistryTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add beenest-iam-core beenest-iam-federation beenest-iam-admin
git commit -m "feat(idp): 暴露提供方类型元数据,管理端可动态渲染提供方类型"
```

---

### Task 2: 机密配置键规则化

**Files:**
- Modify: `beenest-iam-common/src/main/java/club/beenest/iam/common/security/IamIdpConfigKeys.java`
- Modify: `beenest-iam-admin/src/main/java/club/beenest/iam/admin/service/impl/IdentityProviderAdminServiceImpl.java`
- Test: `beenest-iam-common/src/test/java/club/beenest/iam/common/security/IamIdpConfigKeysTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `boolean IamIdpConfigKeys.isSensitive(String key)` —— `clientSecret` 或键名以 `Secret`/`secret` 结尾时返回 `true`。

- [ ] **Step 1: 写失败的测试**

创建 `beenest-iam-common/src/test/java/club/beenest/iam/common/security/IamIdpConfigKeysTest.java`：

```java
package club.beenest.iam.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机密配置键判定测试。
 * <p>判定规则若只认死名单,新扩展包引入带密钥的配置项时就必须回核心加一条,
 * 热插拔就成了空话。这里钉住"以 Secret 结尾即机密"的约定。</p>
 */
class IamIdpConfigKeysTest {

    @Test
    @DisplayName("以 Secret 结尾的配置键一律按机密处理")
    void secretSuffixIsSensitive() {
        assertTrue(IamIdpConfigKeys.isSensitive("clientSecret"));
        assertTrue(IamIdpConfigKeys.isSensitive("appSecret"));
        assertTrue(IamIdpConfigKeys.isSensitive("apiSecret"));
        assertTrue(IamIdpConfigKeys.isSensitive("appsecret"));
    }

    @Test
    @DisplayName("普通配置键不算机密")
    void ordinaryKeysAreNotSensitive() {
        assertFalse(IamIdpConfigKeys.isSensitive("clientId"));
        assertFalse(IamIdpConfigKeys.isSensitive("authorizationUrl"));
        assertFalse(IamIdpConfigKeys.isSensitive(null));
        assertFalse(IamIdpConfigKeys.isSensitive(""));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-common test -Dtest=IamIdpConfigKeysTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL，`appSecret` 判定为 false。

- [ ] **Step 3: 实现规则**

`IamIdpConfigKeys.java` 改为：

```java
    /** 需要加密落库的配置键:固定名 + 以 Secret 结尾的约定。 */
    private static final Set<String> SENSITIVE = Set.of(CLIENT_SECRET);

    /** 机密键名后缀:扩展包新增带密钥的配置项时按此约定命名,核心无需改动。 */
    private static final String SECRET_SUFFIX = "secret";

    /**
     * 该配置键是否属于机密。
     * <p>除固定名单外,键名以 secret 结尾(忽略大小写)的一律按机密处理:
     * 扩展包各自命名密钥(appSecret/apiSecret),若只认死名单,
     * 每接一个新提供方都得回核心加一条,扩展包的独立性就被破坏了。</p>
     */
    public static boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (SENSITIVE.contains(key)) {
            return true;
        }
        return key.toLowerCase(java.util.Locale.ROOT).endsWith(SECRET_SUFFIX);
    }
```

`IdentityProviderAdminServiceImpl.java` 的「编辑留空保留原值」逻辑改为按规则判定：把

```java
    private static List<String> sensitiveKeys() {
        return List.of(IamIdpConfigKeys.CLIENT_SECRET);
    }
```

替换为

```java
    /**
     * 库中已存的机密配置键。
     * <p>按机密规则逐键判定:扩展包用 appSecret 之类的键名时,
     * 同样享受"留空即保留原值"的待遇,不会被编辑操作悄悄抹掉。</p>
     */
    private static List<String> sensitiveKeys(Map<String, String> stored) {
        return stored.keySet().stream()
                .filter(IamIdpConfigKeys::isSensitive)
                .toList();
    }
```

并把 `mergeConfig` 里的 `for (String key : sensitiveKeys())` 改为 `for (String key : sensitiveKeys(stored))`（`stored` 即该方法内已有的局部变量）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-common test -Dtest=IamIdpConfigKeysTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add beenest-iam-common beenest-iam-admin
git commit -m "feat(idp): 机密配置键按规则判定,扩展包无需回核心登记密钥键名"
```

---

### Task 3: 签发方基址解析器下沉到 common

**Files:**
- Create: `beenest-iam-common/src/main/java/club/beenest/iam/common/web/IamIssuerBaseUrlResolver.java`
- Modify: `beenest-iam-console/src/main/java/club/beenest/iam/console/support/IssuerBaseUrlResolver.java`（删除）
- Modify: `beenest-iam-console/src/main/java/club/beenest/iam/console/service/impl/LaunchpadServiceImpl.java`（改用新类）
- Test: 无独立单测（由 Task 8 集成测试覆盖）

**Interfaces:**
- Consumes: 无。
- Produces: `class IamIssuerBaseUrlResolver`（`club.beenest.iam.common.web`），方法 `String resolve(HttpServletRequest request)`，构造参数 `@Value("${iam.issuer-base-url:}") String configuredBaseUrl`。`iam.issuer-base-url` 非空时用配置值，否则按当前请求推导。

- [ ] **Step 1: 新建 common 中的解析器**

创建 `beenest-iam-common/src/main/java/club/beenest/iam/common/web/IamIssuerBaseUrlResolver.java`，内容为原 `IssuerBaseUrlResolver` 的实现，类名改为 `IamIssuerBaseUrlResolver`，注释补充一句「小程序登录在业务链上签发令牌时同样需要拼出与协议端点一致的签发方，故把解析器下沉到公共模块」。

- [ ] **Step 2: 控制台改用新类并删除旧类**

`LaunchpadServiceImpl` 中把 `IssuerBaseUrlResolver` 的 import 与字段类型改为 `IamIssuerBaseUrlResolver`；删除 `beenest-iam-console/src/main/java/club/beenest/iam/console/support/IssuerBaseUrlResolver.java`；若 `support` 包下再无其它文件则一并删除该目录。

用 `rg -n "IssuerBaseUrlResolver" beenest-iam-console beenest-iam-web` 确认没有遗漏引用。

- [ ] **Step 3: 编译验证**

Run: `./mvnw -q -o -pl beenest-iam-console -am compile`
Expected: 编译通过。

- [ ] **Step 4: 提交**

```bash
git add beenest-iam-common beenest-iam-console
git commit -m "refactor: 签发方基址解析器下沉到公共模块,业务链签发令牌时复用"
```

---

### Task 4: 微信扩展包 —— 小程序提供方

**Files:**
- Create: `beenest-iam-provider-wechat/pom.xml`
- Create: `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatMiniProgramProvider.java`
- Create: `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatMiniProgramFactory.java`
- Test: `beenest-iam-provider-wechat/src/test/java/club/beenest/iam/provider/wechat/WeChatMiniProgramProviderTest.java`

**Interfaces:**
- Consumes: `club.beenest.iam.core.spi.federation.MiniProgramIdentityProvider`、`CallbackContext`、`BrokeredIdentityContext`、`IdentityProviderConfig`。
- Produces:
  - `class WeChatMiniProgramProvider implements MiniProgramIdentityProvider`，构造 `WeChatMiniProgramProvider(IdentityProviderConfig config, ObjectMapper objectMapper)`。
  - `class WeChatMiniProgramFactory implements IdentityProviderFactory<WeChatMiniProgramProvider>`：`getProviderId()` → `"wechat-mini"`，`getName()` → `"微信小程序"`，`supportsBrowserLogin()` → `false`。

- [ ] **Step 1: 写失败的测试**

创建 `beenest-iam-provider-wechat/src/test/java/club/beenest/iam/provider/wechat/WeChatMiniProgramProviderTest.java`：

```java
package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信小程序提供方测试。
 * <p>换票是纯外部调用,最容易错在两处:把 errcode 当成身份解析,
 * 以及 unionid 缺失时没有回落到 openid。这里用内嵌 HTTP 服务把两种情况都钉住。</p>
 */
class WeChatMiniProgramProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("换票成功:外部标识取 unionid,openid 落进属性")
    void exchangesCodeForIdentity() throws Exception {
        String baseUrl = startServer("{\"openid\":\"o-abc\",\"session_key\":\"sk\",\"unionid\":\"u-xyz\"}");
        WeChatMiniProgramProvider provider = provider(baseUrl);

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(
                new CallbackContext(null, null, "js-code", "default", null));

        assertEquals("u-xyz", identity.getExternalId(), "有 unionid 时以外部统一标识为准");
        assertEquals("o-abc", identity.getAttributes().get("openid"));
        assertEquals("u-xyz", identity.getAttributes().get("unionid"));
        assertNull(identity.getAttributes().get("session_key"), "会话密钥不落库");
    }

    @Test
    @DisplayName("unionid 缺失:外部标识回落到 openid")
    void fallsBackToOpenId() throws Exception {
        String baseUrl = startServer("{\"openid\":\"o-abc\",\"session_key\":\"sk\"}");
        WeChatMiniProgramProvider provider = provider(baseUrl);

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(
                new CallbackContext(null, null, "js-code", "default", null));

        assertEquals("o-abc", identity.getExternalId());
    }

    @Test
    @DisplayName("微信返回错误码:换票失败,不当成身份解析")
    void errorResponseFailsFast() throws Exception {
        String baseUrl = startServer("{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        WeChatMiniProgramProvider provider = provider(baseUrl);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.buildIdentityFromCallback(
                        new CallbackContext(null, null, "js-code", "default", null)));
        assertTrue(error.getMessage().contains("40029"), "错误码应当出现在错误信息里: " + error.getMessage());
    }

    @Test
    @DisplayName("回调缺少 code:直接失败")
    void missingJsCodeFailsFast() {
        WeChatMiniProgramProvider provider = provider("http://127.0.0.1:1");
        assertThrows(IllegalStateException.class,
                () -> provider.buildIdentityFromCallback(
                        new CallbackContext(null, null, null, "default", null)));
    }

    private WeChatMiniProgramProvider provider(String apiBaseUrl) {
        IdentityProviderConfig config = new IdentityProviderConfig("wx-mini", "wechat-mini", "微信小程序", true,
                Map.of("clientId", "wx-app-id", "clientSecret", "wx-app-secret", "apiBaseUrl", apiBaseUrl));
        return new WeChatMiniProgramProvider(config, new ObjectMapper());
    }

    /** 内嵌微信接口:固定返回一段 JSON。 */
    private String startServer(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/jscode2session", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-provider-wechat -am test -Dtest=WeChatMiniProgramProviderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（模块尚不存在）。

- [ ] **Step 3: 建模块骨架**

创建 `beenest-iam-provider-wechat/pom.xml`（照 `beenest-iam-provider-oidc/pom.xml` 改 artifactId 与 description，依赖 `beenest-iam-federation` + `jackson-databind` + `lombok` + `spring-boot-starter-test`）。

在父 `pom.xml` 的 `<modules>` 中 `beenest-iam-provider-oidc` 之后加 `<module>beenest-iam-provider-wechat</module>`，并在 `<dependencyManagement>` 中加对应条目（version 用 `${project.version}`）。

- [ ] **Step 4: 实现小程序提供方**

创建 `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatMiniProgramProvider.java`：

```java
package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.MiniProgramIdentityProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信小程序登录提供方。
 * <p>小程序没有浏览器跳转:客户端把 wx.login() 拿到的 code 上送平台,
 * 平台拿 code 换微信侧身份(openid/unionid),再交给联邦运行时换成本地用户。</p>
 * <p>外部标识取 unionid,缺失时回落到 openid:同一微信用户在开放平台与
 * 小程序下的 openid 不同、unionid 相同,只有绑定过开放平台账号的用户才有 unionid,
 * 这时两个入口才能认出是同一个人。</p>
 */
public class WeChatMiniProgramProvider implements MiniProgramIdentityProvider {

    /** 微信默认接口基址。 */
    private static final String DEFAULT_API_BASE_URL = "https://api.weixin.qq.com";
    /** code2session 路径。 */
    private static final String CODE2SESSION_PATH = "/sns/jscode2session";
    /** 外部调用超时(秒)。 */
    private static final int TIMEOUT_SECONDS = 10;

    private final IdentityProviderConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    public WeChatMiniProgramProvider(IdentityProviderConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public IdentityProviderConfig getConfig() {
        return config;
    }

    @Override
    public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
        // 1. 回调必须带小程序 code
        String jsCode = ctx.jsCode();
        if (jsCode == null || jsCode.isBlank()) {
            throw new IllegalStateException("小程序登录回调缺少 code");
        }
        // 2. 调微信接口换身份
        Map<String, Object> response = requestCode2Session(jsCode);
        // 3. 微信用 errcode 表示失败,不是 HTTP 状态码
        Object errcode = response.get("errcode");
        if (errcode != null) {
            throw new IllegalStateException("微信小程序换票失败: errcode=" + errcode
                    + ", errmsg=" + response.get("errmsg"));
        }
        String openid = text(response, "openid");
        if (openid == null || openid.isBlank()) {
            throw new IllegalStateException("微信小程序换票失败: 响应缺少 openid");
        }
        String unionid = text(response, "unionid");
        // 4. 外部标识:优先 unionid,缺失回落 openid
        String externalId = (unionid == null || unionid.isBlank()) ? openid : unionid;
        BrokeredIdentityContext identity = new BrokeredIdentityContext(externalId, config.providerId(), config.alias());
        identity.setAttribute("openid", openid);
        if (unionid != null && !unionid.isBlank()) {
            identity.setAttribute("unionid", unionid);
        }
        // 5. 会话密钥不落库:它是加密通信用的临时凭据,长期保存只增加泄露面
        return identity;
    }

    /** 用 code 换微信侧身份。 */
    private Map<String, Object> requestCode2Session(String jsCode) {
        String url = String.format("%s%s?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code",
                apiBaseUrl(), CODE2SESSION_PATH,
                encode(config.require("clientId")), encode(config.require("clientSecret")), encode(jsCode));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("微信小程序换票请求失败,HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("微信小程序换票请求异常", e);
        }
    }

    /** 接口基址:配置项优先,缺省用微信公网地址。 */
    private String apiBaseUrl() {
        String base = config.get("apiBaseUrl");
        return (base == null || base.isBlank()) ? DEFAULT_API_BASE_URL : trimTrailingSlash(base.trim());
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
```

创建 `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatMiniProgramFactory.java`：

```java
package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty.ConfigType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 微信小程序提供方工厂。
 * <p>以 Spring Bean 形式注册,联邦运行时按 classpath 自动发现;
 * 移除本模块依赖即下线该提供方,无需改动核心代码。</p>
 */
@Component
public class WeChatMiniProgramFactory implements IdentityProviderFactory<WeChatMiniProgramProvider> {

    private final ObjectMapper objectMapper;

    public WeChatMiniProgramFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderId() {
        return "wechat-mini";
    }

    @Override
    public String getName() {
        return "微信小程序";
    }

    @Override
    public boolean supportsBrowserLogin() {
        // 小程序没有浏览器跳转地址:只能由客户端直连换取身份
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty("clientId", "小程序 AppID", ConfigType.STRING, true, false, null,
                        "微信小程序后台的 AppID"),
                new ProviderConfigProperty("clientSecret", "小程序 AppSecret", ConfigType.PASSWORD, true, true, null,
                        "微信小程序后台的 AppSecret,加密落库且不回显"),
                new ProviderConfigProperty("apiBaseUrl", "接口基址", ConfigType.URL, false, false,
                        "https://api.weixin.qq.com", "微信接口基址,私有部署或测试时覆盖")
        );
    }

    @Override
    public WeChatMiniProgramProvider create(IdentityProviderConfig config) {
        return new WeChatMiniProgramProvider(config, objectMapper);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-provider-wechat -am test -Dtest=WeChatMiniProgramProviderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 提交**

```bash
git add beenest-iam-provider-wechat pom.xml
git commit -m "feat(wechat): 新增微信小程序登录提供方扩展包"
```

---

### Task 5: 微信扩展包 —— 扫码登录提供方

**Files:**
- Create: `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatWebProvider.java`
- Create: `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatWebFactory.java`
- Test: `beenest-iam-provider-wechat/src/test/java/club/beenest/iam/provider/wechat/WeChatWebProviderTest.java`

**Interfaces:**
- Consumes: `club.beenest.iam.federation.provider.OAuth2IdentityProvider`（基类，提供 `appendQueryParam` 静态方法与 `config` 字段）。
- Produces:
  - `class WeChatWebProvider extends OAuth2IdentityProvider`，构造 `WeChatWebProvider(IdentityProviderConfig config, ObjectMapper objectMapper)`。
  - `class WeChatWebFactory implements IdentityProviderFactory<WeChatWebProvider>`：`getProviderId()` → `"wechat-web"`，`getName()` → `"微信扫码登录"`，`supportsBrowserLogin()` → `true`（默认）。

- [ ] **Step 1: 写失败的测试**

创建 `beenest-iam-provider-wechat/src/test/java/club/beenest/iam/provider/wechat/WeChatWebProviderTest.java`：

```java
package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.AuthorizationRequest;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信扫码登录提供方测试。
 * <p>微信的授权地址有三条硬约束,错了不会报错,只是微信不渲染二维码:
 * appid 必须在首位、redirect_uri 整体编码、地址以 #wechat_redirect 结尾。</p>
 */
class WeChatWebProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("授权地址满足微信的三条硬约束")
    void authorizationUrlMatchesWechatRequirements() {
        WeChatWebProvider provider = provider("https://open.weixin.qq.com/connect/qrconnect");

        String url = provider.authorizationUrl(new AuthorizationRequest(
                "https://iam.example.com/broker/default/wx-web/callback", "state-1", Map.of()));

        assertTrue(url.startsWith("https://open.weixin.qq.com/connect/qrconnect?appid="),
                "appid 必须是第一个参数: " + url);
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fiam.example.com%2Fbroker%2Fdefault%2Fwx-web%2Fcallback"),
                "redirect_uri 必须整体编码: " + url);
        assertTrue(url.endsWith("#wechat_redirect"), "地址必须以 #wechat_redirect 结尾: " + url);
        assertTrue(url.contains("scope=snsapi_login"), "默认范围应为 snsapi_login: " + url);
        assertTrue(url.contains("state=state-1"), "必须携带 state: " + url);
    }

    @Test
    @DisplayName("回调换票:拿到 openid 与昵称")
    void exchangesCodeForIdentity() throws Exception {
        String baseUrl = startServer();
        WeChatWebProvider provider = provider("https://open.weixin.qq.com/connect/qrconnect");
        // 把令牌与用户信息端点指向内嵌服务
        provider = new WeChatWebProvider(config(baseUrl), new ObjectMapper());

        BrokeredIdentityContext identity = provider.buildIdentityFromCallback(
                new CallbackContext("auth-code", "state-1", null, "default",
                        "https://iam.example.com/broker/default/wx-web/callback"));

        assertEquals("u-xyz", identity.getExternalId());
        assertEquals("张三", identity.getNickname());
        assertEquals("o-abc", identity.getAttributes().get("openid"));
    }

    private WeChatWebProvider provider(String authorizationUrl) {
        return new WeChatWebProvider(config(authorizationUrl), new ObjectMapper());
    }

    private IdentityProviderConfig config(String authorizationUrl) {
        return new IdentityProviderConfig("wx-web", "wechat-web", "微信扫码登录", true,
                Map.of("clientId", "wx-open-id", "clientSecret", "wx-open-secret",
                        "authorizationUrl", authorizationUrl,
                        "tokenUrl", "https://api.weixin.qq.com/sns/oauth2/access_token",
                        "userInfoUrl", "https://api.weixin.qq.com/sns/userinfo"));
    }

    /** 内嵌微信接口:令牌端点与用户信息端点。 */
    private String startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sns/oauth2/access_token", exchange -> {
            respond(exchange, "{\"access_token\":\"mock-access\",\"openid\":\"o-abc\","
                    + "\"unionid\":\"u-xyz\",\"expires_in\":7200}");
        });
        server.createContext("/sns/userinfo", exchange -> {
            respond(exchange, "{\"openid\":\"o-abc\",\"nickname\":\"张三\","
                    + "\"headimgurl\":\"https://img.example.com/a.png\"}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-provider-wechat -am test -Dtest=WeChatWebProviderTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现扫码提供方**

创建 `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatWebProvider.java`：

```java
package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.federation.provider.OAuth2IdentityProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信开放平台扫码登录提供方。
 * <p>浏览器跳到微信的扫码授权页,微信渲染二维码,用户扫一扫授权后
 * 微信回调本平台的 broker 回调地址,平台用授权码换 openid 再取昵称头像。</p>
 * <p>授权地址不能沿用基类的拼装顺序:微信按位置解析参数,
 * appid 必须是第一个参数,地址末尾还必须带 #wechat_redirect 片段,
 * 少了任何一条微信都不会渲染二维码,只给一个空白页。</p>
 */
public class WeChatWebProvider extends OAuth2IdentityProvider {

    /** 扫码授权地址默认值。 */
    private static final String DEFAULT_AUTHORIZATION_URL = "https://open.weixin.qq.com/connect/qrconnect";
    /** 令牌端点默认值。 */
    private static final String DEFAULT_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    /** 用户信息端点默认值。 */
    private static final String DEFAULT_USER_INFO_URL = "https://api.weixin.qq.com/sns/userinfo";
    /** 微信要求授权地址带的片段,没有它不渲染二维码。 */
    private static final String WECHAT_REDIRECT_FRAGMENT = "#wechat_redirect";
    /** 默认授权范围:snsapi_login 才会返回昵称头像。 */
    private static final String DEFAULT_SCOPE = "snsapi_login";
    /** 外部调用超时(秒)。 */
    private static final int TIMEOUT_SECONDS = 10;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();

    public WeChatWebProvider(IdentityProviderConfig config, ObjectMapper objectMapper) {
        super(config);
        this.objectMapper = objectMapper;
    }

    @Override
    public String authorizationUrl(club.beenest.iam.core.spi.federation.AuthorizationRequest request) {
        // 1. 微信按位置解析参数:appid 必须排在最前
        StringBuilder url = new StringBuilder(authorizationEndpoint());
        url.append(appendQueryParam("?", "appid", config.require("clientId")));
        url.append(appendQueryParam("&", "redirect_uri", request.redirectUri()));
        url.append(appendQueryParam("&", "response_type", "code"));
        url.append(appendQueryParam("&", "scope", scope()));
        url.append(appendQueryParam("&", "state", request.state()));
        for (Map.Entry<String, String> entry : request.extraParams().entrySet()) {
            url.append(appendQueryParam("&", entry.getKey(), entry.getValue()));
        }
        // 2. 结尾片段不能少,少了微信不渲染二维码
        url.append(WECHAT_REDIRECT_FRAGMENT);
        return url.toString();
    }

    @Override
    public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
        // 1. 回调必须带授权码
        if (ctx.code() == null || ctx.code().isBlank()) {
            throw new IllegalStateException("微信扫码回调缺少授权码");
        }
        // 2. 用授权码换访问令牌与 openid
        Map<String, Object> token = requestToken(ctx);
        String openid = text(token, "openid");
        if (openid == null || openid.isBlank()) {
            throw new IllegalStateException("微信扫码换票失败: 响应缺少 openid");
        }
        String unionid = text(token, "unionid");
        // 3. 取用户信息(昵称头像需要用户授权 snsapi_login)
        Map<String, Object> userInfo = requestUserInfo(text(token, "access_token"), openid);
        // 4. 外部标识:优先 unionid,缺失回落 openid
        String externalId = (unionid == null || unionid.isBlank()) ? openid : unionid;
        BrokeredIdentityContext identity = new BrokeredIdentityContext(externalId, config.providerId(), config.alias());
        identity.setNickname(text(userInfo, "nickname"));
        identity.setAttribute("openid", openid);
        if (unionid != null && !unionid.isBlank()) {
            identity.setAttribute("unionid", unionid);
        }
        String avatar = text(userInfo, "headimgurl");
        if (avatar != null && !avatar.isBlank()) {
            identity.setAttribute("avatar", avatar);
        }
        return identity;
    }

    /** 授权码换访问令牌。 */
    private Map<String, Object> requestToken(CallbackContext ctx) {
        String url = String.format("%s?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
                tokenEndpoint(), encode(config.require("clientId")), encode(config.require("clientSecret")),
                encode(ctx.code()));
        return parseJson(get(url));
    }

    /** 取用户信息。 */
    private Map<String, Object> requestUserInfo(String accessToken, String openid) {
        String url = String.format("%s?access_token=%s&openid=%s&lang=zh_CN",
                userInfoEndpoint(), encode(accessToken), encode(openid));
        return parseJson(get(url));
    }

    /** 发起 GET 并返回响应体。 */
    private String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("微信接口请求失败,HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("微信接口请求异常", e);
        }
    }

    private Map<String, Object> parseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("微信接口响应不是合法 JSON", e);
        }
    }

    private String authorizationEndpoint() {
        return endpoint("authorizationUrl", DEFAULT_AUTHORIZATION_URL);
    }

    private String tokenEndpoint() {
        return endpoint("tokenUrl", DEFAULT_TOKEN_URL);
    }

    private String userInfoEndpoint() {
        return endpoint("userInfoUrl", DEFAULT_USER_INFO_URL);
    }

    private String endpoint(String key, String fallback) {
        String value = config.get(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String scope() {
        String value = config.get("scope");
        return (value == null || value.isBlank()) ? DEFAULT_SCOPE : value.trim();
    }

    private static String encode(String value) {
        return value == null ? "" : java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
```

创建 `beenest-iam-provider-wechat/src/main/java/club/beenest/iam/provider/wechat/WeChatWebFactory.java`：

```java
package club.beenest.iam.provider.wechat;

import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty.ConfigType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 微信开放平台扫码登录提供方工厂。
 * <p>以 Spring Bean 形式注册,联邦运行时按 classpath 自动发现;
 * 移除本模块依赖即下线该提供方,无需改动核心代码。</p>
 */
@Component
public class WeChatWebFactory implements IdentityProviderFactory<WeChatWebProvider> {

    private final ObjectMapper objectMapper;

    public WeChatWebFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderId() {
        return "wechat-web";
    }

    @Override
    public String getName() {
        return "微信扫码登录";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                new ProviderConfigProperty("clientId", "开放平台 AppID", ConfigType.STRING, true, false, null,
                        "微信开放平台「网站应用」的 AppID"),
                new ProviderConfigProperty("clientSecret", "开放平台 AppSecret", ConfigType.PASSWORD, true, true, null,
                        "微信开放平台「网站应用」的 AppSecret,加密落库且不回显"),
                new ProviderConfigProperty("scope", "授权范围", ConfigType.STRING, false, false, "snsapi_login",
                        "snsapi_login 才会返回昵称头像"),
                new ProviderConfigProperty("authorizationUrl", "扫码授权端点", ConfigType.URL, false, false,
                        "https://open.weixin.qq.com/connect/qrconnect", "微信扫码授权地址"),
                new ProviderConfigProperty("tokenUrl", "令牌端点", ConfigType.URL, false, false,
                        "https://api.weixin.qq.com/sns/oauth2/access_token", "授权码换访问令牌的地址"),
                new ProviderConfigProperty("userInfoUrl", "用户信息端点", ConfigType.URL, false, false,
                        "https://api.weixin.qq.com/sns/userinfo", "读取昵称头像的地址")
        );
    }

    @Override
    public WeChatWebProvider create(IdentityProviderConfig config) {
        return new WeChatWebProvider(config, objectMapper);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-provider-wechat -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 两个测试类全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add beenest-iam-provider-wechat
git commit -m "feat(wechat): 新增微信开放平台扫码登录提供方"
```

---

### Task 6: 联邦运行时的小程序换取入口

**Files:**
- Modify: `beenest-iam-federation/src/main/java/club/beenest/iam/federation/broker/IdentityBrokerService.java`
- Test: `beenest-iam-federation/src/test/java/club/beenest/iam/federation/broker/IdentityBrokerServiceMiniProgramTest.java`

**Interfaces:**
- Consumes: `IdentityProviderRegistry`、`MiniProgramIdentityProvider`、`UserFederationLinkService`。
- Produces: `UserModel IdentityBrokerService.completeMiniProgramLogin(String realmKey, String alias, String jsCode)`。

- [ ] **Step 1: 写失败的测试**

创建 `beenest-iam-federation/src/test/java/club/beenest/iam/federation/broker/IdentityBrokerServiceMiniProgramTest.java`：

```java
package club.beenest.iam.federation.broker;

import club.beenest.iam.common.security.IamSecretCrypto;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.core.spi.federation.BrokeredIdentityContext;
import club.beenest.iam.core.spi.federation.CallbackContext;
import club.beenest.iam.core.spi.federation.IdentityProvider;
import club.beenest.iam.core.spi.federation.IdentityProviderConfig;
import club.beenest.iam.core.spi.federation.IdentityProviderFactory;
import club.beenest.iam.core.spi.federation.MiniProgramIdentityProvider;
import club.beenest.iam.core.spi.federation.ProviderConfigProperty;
import club.beenest.iam.core.model.IdentityProviderModel;
import club.beenest.iam.core.spi.provider.IdentityProviderStore;
import club.beenest.iam.core.spi.provider.RealmProvider;
import club.beenest.iam.federation.registry.IdentityProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 小程序类提供方的换取入口测试。
 * <p>入口只认实现了小程序 SPI 的提供方:把浏览器跳转类提供方误当成小程序类,
 * 换票时会拿不到 code,错误必须在换取前就说清楚。</p>
 */
class IdentityBrokerServiceMiniProgramTest {

    @Test
    @DisplayName("提供方不是小程序类:换取前就拒绝")
    void rejectsNonMiniProgramProvider() {
        IdentityBrokerService service = new IdentityBrokerService(
                realmProvider(), identityProviderStore(), registry(new BrowserOnlyFactory()),
                new UserFederationLinkService(null, null, null, null), secretCrypto());

        club.beenest.iam.common.exception.BusinessException error = assertThrows(
                club.beenest.iam.common.exception.BusinessException.class,
                () -> service.completeMiniProgramLogin("default", "wx", "js-code"));
        assertEquals("该身份提供方不支持小程序登录: wx", error.getMessage());
    }

    @Test
    @DisplayName("小程序类提供方:用 jsCode 换到外部身份并落到本地用户")
    void exchangesJsCode() {
        IdentityBrokerService service = new IdentityBrokerService(
                realmProvider(), identityProviderStore(), registry(new MiniFactory()),
                new UserFederationLinkService(null, null, null, null) {
                    @Override
                    public UserModel findOrCreateLinkedUser(BrokeredIdentityContext identity, Long realmId, Long idpId) {
                        return new UserModel(7L, realmId, "wx-mini-o-1", null, "张三", 1, "EXTERNAL", 0, Map.of());
                    }
                }, secretCrypto());

        UserModel user = service.completeMiniProgramLogin("default", "wx", "js-code");
        assertEquals(7L, user.id());
    }

    // ===== 桩件 =====

    private static IdentityProviderRegistry registry(IdentityProviderFactory<?> factory) {
        return new IdentityProviderRegistry(List.of(factory));
    }

    private static RealmProvider realmProvider() {
        return new RealmProvider() {
            @Override
            public Optional<RealmModel> findByRealmKey(String realmKey) {
                return Optional.of(new RealmModel(1L, "default", "默认域", true, 3600, 2592000, 0, 0));
            }

            @Override
            public Optional<RealmModel> findById(Long id) {
                return findByRealmKey("default");
            }

            @Override
            public java.util.List<RealmModel> listAll() {
                return List.of();
            }
        };
    }

    private static IdentityProviderStore identityProviderStore() {
        return new IdentityProviderStore() {
            @Override
            public Optional<IdentityProviderModel> findByIdp(Long realmId, String alias) {
                return Optional.of(new IdentityProviderModel(9L, realmId, alias, "wechat-mini", "微信小程序", true, Map.of()));
            }

            @Override
            public java.util.List<IdentityProviderModel> listByRealm(Long realmId) {
                return List.of();
            }

            @Override
            public Optional<Long> findLinkedUserId(Long idpId, String externalId) {
                return Optional.empty();
            }

            @Override
            public void link(Long realmId, Long userId, Long idpId, String externalId, String externalName) {
            }

            @Override
            public void unlinkByIdp(Long idpId) {
            }

            @Override
            public java.util.List<club.beenest.iam.core.model.IdpMapperModel> listMappers(Long idpId) {
                return List.of();
            }
        };
    }

    private static IamSecretCrypto secretCrypto() {
        return new IamSecretCrypto("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 浏览器跳转类提供方:不支持小程序登录。 */
    private static final class BrowserOnlyFactory implements IdentityProviderFactory<IdentityProvider<IdentityProviderConfig>> {
        @Override
        public String getProviderId() {
            return "oidc";
        }

        @Override
        public String getName() {
            return "通用 OIDC";
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return List.of();
        }

        @Override
        public IdentityProvider<IdentityProviderConfig> create(IdentityProviderConfig config) {
            return new IdentityProvider<>() {
                @Override
                public IdentityProviderConfig getConfig() {
                    return config;
                }

                @Override
                public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
                    return new BrokeredIdentityContext("x", "oidc", "x");
                }
            };
        }
    }

    /** 小程序类提供方。 */
    private static final class MiniFactory implements IdentityProviderFactory<MiniProgramIdentityProvider> {
        @Override
        public String getProviderId() {
            return "wechat-mini";
        }

        @Override
        public String getName() {
            return "微信小程序";
        }

        @Override
        public List<ProviderConfigProperty> getConfigProperties() {
            return List.of();
        }

        @Override
        public MiniProgramIdentityProvider create(IdentityProviderConfig config) {
            return new MiniProgramIdentityProvider() {
                @Override
                public IdentityProviderConfig getConfig() {
                    return config;
                }

                @Override
                public BrokeredIdentityContext buildIdentityFromCallback(CallbackContext ctx) {
                    return new BrokeredIdentityContext("o-1", "wechat-mini", "wx-mini");
                }
            };
        }
    }
}
```

> **执行提示**：本测试用匿名实现桩件。落地时若桩件的抽象方法签名与实际接口不一致（例如 `RealmProvider`、`IdentityProviderStore`、`UserModel` 的字段顺序），以实际接口为准调整桩件，**不要改动被测接口**。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-federation -am test -Dtest=IdentityBrokerServiceMiniProgramTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`completeMiniProgramLogin` 不存在）或桩件签名不匹配报错。

- [ ] **Step 3: 实现换取入口**

在 `IdentityBrokerService` 中新增：

```java
    /**
     * 小程序类提供方的登录落地:用客户端上送的 code 换外部身份,再绑定或复用本地用户。
     *
     * @param realmKey 身份域标识
     * @param alias    提供方别名
     * @param jsCode   客户端上送的一次性 code
     * @return 本地用户
     */
    public UserModel completeMiniProgramLogin(String realmKey, String alias, String jsCode) {
        // 1. 读取提供方并创建实例
        IdentityProviderModel model = loadIdp(realmKey, alias);
        IdentityProvider<?> provider = registry.create(toConfig(model));
        // 2. 只有小程序类提供方才有 code 换身份的入口:
        //    拿浏览器跳转类来换,换票阶段才会因取不到 code 而失败,报错太晚
        if (!(provider instanceof MiniProgramIdentityProvider miniProgram)) {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "该身份提供方不支持小程序登录: " + alias);
        }
        // 3. 换外部身份
        BrokeredIdentityContext identity;
        try {
            identity = miniProgram.buildIdentityFromCallback(
                    new CallbackContext(null, null, jsCode, realmKey, null));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 外部服务故障、网络异常、响应非法都归到同一类:上游不可用
            throw unavailable(alias, "换取外部身份失败", e);
        }
        // 4. 绑定/复用本地用户
        return federationLinkService.findOrCreateLinkedUser(identity, model.realmId(), model.id());
    }
```

补 import：`club.beenest.iam.core.spi.federation.MiniProgramIdentityProvider`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-federation -am test -Dtest=IdentityBrokerServiceMiniProgramTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add beenest-iam-federation
git commit -m "feat(federation): 联邦运行时支持小程序类提供方的换取入口"
```

---

### Task 7: 小程序登录端点与令牌签发（authz）

**Files:**
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mini/MiniProgramTokenIssuer.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mini/MiniProgramLoginService.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/MiniProgramLoginRequest.java`
- Create: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/MiniProgramLoginResponse.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/AuthController.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/config/IamSecurityConfiguration.java`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamMiniProgramLoginIntegrationTest.java`

**Interfaces:**
- Consumes: `IdentityBrokerService.completeMiniProgramLogin`、`IamPrincipalFactory`、`IamRegisteredClientRepository`、`RedisOAuth2AuthorizationService`、`IamJwtTokenCustomizer`、`JwtEncoder`、`IamIssuerBaseUrlResolver`。
- Produces:
  - `POST /api/v1/auth/login/mini-program?realm={realmKey}` → `Result<MiniProgramLoginResponse>`。
  - `record MiniProgramLoginResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, String scope, CurrentUserResponse user)`。
  - `record MiniProgramLoginRequest(@NotBlank String provider, @NotBlank String jsCode, @NotBlank String clientId, @NotBlank String clientSecret)`。

- [ ] **Step 1: 写失败的测试**

创建 `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamMiniProgramLoginIntegrationTest.java`：

```java
package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小程序登录闭环集成测试。
 * <p>验证四件事:客户端密钥错误被拒;jsCode 换到外部身份后自动建号;
 * openid 落进用户扩展属性;签发的令牌能访问受保护接口并可刷新。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamMiniProgramLoginIntegrationTest extends IamIntegrationTestBase {

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

    private HttpServer wechat;

    @AfterEach
    void stopWechat() {
        if (wechat != null) {
            wechat.stop(0);
        }
    }

    @Test
    @DisplayName("小程序登录:自动建号、记录 openid、签发可用令牌")
    void miniProgramLoginCreatesUserAndIssuesToken() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-mini-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);
        String clientId = "mini-" + System.nanoTime();
        String clientSecret = createClient(admin, clientId);

        // 1. 客户端密钥错误必须被拒
        ResponseEntity<String> bad = postMiniProgram(alias, clientId, "wrong-secret", "js-code-1");
        assertTrue(bad.getStatusCode().is4xxClientError(),
                "密钥错误必须被拒绝: " + bad.getStatusCode() + " " + bad.getBody());

        // 2. 正常登录
        ResponseEntity<String> response = postMiniProgram(alias, clientId, clientSecret, "js-code-1");
        assertEquals(200, response.getStatusCode().value(), "小程序登录应当成功: " + response.getBody());
        JsonNode data = dataOf(response.getBody());
        String accessToken = data.path("accessToken").asText();
        assertNotNull(accessToken, "应当签发访问令牌: " + response.getBody());
        assertTrue(data.path("refreshToken").asText().length() > 0, "应当签发刷新令牌");

        // 3. 令牌能访问受保护接口
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(accessToken);
        ResponseEntity<String> me = rest.exchange("/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(null, bearer), String.class);
        assertEquals(200, me.getStatusCode().value(), "令牌应当能访问当前用户接口: " + me.getBody());

        // 4. 用户已自动建出,且 openid 落在扩展属性里
        ResponseEntity<String> users = exchange(admin, HttpMethod.GET, "/api/v1/admin/users?pageSize=200", null);
        JsonNode external = null;
        for (JsonNode node : dataOf(users.getBody()).path("records")) {
            if ("EXTERNAL".equals(node.path("userType").asText())) {
                external = node;
                break;
            }
        }
        assertNotNull(external, "小程序登录应当自动建出外部账号");
        assertEquals("o-mini-1", external.path("attributes").path("openid").asText(),
                "openid 必须落进用户扩展属性");
    }

    // ===== 内部实现 =====

    /** 内嵌微信小程序接口。 */
    private String startWechat() throws IOException {
        wechat = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wechat.createContext("/sns/jscode2session", exchange -> {
            byte[] bytes = "{\"openid\":\"o-mini-1\",\"session_key\":\"sk\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        wechat.start();
        return "http://127.0.0.1:" + wechat.getAddress().getPort();
    }

    /** 登记微信小程序提供方。 */
    private void createMiniProvider(Session admin, String alias, String wechatBase) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "wx-app-id");
        config.put("clientSecret", "wx-app-secret");
        config.put("apiBaseUrl", wechatBase);
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "wechat-mini",
                                "displayName", "微信小程序", "enabled", true, "config", config)));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "登记提供方应当成功: " + created.getBody());
    }

    /** 登记机密客户端,返回明文密钥。 */
    private String createClient(Session admin, String clientKey) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clientKey", clientKey);
        payload.put("clientName", "小程序后端");
        payload.put("clientType", "CONFIDENTIAL");
        payload.put("redirectUris", List.of("https://app.example.com/callback"));
        payload.put("scopes", List.of("openid", "profile"));
        payload.put("grantTypes", List.of("refresh_token"));
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/clients", objectMapper.writeValueAsString(payload));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "登记客户端应当成功: " + created.getBody());
        return dataOf(created.getBody()).path("clientSecret").asText();
    }

    /** 调小程序登录端点。 */
    private ResponseEntity<String> postMiniProgram(String alias, String clientId, String clientSecret, String jsCode)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> payload = new HashMap<>();
        payload.put("provider", alias);
        payload.put("jsCode", jsCode);
        payload.put("clientId", clientId);
        payload.put("clientSecret", clientSecret);
        return rest.exchange("/api/v1/auth/login/mini-program?realm=default", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(payload), headers), String.class);
    }

    private record Session(String cookie, String csrf) {
    }

    private Session login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return new Session(cookie, cookieValue(cookie, "XSRF-TOKEN"));
    }

    private ResponseEntity<String> exchange(Session admin, HttpMethod method, String path, String body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", admin.csrf());
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    private static String cookieValue(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }
}
```

> **执行提示**：`IamIntegrationTestBase` 已提供 `rest`、`objectMapper`、`dataOf(...)`、`ADMIN_PASSWORD`。若基类里的 `dataOf` 名称不同，按实际名称调整；`joinCookies`/`cookieValue` 在本测试类内已定义，不依赖基类。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-server -am test -Dtest=IamMiniProgramLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 失败（端点不存在，返回 404 或 401）。

- [ ] **Step 3: 定义请求与响应 DTO**

创建 `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/MiniProgramLoginRequest.java`：

```java
package club.beenest.iam.authz.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 小程序登录请求。
 * <p>客户端凭据由小程序后端持有并代为调用:小程序本身不接触长期密钥,
 * 即使被逆向也拿不到可换取令牌的凭据。</p>
 *
 * @param provider     提供方别名
 * @param jsCode       小程序 wx.login() 拿到的一次性 code
 * @param clientId     平台注册的客户端标识
 * @param clientSecret 客户端密钥
 */
public record MiniProgramLoginRequest(
        @NotBlank(message = "提供方别名不能为空") String provider,
        @NotBlank(message = "小程序 code 不能为空") String jsCode,
        @NotBlank(message = "客户端标识不能为空") String clientId,
        @NotBlank(message = "客户端密钥不能为空") String clientSecret
) {
}
```

创建 `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/MiniProgramLoginResponse.java`：

```java
package club.beenest.iam.authz.rest.dto;

/**
 * 小程序登录响应。
 * <p>返回标准令牌三元组 + 当前用户信息:小程序凭访问令牌调平台接口,
 * 凭刷新令牌续期,不必自行保管任何会话票据。</p>
 *
 * @param accessToken  访问令牌
 * @param refreshToken 刷新令牌
 * @param tokenType    令牌类型
 * @param expiresIn    访问令牌有效期(秒)
 * @param scope        已授权范围
 * @param user         当前用户与权限码
 */
public record MiniProgramLoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String scope,
        CurrentUserResponse user
) {
}
```

- [ ] **Step 4: 实现令牌签发器**

创建 `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mini/MiniProgramTokenIssuer.java`：

```java
package club.beenest.iam.authz.mini;

import club.beenest.iam.authz.token.IamJwtTokenCustomizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenType;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 小程序登录的令牌签发器。
 * <p>复用协议栈的令牌生成组件与平台的授权存储:签出来的令牌与浏览器
 * 授权码流程签出来的完全同构,因此刷新、吊销、userinfo、单点登出
 * 全部沿用现成链路,不必另写一套令牌生命周期。</p>
 * <p>签发方按"外部访问基址 + /realms/{域标识}"拼出,与协议端点保持一致:
 * 验签侧靠 issuer 里的域标识反查身份域,签发侧拼错就等于签了一张谁也认不出的令牌。</p>
 */
@Component
public class MiniProgramTokenIssuer {

    /** 本平台自有的授权类型:仅标识签发来源,不参与协议栈的授权类型分派。 */
    public static final String GRANT_TYPE = "urn:beenest:iam:grant-type:mini-program";

    private final JwtEncoder jwtEncoder;
    private final IamJwtTokenCustomizer jwtCustomizer;
    private final OAuth2AuthorizationService authorizationService;
    private final AuthorizationServerSettings authorizationServerSettings;

    public MiniProgramTokenIssuer(JwtEncoder jwtEncoder, IamJwtTokenCustomizer jwtCustomizer,
                                   OAuth2AuthorizationService authorizationService,
                                   AuthorizationServerSettings authorizationServerSettings) {
        this.jwtEncoder = jwtEncoder;
        this.jwtCustomizer = jwtCustomizer;
        this.authorizationService = authorizationService;
        this.authorizationServerSettings = authorizationServerSettings;
    }

    /**
     * 签发访问令牌与刷新令牌,并把授权记录写入平台的授权存储。
     *
     * @param client    令牌归属的客户端
     * @param principal 已认证主体
     * @param scopes    已授权范围
     * @param issuer    签发方
     * @return 签发结果
     */
    public IssuedTokens issue(RegisteredClient client, Authentication principal, Set<String> scopes, String issuer) {
        // 1. 授权记录:主体快照放在属性里,令牌定制器据此输出权限声明
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(principal.getName())
                .authorizationGrantType(new AuthorizationGrantType(GRANT_TYPE))
                .authorizedScopes(scopes)
                .attribute(Principal.class.getName(), principal)
                .build();
        // 2. 令牌上下文:签发方与授权记录都要给到生成器
        AuthorizationServerContext serverContext = new FixedAuthorizationServerContext(
                issuer, authorizationServerSettings);
        OAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(principal)
                .authorizationServerContext(serverContext)
                .authorization(authorization)
                .authorizedScopes(scopes)
                .authorizationGrantType(new AuthorizationGrantType(GRANT_TYPE))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();
        // 3. 访问令牌:与协议栈同款的 JWT 生成器 + 同一个声明定制器
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(jwtCustomizer);
        OAuth2Token accessToken = jwtGenerator.generate(tokenContext);
        // 4. 刷新令牌
        OAuth2Token refreshToken = new OAuth2RefreshTokenGenerator().generate(tokenContext);
        // 5. 写回授权记录:刷新、吊销、列举都靠它
        OAuth2Authorization saved = OAuth2Authorization.from(authorization)
                .accessToken((OAuth2AccessToken) accessToken)
                .refreshToken((OAuth2RefreshToken) refreshToken)
                .build();
        authorizationService.save(saved);
        return new IssuedTokens((OAuth2AccessToken) accessToken, (OAuth2RefreshToken) refreshToken);
    }

    /**
     * 签发结果。
     *
     * @param accessToken  访问令牌
     * @param refreshToken 刷新令牌
     */
    public record IssuedTokens(OAuth2AccessToken accessToken, OAuth2RefreshToken refreshToken) {

        /** 访问令牌剩余有效期(秒)。 */
        public long expiresIn() {
            Instant expiresAt = accessToken.getExpiresAt();
            if (expiresAt == null) {
                return 0L;
            }
            return Math.max(0L, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
        }
    }

    /** 签发方上下文:业务链上没有协议栈的上下文,这里按签发方与授权服务器设置构造一个。 */
    private record FixedAuthorizationServerContext(String issuer, AuthorizationServerSettings settings)
            implements AuthorizationServerContext {

        @Override
        public String getIssuer() {
            return issuer;
        }

        @Override
        public AuthorizationServerSettings getAuthorizationServerSettings() {
            return settings;
        }
    }
}
```

- [ ] **Step 5: 实现登录服务**

创建 `beenest-iam-authz/src/main/java/club/beenest/iam/authz/mini/MiniProgramLoginService.java`：

```java
package club.beenest.iam.authz.mini;

import club.beenest.iam.authz.audit.LoginEventPublisher;
import club.beenest.iam.authz.principal.IamPrincipalFactory;
import club.beenest.iam.authz.principal.IamUserPrincipal;
import club.beenest.iam.authz.rest.dto.CurrentUserResponse;
import club.beenest.iam.authz.rest.dto.MiniProgramLoginResponse;
import club.beenest.iam.authz.tenant.RealmContext;
import club.beenest.iam.authz.tenant.RealmContextHolder;
import club.beenest.iam.common.event.LoginEventEvent;
import club.beenest.iam.common.exception.BusinessException;
import club.beenest.iam.common.result.ErrorCode;
import club.beenest.iam.common.web.IamIssuerBaseUrlResolver;
import club.beenest.iam.core.model.RealmModel;
import club.beenest.iam.core.model.UserModel;
import club.beenest.iam.federation.broker.IdentityBrokerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 小程序登录服务。
 * <p>链路:客户端认证 → 联邦换身份 → 自动注册本地用户 → 签发令牌。
 * <p>客户端密钥只在小程序后端持有:小程序把 code 交给自己的后端,
 * 后端代为向平台换令牌,即使小程序被逆向也拿不到可换取令牌的长期凭据。</p>
 */
@Service
public class MiniProgramLoginService {

    /** 默认授权范围:小程序不涉及下游应用,取最小可用集合。 */
    private static final Set<String> DEFAULT_SCOPES = Set.of("openid", "profile");

    private final IdentityBrokerService brokerService;
    private final IamPrincipalFactory principalFactory;
    private final LoginEventPublisher loginEventPublisher;
    private final RegisteredClientRepository registeredClientRepository;
    private final OAuth2AuthorizationService authorizationService;
    private final PasswordEncoder passwordEncoder;
    private final MiniProgramTokenIssuer tokenIssuer;
    private final IamIssuerBaseUrlResolver issuerBaseUrlResolver;

    public MiniProgramLoginService(IdentityBrokerService brokerService,
                                    IamPrincipalFactory principalFactory,
                                    LoginEventPublisher loginEventPublisher,
                                    RegisteredClientRepository registeredClientRepository,
                                    OAuth2AuthorizationService authorizationService,
                                    PasswordEncoder passwordEncoder,
                                    MiniProgramTokenIssuer tokenIssuer,
                                    IamIssuerBaseUrlResolver issuerBaseUrlResolver) {
        this.brokerService = brokerService;
        this.principalFactory = principalFactory;
        this.loginEventPublisher = loginEventPublisher;
        this.registeredClientRepository = registeredClientRepository;
        this.authorizationService = authorizationService;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.issuerBaseUrlResolver = issuerBaseUrlResolver;
    }

    /**
     * 小程序登录。
     *
     * @param provider     提供方别名
     * @param jsCode       小程序一次性 code
     * @param clientId     客户端标识
     * @param clientSecret 客户端密钥
     * @param request      当前请求(用于推导签发方与留痕来源)
     * @return 令牌与当前用户
     */
    public MiniProgramLoginResponse login(String provider, String jsCode, String clientId,
                                          String clientSecret, HttpServletRequest request) {
        // 1. 客户端认证:密钥错误在这里就拒,不必消耗用户的 code
        RegisteredClient client = authenticateClient(clientId, clientSecret);
        // 2. 联邦换身份并自动注册本地用户
        RealmModel realm = currentRealm();
        UserModel user = brokerService.completeMiniProgramLogin(realm.realmKey(), provider, jsCode);
        if (user.status() != 1) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED, "账号已禁用或锁定");
        }
        // 3. 构建主体:权限口径与其它登录方式完全一致
        IamUserPrincipal principal = principalFactory.build(realm, user);
        Authentication authentication = principalFactory.toAuthentication(principal);
        // 4. 签发令牌:签发方与协议端点保持一致,验签侧才能反查回域
        String issuer = issuerBaseUrlResolver.resolve(request) + "/realms/" + realm.realmKey();
        Set<String> scopes = resolveScopes(client);
        MiniProgramTokenIssuer.IssuedTokens tokens =
                tokenIssuer.issue(client, authentication, scopes, issuer);
        // 5. 留痕:与其它登录方式同一套风控与审计口径
        loginEventPublisher.publish(realm.realmKey(), user.id(), user.username(),
                LoginEventEvent.LOGIN_SUCCESS, "FEDERATION",
                request.getRemoteAddr(), request.getHeader("User-Agent"), null);
        return new MiniProgramLoginResponse(
                tokens.accessToken().getTokenValue(),
                tokens.refreshToken() == null ? null : tokens.refreshToken().getTokenValue(),
                tokens.accessToken().getTokenType().getValue(),
                tokens.expiresIn(),
                String.join(" ", scopes),
                CurrentUserResponse.from(principal));
    }

    /**
     * 客户端认证。
     * <p>复用协议栈的机密客户端认证器:密钥在库里是哈希值,比对必须走平台的
     * 口令编码器,自己拿明文比会永远失败。</p>
     */
    private RegisteredClient authenticateClient(String clientId, String clientSecret) {
        ClientSecretAuthenticationProvider provider =
                new ClientSecretAuthenticationProvider(registeredClientRepository, authorizationService);
        provider.setPasswordEncoder(passwordEncoder);
        try {
            Authentication authenticated = provider.authenticate(
                    new org.springframework.security.oauth2.server.authorization.authentication
                            .OAuth2ClientAuthenticationToken(clientId, clientSecret,
                            ClientAuthenticationMethod.CLIENT_SECRET_POST));
            return (RegisteredClient) authenticated.getPrincipal();
        } catch (OAuth2AuthenticationException e) {
            // 对外统一为凭证无效,不区分"客户端不存在"与"密钥错误"
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "客户端凭据无效");
        }
    }

    /** 授权范围:取客户端登记的范围,缺省用最小集合。 */
    private static Set<String> resolveScopes(RegisteredClient client) {
        if (client.getScopes() == null || client.getScopes().isEmpty()) {
            return DEFAULT_SCOPES;
        }
        return new LinkedHashSet<>(client.getScopes());
    }

    /** 当前身份域;无上下文回落 default。 */
    private static RealmModel currentRealm() {
        RealmContext context = RealmContextHolder.get();
        return context == null ? null : context.realm();
    }
}
```

- [ ] **Step 6: 暴露端点**

在 `AuthController` 中新增方法（构造注入 `MiniProgramLoginService`）：

```java
    /**
     * 小程序登录。
     * <p>客户端上送小程序 code 与自己的客户端凭据,平台换到外部身份后
     * 自动注册本地用户并签发令牌。小程序不接触任何长期密钥。</p>
     */
    @PostMapping("/login/mini-program")
    public Result<MiniProgramLoginResponse> miniProgramLogin(
            @Valid @RequestBody MiniProgramLoginRequest request,
            HttpServletRequest httpRequest) {
        return Result.success(miniProgramLoginService.login(
                request.provider(), request.jsCode(), request.clientId(),
                request.clientSecret(), httpRequest));
    }
```

补 import：`club.beenest.iam.authz.mini.MiniProgramLoginService`、`club.beenest.iam.authz.rest.dto.MiniProgramLoginRequest`、`club.beenest.iam.authz.rest.dto.MiniProgramLoginResponse`。

在 `IamSecurityConfiguration` 的放行名单里补一行（与现有登录接口同族）：

```java
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/login-mfa",
                                "/api/v1/auth/login/mini-program",
                                "/api/v1/auth/captcha", "/api/v1/auth/captcha-required",
                                "/api/v1/auth/idps").permitAll()
```

并把 CSRF 豁免名单里的 `/api/v1/auth/login` 换成路径前缀匹配,让小程序登录端点同样豁免（该端点不使用环境凭据,凭据在请求体里显式提交）：

```java
                        .ignoringRequestMatchers(new BearerTokenRequestMatcher(),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/login"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/login-mfa"),
                                PathPatternRequestMatcher.pathPattern("/api/v1/auth/login/mini-program"))
```

- [ ] **Step 7: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-server -am test -Dtest=IamMiniProgramLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add beenest-iam-authz beenest-iam-server
git commit -m "feat(authz): 新增小程序登录端点,联邦换身份后签发标准令牌"
```

---

### Task 8: 登录页只渲染可跳转的提供方

**Files:**
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/dto/IdentityProviderEntry.java`
- Modify: `beenest-iam-authz/src/main/java/club/beenest/iam/authz/rest/AuthController.java`
- Modify: `beenest-iam-web/src/main/resources/themes/nestid/login/login.html`
- Test: `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamMiniProgramLoginIntegrationTest.java`（追加一个用例）

- [ ] **Step 1: 追加失败的测试**

在 `IamMiniProgramLoginIntegrationTest` 中追加：

```java
    @Test
    @DisplayName("登录页入口列表:小程序类提供方不作为浏览器跳转项下发")
    void idpEntriesExcludeMiniProgramProvider() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-mini-entry-" + System.nanoTime();
        createMiniProvider(admin, alias, wechatBase);

        ResponseEntity<String> response = rest.exchange("/api/v1/auth/idps?realm=default", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(200, response.getStatusCode().value());
        for (JsonNode entry : dataOf(response.getBody())) {
            if (alias.equals(entry.path("alias").asText())) {
                assertTrue(!entry.path("browserLogin").asBoolean(),
                        "小程序类提供方不能出现在浏览器跳转入口里");
                return;
            }
        }
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q -pl beenest-iam-server -am test -Dtest=IamMiniProgramLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（`browserLogin` 字段不存在）。

- [ ] **Step 3: 下发能力位**

`IdentityProviderEntry` 增加字段：

```java
/**
 * 三方登录入口。
 * <p>登录页据此渲染第三方登录按钮;不含任何敏感配置
 * (客户端密钥、端点地址等一律不下发到浏览器)。</p>
 *
 * @param alias     提供方别名
 * @param displayName 展示名
 * @param providerId 提供方类型(oidc/wechat/...)
 * @param loginUrl  发起登录的地址
 * @param browserLogin 该类型是否支持浏览器跳转登录(小程序类为 false,登录页不渲染按钮)
 */
public record IdentityProviderEntry(
        String alias,
        String displayName,
        String providerId,
        String loginUrl,
        boolean browserLogin
) {
}
```

`AuthController.identityProviders()` 改为按注册表给出的能力位过滤并下发：

```java
    @GetMapping("/idps")
    public Result<List<IdentityProviderEntry>> identityProviders() {
        RealmContext context = RealmContextHolder.get();
        Long realmId = context == null ? null : context.realm().id();
        if (realmId == null) {
            return Result.success(List.of());
        }
        List<IdentityProviderEntry> entries = identityProviderStore.listByRealm(realmId).stream()
                .filter(model -> model.enabled())
                .map(model -> new IdentityProviderEntry(
                        model.alias(), model.displayName(), model.providerId(),
                        "/broker/" + context.realmKey() + "/" + model.alias() + "/login",
                        browserLogin(model.providerId())))
                .toList();
        return Result.success(entries);
    }

    /**
     * 该提供方类型是否支持浏览器跳转登录。
     * <p>类型未注册(扩展包被移除)时按不支持处理:登录页不应给出一个
     * 点下去必然失败的入口。</p>
     */
    private boolean browserLogin(String providerId) {
        return identityProviderRegistry.listProviderTypes().stream()
                .filter(type -> type.providerId().equals(providerId))
                .findFirst()
                .map(ProviderTypeInfo::browserLogin)
                .orElse(false);
    }
```

`AuthController` 构造注入新增 `IdentityProviderRegistry identityProviderRegistry`，补 import `club.beenest.iam.federation.registry.IdentityProviderRegistry` 与 `club.beenest.iam.core.spi.federation.ProviderTypeInfo`。

- [ ] **Step 4: 登录页跳过非跳转项**

`login.html` 的 `loadIdpEntries` 中，渲染按钮前先过滤：

```javascript
                    body.data.forEach(function (entry) {
                        /* 小程序类提供方没有浏览器跳转入口,不在此处渲染 */
                        if (entry.browserLogin === false) {
                            return;
                        }
                        var button = document.createElement('a');
                        ...
                    });
```

并在拉取完成后按实际渲染数量决定是否显示整块（避免只剩分隔线的空块）：

```javascript
                    if (list.children.length === 0) {
                        return;
                    }
                    block.style.display = '';
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-server -am test -Dtest=IamMiniProgramLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add beenest-iam-authz beenest-iam-web
git commit -m "feat(authz): 登录入口按提供方能力位下发,小程序类不渲染跳转按钮"
```

---

### Task 9: 控制台动态配置表单

**Files:**
- Modify: `beenest-iam-web/frontend/src/views/IdentityProvidersView.vue`
- Modify: `beenest-iam-web/frontend/src/lib/api.ts`
- Modify: `beenest-iam-web/frontend/src/lib/types.ts`
- Test: 前端构建（`npm run build`）作为验收

**Interfaces:**
- Consumes: `GET /api/v1/admin/identity-providers/provider-types` → `ProviderTypeInfo[]`（字段 `providerId` / `name` / `browserLogin` / `configProperties`），`ProviderConfigProperty` 字段 `name` / `label` / `type` / `required` / `secret` / `defaultValue` / `helpText`。
- Produces: 前端类型 `ProviderTypeInfo` 与 `ProviderConfigProperty`。

- [ ] **Step 1: 补前端类型**

在 `beenest-iam-web/frontend/src/lib/types.ts` 末尾追加：

```typescript
/** 提供方配置项声明(与后端 ProviderConfigProperty 对应)。 */
export interface ProviderConfigProperty {
  name: string;
  label: string;
  type: 'STRING' | 'TEXT' | 'PASSWORD' | 'NUMBER' | 'BOOLEAN' | 'URL';
  required: boolean;
  secret: boolean;
  defaultValue?: string | null;
  helpText?: string | null;
}

/** 提供方类型元数据(与后端 ProviderTypeInfo 对应)。 */
export interface ProviderTypeInfo {
  providerId: string;
  name: string;
  browserLogin: boolean;
  configProperties: ProviderConfigProperty[];
}
```

- [ ] **Step 2: 补 API 方法**

在 `beenest-iam-web/frontend/src/lib/api.ts` 中新增（与既有 `api<T>` 调用风格一致）：

```typescript
/** 查询已注册的提供方类型及其配置项声明。 */
export function providerTypes(): Promise<ProviderTypeInfo[]> {
  return api<ProviderTypeInfo[]>('/api/v1/admin/identity-providers/provider-types');
}
```

并补 `import type { ProviderTypeInfo } from './types';`（若该文件已有 types 的导入，合并进去）。

- [ ] **Step 3: 提供方类型下拉改为动态**

`IdentityProvidersView.vue`：

1. 把硬编码的 `PROVIDER_TYPES` 常量改为 `ref<FilterOption[]>([])`（`FilterOption` 从 `@/lib/types` 引入），并在 `onMounted` 时拉取：

```typescript
const providerTypes = ref<FilterOption[]>([]);
const providerTypeOptions = ref<ProviderTypeInfo[]>([]);

onMounted(async () => {
  try {
    const types = await providerTypesApi();
    providerTypeOptions.value = types;
    providerTypes.value = types.map((t) => ({ value: t.providerId, label: t.name }));
  } catch {
    /* 拉取失败不阻塞管理员:类型下拉退化为自由输入 */
    providerTypes.value = [];
  }
});
```

（`providerTypesApi` 为 Step 2 里新增的 `providerTypes` 函数的别名导入，避免与局部变量同名。）

2. 表单的 `providerId` 字段的 `options` 改为引用 `providerTypes`，并在拉取失败时允许自由输入：

```typescript
      {
        name: 'providerId',
        label: '提供方类型',
        type: 'select',
        required: true,
        options: providerTypes,
        help: '由容器内已注册的扩展包决定:引入扩展包即出现,移除即下线',
      },
```

3. `PROVIDER_LABEL` 改为按拉取到的类型元数据生成（保留一个静态兜底表）：

```typescript
const providerLabel = (providerId: string) =>
  providerTypeOptions.value.find((t) => t.providerId === providerId)?.name ||
  PROVIDER_LABEL[providerId] ||
  providerId;
```

把模板与列渲染里的 `PROVIDER_LABEL[row.providerId] || row.providerId` 换成 `providerLabel(row.providerId)`。

- [ ] **Step 4: 配置表单按类型声明动态渲染**

在 `IdentityProvidersView.vue` 中新增一个计算属性，把当前所选类型的配置项声明翻译成表单字段：

```typescript
/** 配置项类型 → 表单控件类型。 */
const CONFIG_FIELD_TYPE: Record<ProviderConfigProperty['type'], FieldDef['type']> = {
  STRING: 'text',
  TEXT: 'textarea',
  PASSWORD: 'password',
  NUMBER: 'number',
  BOOLEAN: 'switch',
  URL: 'text',
};

/** 当前所选提供方类型的配置项声明。 */
const selectedType = computed(() =>
  providerTypeOptions.value.find((t) => t.providerId === formValues.value.providerId),
);

/** 按类型声明生成的配置字段。 */
const configFields = computed<FieldDef[]>(() => {
  const type = selectedType.value;
  if (!type) return [];
  return type.configProperties.map((prop) => ({
    name: `config.${prop.name}`,
    label: prop.label,
    type: CONFIG_FIELD_TYPE[prop.type] ?? 'text',
    required: prop.required,
    help: prop.helpText ?? undefined,
    placeholder: prop.defaultValue ?? undefined,
  }));
});
```

（`formValues` 为 `SchemaForm` 的取值容器；若 `ResourceConfig.form.fields` 是静态数组，改成函数式 `fields: (isEdit) => [...]` 形式，由 `ResourceConfig` 支持——`types.ts` 的 `ResourceConfig.form.fields` 类型为 `FieldDef[] | ((isEdit: boolean, row?: T) => FieldDef[] | Promise<FieldDef[]>)`，用后者。）

在 `form.fields` 改为函数式后，按所选类型拼出字段：

```typescript
    fields: (isEdit) => [
      ...(isEdit ? [] : [
        { name: 'alias', label: '别名', required: true, mono: true, placeholder: '如 wx-mini' },
      ]),
      { name: 'displayName', label: '展示名', required: true, placeholder: '如 微信小程序' },
      {
        name: 'providerId',
        label: '提供方类型',
        type: 'select',
        required: true,
        options: providerTypes,
        help: '由容器内已注册的扩展包决定:引入扩展包即出现,移除即下线',
      },
      { name: 'enabled', label: '启用', type: 'switch', onText: '启用中' },
      ...configFields.value,
    ],
```

`toUpdate` 收集配置时把 `config.*` 前缀的字段还原成 `config` 对象：

```typescript
    toUpdate: (values) => {
      const config: Record<string, string> = {};
      Object.entries(values).forEach(([key, value]) => {
        if (!key.startsWith('config.')) return;
        const name = key.slice('config.'.length);
        if (value === undefined || value === null || value === '') return;
        config[name] = String(value);
      });
      const payload: Record<string, any> = {
        displayName: values.displayName,
        enabled: Boolean(values.enabled),
      };
      if (Object.keys(config).length) payload.config = config;
      return payload;
    },
```

> **注意**：机密项（`secret = true`）在编辑态留空表示保持原值，服务端已按机密规则处理（Task 2），前端只需原样提交空串或省略——本实现选择省略（`value === ''` 时不写入 `config`），与服务端「留空保留原值」的语义一致。

- [ ] **Step 5: 构建验证**

Run: `cd beenest-iam-web/frontend && npm run build`
Expected: 构建通过（无类型错误）。

- [ ] **Step 6: 提交**

```bash
git add beenest-iam-web/frontend
git commit -m "feat(console): 提供方类型与配置表单按后端元数据动态渲染"
```

---

### Task 10: server 装配与端到端集成测试

**Files:**
- Modify: `beenest-iam-server/pom.xml`
- Create: `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamWechatLoginIntegrationTest.java`
- Modify: `readme.md`

**Interfaces:**
- Consumes: Task 1~9 的全部产出。
- Produces: server 进程内可用的 `wechat-mini` / `wechat-web` 提供方类型。

- [ ] **Step 1: server 引入扩展包**

在 `beenest-iam-server/pom.xml` 的 `beenest-iam-provider-oidc` 依赖之后加：

```xml
        <!-- 第三方登录扩展包:引入即启用,移除即下线 -->
        <dependency>
            <groupId>club.beenest</groupId>
            <artifactId>beenest-iam-provider-wechat</artifactId>
        </dependency>
```

- [ ] **Step 2: 写端到端测试**

创建 `beenest-iam-server/src/test/java/club/beenest/iam/integration/IamWechatLoginIntegrationTest.java`：覆盖两条完整链路。

```java
package club.beenest.iam.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微信登录端到端集成测试。
 * <p>两条链路:小程序登录(jsCode 换身份 → 自动建号 → 令牌可用)与
 * 扫码登录(浏览器跳微信 → 回调建会话)。扩展包被引入 server 后,
 * 这两条链路必须在真实容器里跑通,否则"配好了但登不进来"只能靠用户投诉发现。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IamWechatLoginIntegrationTest extends IamIntegrationTestBase {

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

    private HttpServer wechat;

    @AfterEach
    void stopWechat() {
        if (wechat != null) {
            wechat.stop(0);
        }
    }

    @Test
    @DisplayName("提供方类型元数据里出现微信两类扩展包")
    void providerTypesIncludeWechat() throws Exception {
        Session admin = login();
        ResponseEntity<String> response = exchange(admin, HttpMethod.GET,
                "/api/v1/admin/identity-providers/provider-types", null);
        assertEquals(200, response.getStatusCode().value());
        boolean mini = false;
        boolean web = false;
        for (JsonNode type : dataOf(response.getBody())) {
            if ("wechat-mini".equals(type.path("providerId").asText())) {
                mini = true;
                assertTrue(!type.path("browserLogin").asBoolean(), "小程序类不应支持浏览器跳转");
                assertTrue(type.path("configProperties").size() > 0, "应当声明配置项");
            }
            if ("wechat-web".equals(type.path("providerId").asText())) {
                web = true;
                assertTrue(type.path("browserLogin").asBoolean(), "扫码类支持浏览器跳转");
            }
        }
        assertTrue(mini, "扩展包引入后应当出现 wechat-mini 类型");
        assertTrue(web, "扩展包引入后应当出现 wechat-web 类型");
    }

    @Test
    @DisplayName("扫码登录:发起跳微信授权地址,回调后建立登录态")
    void scanLoginEstablishesSession() throws Exception {
        Session admin = login();
        String wechatBase = startWechat();
        String alias = "wx-web-" + System.nanoTime();
        createWebProvider(admin, alias, wechatBase);

        ResponseEntity<String> start = rest.exchange("/broker/default/" + alias + "/login", HttpMethod.GET,
                new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, start.getStatusCode().value());
        String location = start.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "应当重定向到微信扫码授权页");
        assertTrue(location.startsWith("https://open.weixin.qq.com/connect/qrconnect?appid="),
                "应当跳到微信扫码授权页: " + location);
        assertTrue(location.endsWith("#wechat_redirect"), "微信要求地址带该片段: " + location);

        String state = queryParam(location, "state");
        ResponseEntity<String> callback = rest.exchange(
                "/broker/default/" + alias + "/callback?code=mock-code&state=" + state,
                HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), String.class);
        assertEquals(302, callback.getStatusCode().value(), "回调应当重定向: " + callback.getBody());
        String cookie = joinCookies(callback.getHeaders().get(HttpHeaders.SET_COOKIE));
        assertNotNull(cookieValue(cookie, "IAM_SESSION"), "扫码登录应当建立会话: " + cookie);
    }

    // ===== 内部实现 =====

    private String startWechat() throws IOException {
        wechat = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        wechat.createContext("/sns/oauth2/access_token", exchange -> {
            respond(exchange, "{\"access_token\":\"mock-access\",\"openid\":\"o-web-1\",\"unionid\":\"u-web-1\"}");
        });
        wechat.createContext("/sns/userinfo", exchange -> {
            respond(exchange, "{\"openid\":\"o-web-1\",\"nickname\":\"李四\"}");
        });
        wechat.start();
        return "http://127.0.0.1:" + wechat.getAddress().getPort();
    }

    private void createWebProvider(Session admin, String alias, String wechatBase) throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "wx-open-id");
        config.put("clientSecret", "wx-open-secret");
        config.put("authorizationUrl", "https://open.weixin.qq.com/connect/qrconnect");
        config.put("tokenUrl", wechatBase + "/sns/oauth2/access_token");
        config.put("userInfoUrl", wechatBase + "/sns/userinfo");
        ResponseEntity<String> created = exchange(admin, HttpMethod.POST,
                "/api/v1/admin/identity-providers", objectMapper.writeValueAsString(
                        Map.of("alias", alias, "providerId", "wechat-web",
                                "displayName", "微信扫码登录", "enabled", true, "config", config)));
        assertTrue(created.getStatusCode().is2xxSuccessful(), "登记提供方应当成功: " + created.getBody());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record Session(String cookie, String csrf) {
    }

    private Session login() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("username", "admin", "password", ADMIN_PASSWORD)), headers), String.class);
        assertEquals(200, response.getStatusCode().value(), "登录应当成功: " + response.getBody());
        String cookie = joinCookies(response.getHeaders().get(HttpHeaders.SET_COOKIE));
        return new Session(cookie, cookieValue(cookie, "XSRF-TOKEN"));
    }

    private ResponseEntity<String> exchange(Session admin, HttpMethod method, String path, String body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", admin.csrf());
        headers.set(HttpHeaders.COOKIE, admin.cookie());
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String joinCookies(List<String> setCookies) {
        if (setCookies == null) {
            return "";
        }
        return String.join("; ", setCookies.stream().map(c -> c.split(";", 2)[0]).toList());
    }

    private static String cookieValue(String cookie, String name) {
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String queryParam(String url, String name) {
        if (url == null) {
            return null;
        }
        int index = url.indexOf(name + "=");
        if (index < 0) {
            return null;
        }
        int start = index + name.length() + 1;
        int end = url.indexOf('&', start);
        return end < 0 ? url.substring(start) : url.substring(start, end);
    }
}
```

- [ ] **Step 3: 运行测试确认通过**

Run: `./mvnw -q -pl beenest-iam-server -am test -Dtest=IamWechatLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 4: 全量回归**

Run: `./mvnw -q clean test`
Expected: 全部测试通过（原有 128 个用例 + 新增用例）。

- [ ] **Step 5: 更新 readme 能力清单**

在 `readme.md` 的能力清单里补一行，说明微信登录支持小程序与扫码两种形态、且以扩展包形式交付；并在「已知能力边界」里注明：小程序登录需客户端绑定应用服务否则令牌不带权限；开放平台网站应用需企业认证。

- [ ] **Step 6: 提交**

```bash
git add beenest-iam-server readme.md
git commit -m "feat(wechat): server 引入微信扩展包并补齐端到端集成测试"
```

---

### Task 11: 接入文档

**Files:**
- Create: `docs/wechat-integration.md`

**Interfaces:**
- Consumes: Task 1~10 的最终形态。
- Produces: 面向接入方的文档。

- [ ] **Step 1: 写文档**

`docs/wechat-integration.md` 至少覆盖以下六节（每节都要写清「做什么、怎么配、怎么调、出错怎么办」）：

1. **小程序登录**：微信公众平台侧准备（小程序 AppID/AppSecret、request 合法域名）、平台侧配置（接入方提供方、appId/appSecret）、客户端调用（`wx.login()` → 后端 → `POST /api/v1/auth/login/mini-program?realm={realmKey}`）、请求与响应示例、令牌用法（`Authorization: Bearer`）、刷新（标准令牌端点 `grant_type=refresh_token`）、注销（标准令牌吊销端点）。
2. **扫码登录**：开放平台「网站应用」申请与认证要求、回调地址登记规则（必须与平台 broker 回调地址一致）、平台侧配置、登录页表现（用户点击入口 → 微信二维码页）。
3. **用户自动注册与 openid**：首次登录自动建号（登录名规则）、openid/unionid 落在用户扩展属性、外部标识取值规则（unionid 优先）、同一微信用户在小程序与扫码两入口何时能被认出是同一人。
4. **配置项说明**：两个类型各自的配置项含义、哪些是机密项（不回显、编辑留空保留原值）、`apiBaseUrl` 等端点覆盖项的用途与风险。
5. **接入方责任清单**：客户端密钥只由后端持有、小程序不得内置密钥、域名白名单、密钥泄露的处置（轮换）。
6. **故障排查**：jsCode 失效（40029）、openid 不一致、令牌无权限声明（客户端未绑定应用服务）、扫码页空白（授权地址参数顺序/片段错误）、第三方不可用（IDP_UNAVAILABLE / HTTP 502）。

- [ ] **Step 2: 自检**

Run: `rg -n "TBD|TODO|待补充|xxx" docs/wechat-integration.md`
Expected: 无输出（不允许占位）。

- [ ] **Step 3: 提交**

```bash
git add docs/wechat-integration.md
git commit -m "docs: 微信登录接入文档(小程序与扫码)"
```

---

## 自检

**规格覆盖核对**：

| 规格章节 | 对应任务 |
| --- | --- |
| §5.1 两个提供方类型 | Task 4、Task 5 |
| §5.2 `wechat-mini` 配置与换取 | Task 4 |
| §5.3 `wechat-web` 配置与换取 | Task 5 |
| §6.1~6.4 小程序登录端点与令牌 | Task 7 |
| §7 用户自动注册与 openid 记录 | Task 6、Task 7（集成测试断言 openid 落库） |
| §8.1~8.4 类型元数据与动态表单 | Task 1、Task 9 |
| §9 机密配置处理 | Task 2 |
| §10 多实例与可观测 | Task 7（令牌与授权记录均在 Redis，无进程内状态） |
| §11 测试策略 | Task 4~7、Task 10 |
| §12 交付物 | Task 10（server 装配）、Task 11（文档） |
| §13 实施顺序 | 与本计划任务顺序一致 |

**已知与规格的偏差（有意为之，实施时按此为准）**：

1. 规格 §7 写「openid 需管理员配映射规则才落库」——**与代码实际不符**：`IdpMappingService.apply` 会把外部属性全量拷进本地扩展属性，openid 无需映射规则即落库。已按代码实际修正，Task 7 的集成测试直接断言 `attributes.openid`。
2. 规格 §6.2 的端点响应体是平铺的令牌字段——实现改为 `Result<MiniProgramLoginResponse>` 包裹（与平台其它接口一致），字段名不变。
3. 规格未提「小程序登录的令牌需要客户端绑定应用服务才有权限声明」——这是平台既有的默认拒绝策略（`IamJwtTokenCustomizer.applyAuthorizationClaims`），已写入 Task 11 文档的故障排查一节。
