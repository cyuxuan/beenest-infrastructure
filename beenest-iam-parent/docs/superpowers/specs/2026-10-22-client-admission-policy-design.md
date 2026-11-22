# 客户端准入策略(Client Admission Policy)设计

- 编制日期:2026-10-22
- 状态:待评审
- 关联:对比分析 `docs/keycloak-comparison.html` 第三节 P1-2「认证流可配置」
- 前置:本设计的取舍在 `beenest-iam-parent` 仓库现状下核实,关键结论见附录 A

## 1. 背景与目标

### 1.1 问题

平台当前的认证方式是**域级一刀切**:`iam_login_policy` 决定本域是否开启短信登录、是否强制 MFA、失败多少次要验证码,所有客户端共享同一套规则。接入方无法表达"这个应用只让用手机号""那个高敏系统必须二次验证"。

这在国内场景里是硬需求:同一套 IAM 平台既要接内部老系统(走 AD/口令),又要接面向外部合作方的 App(走手机号),还要接财务类高敏系统(必须 MFA)。域级配置无法同时满足。

### 1.2 目标

1. 客户端可声明**允许的第一因素集合**(PASSWORD / LDAP / SMS / WECHAT / OIDC / SAML)与**是否强制 MFA**
2. 认证方式不满足客户端要求时,走**可操作的补验流程**,而不是把用户挡在门外
3. 登录页与挑战页**只展示本次真正可用的登录方式**
4. 全部能力复用平台既有的 Redis 会话、审计、登录事件与权限码体系,不引入新的架构概念

### 1.3 非目标

- **不做域级风险规则引擎**。文档 P1-2 提到的"异地 IP / 新设备 / 非工作时段"需要设备指纹、地理位置、工作日历等特征数据,平台当前一条都没有(`iam_user_session` 只有 ip 与 user_agent)。先造规则引擎等于先造一个空转的壳。特征数据齐备后再单独立项。
- **不做按应用的准入策略**。配置权威位置是客户端,理由见 §2.1。
- **不做多因子组合策略**(如"口令 + 短信"双第一因素)。`allowed_login_types` 是**择一**语义,不是组合。

## 2. 设计决策

### 2.1 策略挂在客户端,不挂在门户应用

`iam_application_service` 通过 `sso_client_pk` 指向客户端。看起来"按应用配置"更符合管理员心智,但:

- 客户端可以被**多个应用引用**,挂在应用上会出现"一个应用改了策略,另一个应用跟着变"
- 应用可以**自带 SSO 发起端点**(`ssoSelfInitiated`)直连授权端点,此时请求里只有 `client_id`,查不到应用
- 准入判定发生在授权端点,那里唯一稳定存在的标识就是 `client_id`

结论:配置落 `iam_client`,应用侧不提供配置入口。

### 2.2 判定点在授权端点,不在登录页

登录页只做**展示过滤**(少显示几个 Tab),不承担准入判定。理由:

- 登录页可能没有客户端上下文(门户直达 `/portal`、launchpad 启动)
- 用户可以"用方法 A 登录 → 带已有会话访问只收 B 的客户端",只有授权端点能拦住这种复用

### 2.3 不匹配时降级为补验,不直接拒绝

联邦登录(微信扫码 / OIDC / SAML)的时序决定了这一点:回调时用户已经建好会话(`IamFederationController:185`),那时还不知道用户接下来要进哪个客户端。直接拒绝的表现是"我明明登录了怎么又被踢"。

补验流程:签发一次性票据 → 跳挑战页 → 用允许的方式认证 → **升级会话**而非重建会话 → 回到原授权请求。

### 2.4 严格模式 + 配置期校验

白名单是硬约束:会话的 `loginType` 不在白名单内即需补验。域级可用的方式不构成兜底。

为避免"配置了但永远登不进去"的死配置,管理端保存时校验**白名单 ∩ 域级可用方式 ≠ ∅**,交集为空则拒绝保存并说明原因。冲突挡在配置期,不留在运行期。

### 2.5 补验升级会话,不新建会话

补验的本质是"证明同一个用户满足更强的要求",不是"再登录一次"。因此补验成功后**升级**原会话(`loginType` 改写、`mfaVerified` 置位),而不是签发新会话。

理由:

- **审计语义清晰**:一个会话的认证状态从 `WECHAT` 演进为 `PASSWORD`,是一条可读的状态变化;两个会话加一次"用户重新登录了"是噪声
- **不制造会话膨胀**:同一时刻浏览器里只有一个有效会话
- **不延长会话最大存续时长**:补验不能成为续期后门(见 §5.3)

### 2.6 被否决的方案

| 方案 | 否决原因 |
|---|---|
| 按域可编排的策略链(条件/分支/步骤) | 配置面爆炸,平台当前无特征数据可求值,绝大多数规则无法落地 |
| 策略挂在门户应用 | 见 §2.1 |
| 不匹配直接拒绝 | 见 §2.3 |
| 白名单语义为"优先引导"(域级方式兜底) | "只收短信的应用"可能被动收到口令登录,安全语义被稀释 |
| 补验时新建会话 | 见 §2.5 |
| 把策略存进 `RegisteredClient.getMetadata()` | SAS 7.0.7 的 `RegisteredClient` **已移除** `getMetadata()`(见附录 A.2),改用 `ClientSettings.setting()` |

## 3. 组件设计

新增包 `club.beenest.iam.authz.admission`(在 `beenest-iam-authz` 模块):

| 组件 | 职责 |
|---|---|
| `LoginMethodCatalog` | 第一因素词表常量;**域级可用方式**的唯一计算入口(登录页过滤、挑战页渲染、管理端校验、准入判定共用一份口径) |
| `ClientAdmissionPolicy` | record:`allowedLoginTypes`(空 = 不限制)、`requireMfa`;提供 `admits(loginType)`、`allowsMfa()` |
| `AdmissionChallenge` | record:`sid`、`clientId`、`allowedLoginTypes`、`requireMfa`、`returnTo`、`challengeToken`、`createdAt` |
| `AdmissionChallengeService` | Redis 挑战票据的签发 / 读取 / 消费;**双索引**(sid 索引给登录路径、token 索引给跨站回跳,详见 §6.1) |
| `ClientAdmissionFilter` | 协议链过滤器,在授权端点前执行准入判定与挑战发起 |

`ClientAdmissionPolicy` 的装配有两个来源:

- **协议链**:从 `RegisteredClient.getClientSettings()` 读(§3.3),零额外查询
- **管理端 / 登录页**:从 `iam_client` 读,经 `ClientProvider`

## 4. 数据模型

### 4.1 迁移 `V1.2.1__client_admission_policy.sql`

```sql
-- 客户端准入策略:允许的第一因素集合(空 = 不限制)与是否强制 MFA
ALTER TABLE iam_client
    ADD COLUMN IF NOT EXISTS allowed_login_types VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE iam_client
    ADD COLUMN IF NOT EXISTS require_mfa BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN iam_client.allowed_login_types IS
    '客户端允许的第一因素集合(PASSWORD/LDAP/SMS/WECHAT/OIDC/SAML),逗号分隔;空 = 不限制';
COMMENT ON COLUMN iam_client.require_mfa IS
    '客户端强制 MFA;与域级 iam_login_policy.force_mfa 叠加,任一为真即要求二步验证';
```

### 4.2 领域模型

`ClientModel`(core)新增两个分量:

```java
/** 允许的第一因素集合;空集合表示不限制 */
Set<String> allowedLoginTypes,
/** 是否强制 MFA */
boolean requireMfa,
```

`ClientModel` 是 record,新增分量会波及所有构造点。构造点集中在 `ClientConverter`(persistence)与测试,需全量编译修正。

`Client`(persistence entity)同步加两个字段,`ClientMapper.xml` 的 select / insert / update 同步。

### 4.3 策略随 RegisteredClient 下发

SAS 7.0.7 的 `RegisteredClient` 没有 `getMetadata()`,但 `ClientSettings` 继承 `AbstractSettings`,后者提供泛型 `setting(String, Object)` 与 `<T> T getSetting(String)`。

`IamRegisteredClientRepository.toRegisteredClient` 中:

```java
.clientSettings(ClientSettings.builder()
        .requireProofKey(model.requirePkce())
        .requireAuthorizationConsent(model.requireConsent())
        .setting(SETTING_ALLOWED_LOGIN_TYPES, String.join(",", model.allowedLoginTypes()))
        .setting(SETTING_REQUIRE_MFA, model.requireMfa())
        .build())
```

读取侧:

```java
String types = registeredClient.getClientSettings().getSetting(SETTING_ALLOWED_LOGIN_TYPES);
Boolean requireMfa = registeredClient.getClientSettings().getSetting(SETTING_REQUIRE_MFA);
```

**收益**:授权端点判定时 SAS 已经取过 `RegisteredClient`,策略随对象带过来,**判定路径零额外查询**。

**约束**:写入的值必须可序列化(`String` / `Boolean` 满足),且 `getSetting` 的类型要与写入时一致。

### 4.4 域级可用方式

判定"域级可用方式"需要域级策略。`IamSecurityPolicyManager.getLoginPolicy(realmId, realmKey)` 已带 Redis 缓存(`LOGIN_POLICY_CACHE`,5 分钟),直接复用,不新增缓存层。

| 方式 | 域级可用条件 | 数据来源 |
|---|---|---|
| PASSWORD | 恒可用 | — |
| LDAP | 该域配置了 LDAP 且启用 | `iam_realm_ldap_config` |
| SMS | `sms_login_enabled` 且 `iam.sms.channel` 已配置 | `iam_login_policy` + `IamSmsProperties` |
| WECHAT | 该域注册了 WECHAT 类型提供方 | `iam_identity_provider` |
| OIDC | 该域注册了 OIDC 类型提供方 | `iam_identity_provider` |
| SAML | 该域注册了 SAML 类型提供方 | `iam_identity_provider` |

`LoginMethodCatalog` 封装这张表,提供 `Set<String> availableInRealm(Long realmId, String realmKey)`。

## 5. 第一因素词表与会话模型

### 5.1 词表

现有登录事件与会话表只记 `PASSWORD / SMS / FEDERATION` 三值,粒度不足以做白名单判定(用目录口令登录会被记成 PASSWORD,用微信登录与用企业 OIDC 登录无法区分)。

| 值 | 含义 | 打标位置 |
|---|---|---|
| `PASSWORD` | 本地 PASSWORD 凭证行校验通过 | `IamAuthenticationProvider` 默认路径 |
| `LDAP` | 目录 bind 兜底认证通过 | `IamAuthenticationProvider` LDAP 分支 |
| `SMS` | 手机号 + 短信验证码 | `AuthController` 的 `/api/v1/auth/login-sms` |
| `WECHAT` | 微信小程序登录 / 开放平台扫码 | `IamFederationController` 回调 |
| `OIDC` | 外部 OIDC 提供方 | 同上 |
| `SAML` | 外部 SAML IdP(平台作 SP) | 同上 |

TOTP 是第二因素,**不进第一因素白名单**。

### 5.2 载体选择:主体携带 loginType

打标改造点:

**`IamAuthenticationProvider`(第 147–153 行附近)**:LDAP 兜底分支当前对用户透明——目录校验通过后仍按 PASSWORD 记。改为在构建主体时带上实际方式。

**`IamFederationController`(第 185 行)**:当前统一 `establish(..., "FEDERATION")`。改为按提供方类型分 `WECHAT` / `OIDC` / `SAML`。提供方类型在回调时已可从 `IdentityProvider` 拿到。

载体有三种选择:

- **A. `AuthenticationDetails`**:把方式放进 `details`。但 `IamPrincipalFactory.toAuthentication` 只从 principal 重建 Authentication,details 会丢。
- **B. 主体携带**:`IamUserPrincipal` 加 `loginType` 分量。
- **C. 会话快照单独存**:`IamSessionPayload` 加 `loginType`,与主体平行。

**选 B**。理由:主体本来就是"登录成功后挂在 Authentication 上的用户快照",且它同时进 Redis 会话快照与令牌签发路径。放在主体里,`IamSessionPayload` 直接取 `principal.getLoginType()`,不必再单独存一份(排除 C 的双写不一致风险);令牌侧也能据此决定是否需要下发额外声明。

`IamUserPrincipal` 是 final 类 + Jackson 反序列化,新增 `String loginType` 分量需要同步 `@JsonCreator` 构造器参数。**存量 Redis 主体快照缺该字段时为 null**,判定时按 `UNKNOWN` 处理(见 §7.3)。

### 5.3 会话模型变更

**`IamSessionPayload` 新增 `Boolean mfaVerified`**。客户端级 `require_mfa` 需要知道"当前会话是否已完成二步验证"。域级 `force_mfa` 是在**登录时**强制的,登录不通过就不会有会话;但客户端级要求可能晚于登录发生(用户先正常登录,再进高敏应用),因此会话必须记录 MFA 完成态。

**包装类型的原因**:`IamSessionPayload` 是 record,`boolean` 是原始类型,Jackson 反序列化时缺失该字段会抛异常(无法置 null)。用 `Boolean` 则缺失时为 null,语义为"未完成 MFA",安全侧默认。存量兼容问题同样适用于 `IamUserPrincipal.loginType`。

**会话升级**:`IamLoginSessionService` / `IamUserSessionService` 新增:

```java
/** 补验成功:升级会话的登录方式与 MFA 完成态(不重建会话,保留 sid 与存续起算点) */
void upgradeAuthentication(String sid, String loginType, Boolean mfaVerified);
```

实现要点:
- 读 Redis 快照 → 改字段 → 回写(**TTL 按剩余时长重算,不重置为满**)
- 同步更新 `iam_user_session` 行的 `login_type`(该行已存在该列)
- `createdTime` / `maxLifetimeSeconds` / `idleSeconds` / `lastTouchMillis` 保持不变——**补验不能延长会话最大存续时长**,否则用户可以靠反复补验无限续期

## 6. 挑战票据

### 6.1 存储形态

新增 Redis key(两条,常量 `ADMISSION_CHALLENGE_SID` 与 `ADMISSION_CHALLENGE_TOKEN` 加入 `IamRedisKeyConstants`):

- `iam:{realm}:admission:sid:{sid}` — 按会话标识索引
- `iam:{realm}:admission:token:{token}` — 按一次性令牌索引

**必须双索引。** 两条路径的定位方式不同:

- **登录路径**(表单 / REST / 联邦回调回到平台后建立会话)按 **sid** 定位。挑战必然发生在"用户已有会话"的前提下(没有会话时准入判定直接放行,交给登录页),因此 sid 一定存在。按 sid 索引让登录路径无需在表单里传递任何票据参数——建立会话的那一步自己就能查到"本会话是否有待处理的挑战"。
- **跨站联邦回跳**(SAML 走 HTTP-POST 绑定)按 **token** 定位。平台会话 Cookie 是 `SameSite=Lax`,跨站 POST 不带 Cookie,此时 sid 根本拿不到,只有随回跳带回来的令牌能定位到票据。

只做 sid 索引会让 SAML 场景的用户永远补验不完;只做 token 索引则每次登录都要在表单里传参。两条都写、消费时两条都删。

票据值(JSON):`{ sid, clientId, allowedLoginTypes, requireMfa, returnTo, challengeToken, createdAt }`

`challengeToken` 是独立于 sid 的随机值(`UUID.randomUUID()` 去横线),既作 token 索引的键,又作挑战页表单的 CSRF 防护令牌:挑战页 URL 带 token,提交时必须原样回传。sid 本身是会话标识,出现在 URL 里会泄露会话。

TTL 5 分钟,与 `MfaPendingService` 一致。

### 6.2 回跳地址与开放重定向防护

`returnTo` 存**原始授权端点请求的 path + query**。

回跳前校验 `returnTo` 以当前域的授权端点路径开头(形如 `/realms/{realmKey}/oauth2/authorize`)。不满足则丢弃票据并记 `CLIENT_ADMISSION_DENIED`。票据由平台自己签发且只记录平台自己的端点,但这条路径最终要重定向浏览器,不能省。

### 6.3 挑战的两个类型

| 类型 | 触发条件 | 需要补的东西 |
|---|---|---|
| `FIRST_FACTOR` | 会话 `loginType` ∉ 客户端白名单 | 用白名单内的方式重新认证(升级 loginType) |
| `MFA` | 客户端 `requireMfa` 为真且会话 `mfaVerified` 非真 | 补 TOTP / 恢复码(置位 mfaVerified) |

`FIRST_FACTOR` 优先:第一因素都不满足时先解决第一因素,补验成功后若仍需 MFA,再走 `MFA` 挑战(两级挑战串联)。

## 7. 判定流程

### 7.1 判定位置

协议链(`IamSecurityConfiguration.authorizationServerFilterChain`)新增过滤器:

```java
.addFilterBefore(new ClientAdmissionFilter(...), OAuth2AuthorizationEndpointFilter.class)
```

**排序约束**:该过滤器必须排在 `RealmContextFilter` **之后**。它要经 `RegisteredClientRepository` 取客户端,而 `IamRegisteredClientRepository.findByClientId` 依赖 `RealmContextHolder` 中的域上下文。协议链已有 `addFilterBefore(realmContextFilter, AuthorizationFilter.class)`,实现时需验证两个过滤器的实际先后,必要时改用 `addFilterAfter(..., RealmContextFilter.class)`。

`OAuth2AuthorizationEndpointFilter` 在 SAS 7.0.7 的构造函数是 `(AuthenticationManager)` / `(AuthenticationManager, String)`,**已不再接受 `AuthorizationRequestRepository`**(附录 A.1),因此没有"改造协议栈内部过滤器"的路径,外挂 Filter 是唯一干净落点。

### 7.2 判定逻辑

```
仅在 GET 授权端点生效
  ├─ 取请求参数 client_id
  ├─ 查 RegisteredClient(平台已有 RegisteredClientRepository,按域过滤)
  ├─ 取策略:allowedLoginTypes(空 = 不限制)、requireMfa
  ├─ 两者皆空 → 放行
  ├─ 取当前会话主体(Redis 会话快照)
  │   ├─ 无会话 → 放行(交由登录页处理,登录页按策略展示过滤)
  │   └─ 有会话
  │       ├─ loginType ∈ allowedLoginTypes(或白名单为空) → 进入 MFA 判定
  │       └─ 否则 → 发起 FIRST_FACTOR 挑战
  └─ MFA 判定:
      ├─ 客户端 requireMfa 为假 且 域级 force_mfa 为假 → 放行
      ├─ 会话 mfaVerified = TRUE → 放行
      └─ 否则 → 发起 MFA 挑战
```

### 7.3 UNKNOWN 的处理

存量会话(升级前签发)的主体 `loginType` 为 null。判定时:

- 白名单为空 → 不受影响,放行
- 白名单非空 → `UNKNOWN` 不在任何白名单里 → **要求补验一次**。补验成功后写入真实 `loginType`

这是安全侧默认:无法证明会话的认证方式满足要求时,要求重新证明。升级上线后,存量会话最多补验一次即恢复正常。

## 8. 补验流程

### 8.1 统一落点:`IamLoginSessionService.establish`

三条登录路径——表单登录(`IamAuthenticationSuccessHandler`)、REST 登录(`AuthController`)、联邦回调(`IamFederationController`)——**最终都汇聚到 `IamLoginSessionService.establish(request, response, principal, loginType)`**。把升级逻辑放进这个方法,只需改一处:

```java
public void establish(request, response, principal, loginType) {
    establish(request, response, principal, loginType, null);
}

public void establish(request, response, principal, loginType, challengeToken) {
    // 1. 定位本会话待处理的准入挑战
    //    优先用显式 token(跨站联邦回跳时浏览器不会带会话 Cookie,见 §8.2),
    //    否则按当前会话 sid 反查
    AdmissionChallenge challenge = admissionChallengeService.resolve(realmKey, challengeToken, currentSid(request));
    if (challenge != null) {
        // 1.1 服务端校验本次认证方式是否在挑战允许的集合内(不信任前端)
        if (challenge.allows(loginType)) {
            // 1.2 消费挑战,升级会话(不新建)
            admissionChallengeService.consume(challenge);
            userSessionService.upgradeAuthentication(challenge.sid(), loginType, currentMfaVerified);
            return;
        }
        // 1.3 方式不匹配:本次登录按普通登录处理,挑战票据保留待下次尝试
        //     (新会话的 sid 与挑战票据的 sid 不同,旧票据到期自动清理)
    }
    // 2. 既有建会话流程
    ...
}
```

**"方式不匹配时按普通登录处理"**是有意为之:用户可能正要去访问门户而非受限应用,不该被挑战拦住。旧挑战票据随 TTL 自动过期。

**升级而非新建**带来一个额外要求:`mfaVerified` 在升级时**保留原值**。若原会话已通过 MFA,升级 loginType 不应把 MFA 状态抹掉。

### 8.2 方式挑战

```
GET /login-challenge?token=xxx
  ├─ 按 sid 查挑战票据,校验 token 一致
  ├─ 计算「可展示的方式」= 白名单 ∩ 域级可用方式
  ├─ 渲染挑战页(只展示这些方式)
  └─ 用户选择一种方式认证
       ├─ PASSWORD / LDAP → 走现有表单登录(POST /login)
       ├─ SMS             → 走现有短信登录接口(POST /api/v1/auth/login-sms)
       └─ WECHAT/OIDC/SAML → 走现有 broker 发起(GET /broker/{realm}/{alias}/login),
                             发起时把 challengeToken 带进 broker state
  ↓
认证成功 → establish() 检测到待处理挑战 → 升级会话 → 重定向回 returnTo
```

**挑战页不新造认证逻辑**,复用各登录方式既有入口。关键点:升级判定发生在 `establish` 内部,登录入口本身不需要感知挑战的存在。

**联邦方式跨异步跳转的挑战上下文传递**:用户从挑战页点"微信登录"会跳到微信再回跳,跨越多次重定向。会话 Cookie 未必能跟回来——平台会话 Cookie 是 `SameSite=Lax`,**SAML 走 HTTP-POST 绑定回跳时是跨站 POST,浏览器不带 Cookie**( Lax 只允许顶级 GET 导航携带)。

因此挑战票据的定位不能只依赖 sid。处理方式:

- 挑战票据**双索引**(§6.1):按 sid 索引给登录路径用,按 token 索引给跨站回调用
- 挑战页发起 broker 时,把 `challengeToken` 放进 broker 的 state 参数(`FEDERATION_STATE` 已在 Redis,平台现成机制);SAML 走 RelayState
- 回调时 `IamFederationController` 从 state/RelayState 取回 `challengeToken`,以显式 token 调 `establish(..., challengeToken)`
- `AdmissionChallengeService.resolve(realmKey, challengeToken, sid)` 的定位顺序:**显式 token 优先(查 token 索引),其次按 sid 查**

两条定位路径都拿不到挑战 → 视为普通登录,新建会话。

`IamFederationController` 还需要拿到 `returnTo` 决定回跳目标:同样从挑战票据读(按 sid 或 token 均可)。

### 8.3 MFA 挑战

用户会话已存在,只需补第二因素,不重新走完整登录。

- `MfaPendingService` 增加按会话签发的方法:票据值存 `userId:sid`
- 新增 REST 端点 `POST /api/v1/auth/login-mfa-session`:消费票据 → 校验动态口令/恢复码 → **升级现有会话**的 `mfaVerified=TRUE` → 回跳 `returnTo`
- 挑战页在 `MFA` 类型时**只展示 MFA 步骤**,不展示第一因素

MFA 校验逻辑(`mfaService.verifyLoginCode`)完全复用,包括失败计数与防重放。

### 8.4 挑战页

新增 `/login-challenge` 页面(GET 渲染 + POST 提交),主题化模板,复用登录页的样式与交互组件(OTP 分格输入、CapsLock 提示、口令显隐等)。

模板位置:`classpath:/themes/{active}/login/challenge.html`。**注意**:租户主题已支持按域选择目录(`V1.1.7__realm_theme.sql`),挑战页也要走 `IamThemeSelector` 解析,不能硬编码目录。

## 9. 展示过滤

### 9.1 登录页

现状:`LoginPageController.isSmsLoginEnabled(realmKey)` 只看域级开关。

**新的过滤规则**:`可展示的方式` = `白名单 ∩ 域级可用方式`(白名单为空时取全部域级可用方式)。

**口令登录也参与过滤**。若白名单不含 PASSWORD 却仍展示口令表单,用户体验是"密码输对了却被弹走"——比不展示更糟。因此白名单不含 PASSWORD 时,登录页不展示口令表单。

**客户端上下文来源**:`IamAuthenticationEntryPoint` 在把未认证请求重定向到 `/login` 时,手里有原始请求(授权端点 URL)。由它解析 `client_id` 并作为独立参数带给登录页:

```
/login?realm=xxx&client_id=yyy&return_to=...
```

**不从 `return_to` 里解析 client_id**——`return_to` 是站内相对路径,解析它等于把登录页的入参面扩大;而 `client_id` 由平台自己在入口点解析,来源可控。协议链的未认证入口点也是 `IamAuthenticationEntryPoint`(`IamSecurityConfiguration` 协议链段),授权端点未登录时同样经它跳转。

按过滤结果调整:
- 短信 Tab:`smsLoginEnabled` 且白名单允许 `SMS`
- 三方登录入口(微信 / OIDC / SAML):现状 `/api/v1/auth/idps` 返回全部提供方,需按客户端过滤
- 口令表单:白名单允许 `PASSWORD`(或 `LDAP`,LDAP 走同一条口令路径)时展示

**无客户端上下文时**(门户直达)不过滤,展示全部域级可用方式。

### 9.2 挑战页

只展示 §8.2 的「白名单 ∩ 域级可用方式」。

## 10. 管理端与审计

### 10.1 管理端 API

客户端 CRUD 扩展两个字段:
- `allowedLoginTypes`:`List<String>`,取值范围受限于 §5.1 词表
- `requireMfa`:`boolean`

**保存时校验**:
1. 值域合法(词表内、去重、大小写归一)
2. 白名单 ∩ 域级可用方式 ≠ ∅,否则拒绝并说明"该客户端允许的方式在本域全部不可用"

校验用 `LoginMethodCatalog.availableInRealm(...)` 计算,与准入判定共用同一份口径(§3),避免两处标准不一致。

### 10.2 控制台

`ClientsView.vue` 的客户端编辑抽屉增加:
- "允许的登录方式"多选(6 个固定选项 + 留空表示不限制)
- "强制 MFA"开关

前端只做值域约束,交集校验交给服务端(前端无法知道域级 LDAP / 短信通道是否真的配好)。

### 10.3 审计与登录事件

- 客户端准入策略变更走既有 `@LogAudit`
- 新增登录事件类型:
  - `CLIENT_ADMISSION_CHALLENGE`:发起补验(记录 clientId、当前方式、要求方式)
  - `CLIENT_ADMISSION_DENIED`:挑战失败或票据失效
  - `CLIENT_ADMISSION_MFA_CHALLENGE`:MFA 补验发起

事件落 `iam_login_event`,沿用既有字段结构。

## 11. 错误处理与安全边界

| 场景 | 处理 |
|---|---|
| 票据过期 / 已消费 | 提示"登录验证已过期,请重新登录",回登录页 |
| 挑战页提交的方式不在白名单 | 服务端二次校验(不依赖前端过滤),拒绝 |
| 域级可用方式在挑战期间被关闭(如管理员关掉短信) | 挑战时重新计算,方式消失则提示无可用方式 |
| 回跳地址非法(非本站授权端点) | 丢弃票据,记 `CLIENT_ADMISSION_DENIED` |
| 存量会话 loginType 未知 | 要求补验(§7.3) |
| 补验成功但会话已失效 | 走正常未登录流程 |
| 挑战期间用户改用门户登录(方式不匹配) | 按普通登录处理,新会话建立,旧票据到期清理(§8.1) |

**不变式**:
- 补验**不得**延长会话最大存续时长
- 补验成功后的 `loginType` 必须来自实际认证路径,不得由客户端参数指定
- 白名单判定在服务端完成,前端过滤仅为体验优化
- 挑战页的 `challengeToken` 必须与票据内值一致(CSRF 防护)

## 12. 测试策略

### 12.1 单元测试

| 目标 | 用例 |
|---|---|
| 准入判定 | 白名单命中 / 未命中 / 白名单为空 / `requireMfa` 与域级 `force_mfa` 的四种组合 / UNKNOWN 处理 |
| loginType 打标 | 本地口令 / LDAP 兜底 / 短信 / 三种联邦类型各打对标记 |
| 会话升级 | 升级后 `createdTime` 与 `maxLifetimeSeconds` 不变、TTL 不重置、Redis 与 DB 一致、`mfaVerified` 原值保留 |
| 存量兼容 | 无 `loginType` / 无 `mfaVerified` 的存量 Redis 快照能读出且语义为"未满足" |
| 域级可用方式 | LDAP 未配置 / 短信通道未配置 / 提供方未注册时正确排除 |
| 回跳校验 | 非法 `returnTo` 被拒 |
| 挑战票据 | 一次性消费、过期、token 不匹配 |

### 12.2 集成测试(Testcontainers:真实 PostgreSQL + Redis)

沿用既有 `beenest-iam-server/src/test/.../integration/` 模式:

1. **白名单命中**:客户端允许 PASSWORD,授权码 + PKCE 全链路通过
2. **白名单不匹配 → 方式挑战**:客户端只允许 SMS,用户用口令登录后访问 → 转挑战页 → 短信补验 → **同一 sid** 升级 → 授权成功
3. **联邦登录 → 受限客户端 → 挑战**:扫码建会话后访问只收 PASSWORD 的客户端 → broker 补验 → 升级同一会话 → 授权成功
4. **SAML POST 绑定回跳**:会话 Cookie 因 SameSite=Lax 不随跨站 POST 送达,挑战上下文经 RelayState 传递仍能完成升级(§8.2)
5. **强制 MFA**:客户端 `requireMfa=true`,正常登录后访问 → MFA 挑战 → TOTP 补验 → 授权成功
6. **跨实例**:挑战票据与授权请求在 Redis 中,模拟换实例完成补验(用两个 `TestRestTemplate` 指向同一 Redis)
7. **白名单收敛**:客户端白名单与域级无交集 → 管理端保存被拒

### 12.3 手工冒烟

真实浏览器逐项走:客户端配置 → 登录页展示过滤 → 挑战页 → 补验 → 授权成功;管理控制台客户端编辑抽屉无脚本错误。

## 13. 工作量与里程碑

| 里程碑 | 内容 | 天 |
|---|---|---|
| M1 | 迁移 + entity/model/converter/mapper 链路 + 管理端 API + 控制台表单 + loginType 打标 + 主体与会话携带 loginType/mfaVerified + `LoginMethodCatalog` | 6 |
| M2 | `ClientAdmissionFilter` + 判定服务 + `AdmissionChallengeService` + 方式挑战流程 + 挑战页 + `establish` 升级改造 | 6 |
| M3 | 客户端强制 MFA(会话 MFA 挑战 + 新端点)+ 联邦挑战上下文传递 + 登录页与挑战页展示过滤 + 登录事件 + 集成测试 | 6 |
| **合计** | | **≈ 18 天(3.5 周)** |

里程碑顺序保证每一步都可独立验证:M1 后策略可配置可落库,M2 后核心价值(方式准入)可用,M3 补齐 MFA 强制与展示层。

## 14. 不在本次范围内

- 域级风险规则引擎(§1.3)
- 按应用的策略继承与覆盖
- 多因子组合语义
- SAML **IdP 侧**签发断言(平台当前只有 SP 侧 `beenest-iam-provider-saml`,与本设计无关但同属对比文档 P0-1)
- 客户端凭据模式(`client_credentials`)的准入判定——该流程无交互式登录,无第一因素概念

## 15. 附带文档债

以下两处与本设计无直接关系,但同源于"对比文档滞后"这一发现,建议在同一轮内清理(独立小任务,不计入上表):

1. `readme.md` 的「已知能力边界」表仍列着「组织架构(部门/岗位/汇报线):无」与「API 文档(OpenAPI):无」——两者已分别在 `V1.2.0` 与 springdoc 落地
2. `docs/keycloak-comparison.html` 的 P0/P1 状态严重滞后:LDAP/AD(V1.1.9)、短信(V1.1.8)、租户主题(V1.1.7)、OpenAPI、组织架构(V1.2.0)均已落地;SAML 仅完成 SP 侧。测试数口径也不一致(文档 158 / readme 127 / 仓库实际 170 个 `@Test`)

---

## 附录 A:核实记录

本设计的若干结论基于对仓库与依赖的实际核实,记录如下。

### A.1 SAS 7.0.7 已移除 `AuthorizationRequestRepository`

`OAuth2AuthorizationEndpointFilter` 的构造函数为 `(AuthenticationManager)` 与 `(AuthenticationManager, String)`,**不再接受 `AuthorizationRequestRepository`**;jar 内也不存在该类。未完成的授权请求改存 `OAuth2AuthorizationService`,平台已用 Redis 实现(`RedisOAuth2AuthorizationService.putStateIndex` 按 `state` 建索引)。

**影响**:挑战后回到原授权请求是纯 Redis 操作,天然多实例安全,不需要引入 spring-session。SAS 7.0.7 的 `spring-security-oauth2-client` 不在依赖树内。

### A.2 `RegisteredClient` 无 `getMetadata()`

SAS 7.0.7 的 `RegisteredClient` 只有 `getClientSettings()` / `getTokenSettings()` 等固定访问器,无 `getMetadata()`。`ClientSettings` 继承 `AbstractSettings`,后者提供 `setting(String, Object)` 与 `<T> T getSetting(String)`,可用作策略载体(§4.3)。

### A.3 现有登录方式落点

| 方式 | 落点 |
|---|---|
| 表单口令登录 | `IamAuthenticationSuccessHandler:66` → `establish(..., "PASSWORD")` |
| REST 口令登录 | `AuthController:160` → `establishSession(..., "PASSWORD")` |
| MFA 二步 | `AuthController:188` → `establishSession(..., "PASSWORD")` |
| 短信登录 | `AuthController:246` → `establishSession(..., "SMS")` |
| 联邦回调 | `IamFederationController:185` → `establish(..., "FEDERATION")` |
| LDAP 兜底 | `IamAuthenticationProvider:147-153`,当前对用户透明 |

**三条交互式路径都经 `IamLoginSessionService.establish`**,这是 §8.1 升级逻辑能单点落位的前提。

### A.4 会话快照

`IamSessionPayload` 是 record,存于 Redis(`iam:session:{sid}`),由 `IamRedisSecurityContextRepository.currentPayload(request)` 静态方法在请求中读取。新增分量时,原始类型字段在存量 JSON 缺失时会反序列化失败,故新增字段一律用包装类型。

### A.5 授权端点扩展点

`OAuth2AuthorizationEndpointConfigurer` 提供 `consentPage` / `authenticationProvider` / `authorizationResponseHandler` 等,**没有"准入校验"钩子**。判定只能以外挂 Filter 实现。

### A.6 未使用的常量

`IamRedisKeyConstants.REGISTERED_CLIENT`(`iam:%s:client:%s`)当前无引用,`IamRegisteredClientRepository` 直读 `ClientProvider`。本期不改动,记录备查。
