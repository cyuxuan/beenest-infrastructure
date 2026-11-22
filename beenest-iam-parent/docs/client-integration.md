# 客户端应用接入指南

> 适用版本:beenest IAM 1.0.0
> 读者:接入 IAM 的应用开发团队、架构评审、运维
> 关联文档:微信小程序 / 扫码登录见 [`wechat-integration.md`](./wechat-integration.md);
> 架构与功能服务设计见 [`app-access-architecture.md`](./app-access-architecture.md),
> 管理控制台分层见 [`admin-console.md`](./admin-console.md)

本文回答四个问题:

1. 一个应用如何接入 IAM(OIDC 单点登录);
2. 应用自己的菜单与权限,和 IAM 的权限码如何对应、由谁维护;
3. 用户登录后在哪里看到"我有权访问的应用",点击如何直接跳转;
4. 多应用场景下的会话、登出与失效如何联动。

---

## 0. 一页速览

| 你要做的事 | 在哪里做 | 本文位置 |
|---|---|---|
| 确认身份域(realm)与 issuer | 控制台「身份域」 | §3.1 |
| 登记客户端(client_id / 回调 / scope) | 控制台「客户端」 | §3.2 |
| 在应用里接授权码流程 | 应用代码 | §5 |
| 校验令牌、取用户与权限 | 应用代码 | §6 |
| 把应用挂到门户、配置可见性 | 控制台「应用门户」 | §3.5 |
| 让门户点击后直达应用 | 门户 + 应用回调 | §9 |
| 维护应用的菜单与按钮权限 | 控制台「角色 / 权限」+ 应用侧 | §8 |
| 接微信小程序 / 扫码登录 | 管理端身份提供方 + 小程序后端 | [`wechat-integration.md`](./wechat-integration.md) |

一句话架构:**IAM 是唯一的身份源与权限源,应用是独立的会话域**。IAM 负责"你是谁、你能进哪些应用、你持有哪些权限码",
应用负责"用这些权限码渲染自己的菜单、在自己的会话里做业务鉴权"。

---

## 1. 角色与术语

| 术语 | 含义 | 在本系统中的位置 |
|---|---|---|
| 身份域 realm | 租户/环境隔离单元,独立 issuer、独立签名密钥、独立用户与角色 | `iam_realm`,路径 `/realms/{realmKey}` |
| OP(OpenID Provider) | IAM 自身,签发 ID Token / Access Token | `beenest-iam-authz` |
| RP(Relying Party) | 接入的应用 | 你正在开发的应用 |
| client | 应用在 IAM 的协议身份,`client_id` + 密钥 + 回调白名单 | `iam_client` |
| appCode | 应用在门户与权限命名空间中的业务标识 | `iam_application_service.app_code` |
| 权限码 | `resource:action` 形式的字符串,如 `oa:expense:approve` | `iam_permission.permission_code` |
| 角色 | 权限的容器,域级(REALM)或客户端级(CLIENT) | `iam_role` |

**realm 与 client 是两件事**:realm 决定"哪批用户、哪套密钥",client 决定"哪个应用、允许回调到哪"。
一个 realm 下可以有多个应用(多个 client),一个应用只属于一个 realm。

---

## 2. 先选形态:五类客户端

| 形态 | 客户端类型 | 密钥 | PKCE | 典型场景 |
|---|---|---|---|---|
| 服务端 Web(含 BFF) | `CONFIDENTIAL` | 有,只在服务端 | 建议开启 | 管理后台、OA、报表系统 |
| SPA(纯前端) | `PUBLIC` | 无 | **强制** | 内部工具、轻量看板 |
| 移动 App | `PUBLIC` | 无 | **强制** | iOS/Android(系统浏览器完成授权) |
| 微信小程序 | `CONFIDENTIAL` | 有,只在后端 | 按客户端配置 | 小程序 `wx.login()` 换码,见 [`wechat-integration.md`](./wechat-integration.md) |
| 服务间调用 | `CONFIDENTIAL` | 有 | 不适用 | 定时任务、网关到后端(**需同时开启 `client_credentials` 并绑定服务账号**) |

选择原则:

- 只要有一个能安全保存密钥的服务端,就用 `CONFIDENTIAL` + 授权码,不要为了"少写后端"把密钥塞进前端;
- 纯前端只能用 `PUBLIC` + 授权码 + PKCE(`S256`)。本系统**不支持隐式流**,也不支持密码模式;
- `BEARER` 只用于"应用已有自己的登录态、只需要 IAM 令牌做 API 鉴权"的特殊场景,不产生浏览器跳转。

> **小程序不在系统浏览器里走跳转**:凭据直传接口要求 `clientSecret`,客户端必须是 `CONFIDENTIAL`,
> 密钥只放小程序后端。完整流程见 [`wechat-integration.md`](./wechat-integration.md)。
>
> 门户对所有接入方式一视同仁:302 到入口地址,不做协议分叉,也不校验客户端配置——
> 凭据由应用与授权端点之间交换,门户不参与。
>
> `client_credentials` 需要两个条件同时满足:客户端 `grantTypes` 勾选 `client_credentials`,
> 且绑定服务账号用户(`serviceAccountUserId`)。缺任一项,该 grant 不会注册,
> 令牌端点返回 `unauthorized_client`。

---

## 3. 接入前准备(控制台配置)

### 3.1 确认身份域

控制台「身份域」页确认目标 realm 的 `realmKey` 与启用状态。issuer 固定为:

```
https://{iam-host}/realms/{realmKey}
```

发现文档(所有端点以此为准,不要硬编码路径):

```
GET https://{iam-host}/realms/{realmKey}/.well-known/openid-configuration
```

> **所有协议端点都必须带 `/realms/{realmKey}` 前缀。** 根路径上同名端点(如
> `/oauth2/token`)也会响应,但它按 `default` 域处理,签发的 `iss` 不含域前缀,
> 与发现文档里的 issuer 对不上,验签必然失败。不要从根路径复制端点。
>
> 生产网关建议只放行 `/realms/*` 下的协议路径,把根路径协议端点挡在外面。

### 3.2 登记客户端

控制台「客户端」→ 新建:

| 字段 | 说明 | 建议 |
|---|---|---|
| 客户端标识 `clientKey` | 即 `client_id`,创建后不可改 | 用小写短横线:`oa-portal`、`expense-web` |
| 客户端名称 | 展示用 | 与应用门户的 `appName` 一致 |
| 类型 | `PUBLIC` / `CONFIDENTIAL` | 见 §2 |
| 回调地址 `redirectUris` | **多条,逐条添加**(控制台为多值输入;接口接收数组),精确匹配,不支持通配 | 生产只填 https;本地可加 `http://localhost:8080/login/oauth2/code/nestid` |
| 登出回跳地址 `postLogoutRedirectUris` | 登出后允许跳回的地址白名单;留空则登出后只回 IAM 登录页 | 填应用的"已退出"页,如 `https://oa.example.com/signed-out` |
| 后通道登出地址 `backchannelLogoutUri` | 单点登出接收端点;**留空则该应用永远收不到登出通知** | 必填,如 `https://oa.example.com/backchannel-logout`,见 §7.4 |
| 授权作用域 `scopes` | 多条,至少含 `openid` | 内部系统 `openid` 即可;需要档案再加 `profile` |
| 强制 PKCE | 公共客户端应恒为开 | 公共客户端务必开启 |
| 征求用户同意 | 首次授权是否弹同意页 | 内部系统关闭;对外/第三方开启 |
| 复用刷新令牌 | 开=同一枚复用;关=每次刷新轮换 | 默认开(兼容);新系统建议关,见 §7.2 |
| 允许的登录方式 `allowedLoginTypes` | 限定第一因素:`PASSWORD` / `LDAP` / `SMS` / `WECHAT` / `OIDC` / `SAML`;留空不限制 | 高敏应用按需收窄,见 §3.7 |
| 强制二次验证 `requireMfa` | 进入该应用前必须完成 MFA | 高敏应用开启 |
| 主体标识 `subjectMode` | `LOGIN_NAME` / `USER_ID`;留空继承域策略 | 新应用直接选 `USER_ID`,见 §6.4 |
| access / refresh token 有效期 | 覆盖域默认值(秒) | 默认跟随域策略,不要为省事设很长 |

以下两项**只能在管理接口设置**(控制台客户端表单未暴露),用 `POST /api/v1/admin/clients` 的
`grantTypes` 与 `serviceAccountUserId` 字段:

| 字段 | 说明 |
|---|---|
| `grantTypes` | `authorization_code` / `refresh_token` / `client_credentials`;不配时默认前者与刷新 |
| `serviceAccountUserId` | `client_credentials` 的主体用户;不绑定时该 grant 不会注册 |

**密钥只显示一次**:创建/轮换成功后立即复制到应用的密钥管理(环境变量、KMS),不要落库明文、不要提交仓库。

### 3.3 作用域

「客户端作用域」维护可选/默认作用域。scope 的作用是**裁剪令牌内容**,不是权限系统:
`openid` 必填;`profile` 决定是否下发昵称等档案声明;自定义 scope 可配合协议映射器决定是否下发某类 claim。

> 现状:`scopes_supported` 在发现文档中只列出 `openid`(Spring Authorization Server 的默认集合),
> 自定义 scope 仍可正常申请与同意。不要以发现文档的该字段判断"支持哪些 scope",以控制台配置为准。

### 3.4 协议映射(可选)

「协议映射」把用户扩展属性或固定值映射为自定义 claim,分域级与客户端级两级,客户端级优先。
适合把 `employeeNo`、`tenantCode`、`departmentPath` 这类业务字段直接放进令牌,免去应用再查一次接口。

注意:**不要往令牌里塞大对象**。JWT 每个请求都要传输,超过 4KB 会被多数网关拒绝。

### 3.5 发布到应用门户

「应用门户」→ 新增应用:

| 字段 | 说明 |
|---|---|
| 应用编码 `appCode` | 全局唯一,作为权限命名空间与启动标识,建议与 `clientKey` 同名 |
| 应用名称 / 图标 / 分类 / 排序 | 门户卡片的展示信息 |
| 应用类型 | `WEB` / `APP` / `MINI_PROGRAM` / `API` |
| 接入方式 `ssoMode` | `OIDC` / `CAS` / `SAML` / `BEARER`;门户对四种一律只做入口直链 |
| 绑定客户端 | OIDC 必选,应用用它与授权端点换票 |
| 入口地址 `entryUrl` | 门户启动的跳转目标;须为 `http(s)` 地址。应用在这里发起授权,见 §9.3 |
| 可见性 | `PUBLIC` 全员 / `ROLE` 按角色授权 / `PERMISSION` 按权限码 |
| 所需权限码 | 仅 `PERMISSION` 模式生效,如 `oa:access` |
| 菜单托管模式 | `IAM` 全量托管 / `HYBRID` 仅一级模块(默认)/ `APP` 应用自管,见 §8.1 |
| 授权模式 | `IAM_MANAGED`(默认)/ `MAPPED` / `APP_MANAGED`,见 §8.5 |
| 令牌档 | `SCOPED`(新应用默认,按应用裁剪)/ `LEGACY_GLOBAL`(迁移期兼容),见 §6.1 |

**可见性是"能否在门户看到并启动",不是"能否调用 API"**。真正的 API 鉴权在应用侧校验令牌,在 IAM 侧校验权限码,
两者不要混为一谈。

> **可见性在授权端点上同样生效**:应用绑定客户端后,`{issuer}/oauth2/authorize` 会校验当前会话用户对该应用可见,
> 不可见直接返回 403,并记一条 `APPLICATION_ACCESS_DENIED` 登录事件。也就是说,知道 `client_id` 与回调地址
> 不再能绕过门户直接进应用。未绑定应用服务的客户端(平台自身、机器对机器)不受影响;登录前的授权请求放行,
> 登录完成后会再判一次。
> 灰度期可用 `iam.authorization.enforce-application-visibility=false` 临时关闭(见 [deployment.md](./deployment.md#3-常用配置项))。

### 3.6 授权

三步组合出"某用户能进某应用":

1. **给角色**:用户 → 角色(直授或经用户组继承);
2. **给权限**:角色 → 权限码(整体覆盖式授予),或角色 → 菜单(菜单挂权限时自动派生);
3. **给应用可见性**:应用设为 `PUBLIC`,或 `PERMISSION` 且用户持有该权限码,或 `ROLE` 且角色在应用授权名单中。

> 边界:`ROLE` 可见性依赖 `iam_app_service_grant` 授权记录,在控制台「应用门户」页用「授权」(角色)与「指定到人」(用户)两个动作维护;
> 也可以直接用接口写入(`PUT /api/v1/admin/application-services/{id}/grants?subjectType=ROLE`)。
> 不想维护名单时,用 `PUBLIC` 或 `PERMISSION` 更省事。

### 3.7 客户端准入(可选收紧)

客户端的 `allowedLoginTypes` 与 `requireMfa` 定义"进入该应用前,当前会话必须满足什么":

| 配置 | 判定 |
|---|---|
| `allowedLoginTypes` 非空 | 会话建立时的登录方式必须命中,否则要求补验 |
| `requireMfa=true` | 会话未完成 MFA 时要求补验 |

行为要点:

- 判定发生在授权端点 `{issuer}/oauth2/authorize`,GET 与 POST 同等拦截;
- 不满足时**不直接拒绝**,而是把已登录用户送到 `/login-challenge` 补验,成功后回到原授权请求;
- 只影响**浏览器授权码流程**。`/api/v1/auth/provider-code`(小程序等凭据直传)不经过授权端点,
  不触发准入判定;需要在业务侧自行校验登录方式或补做 MFA。

登录方式码与会话的对应关系以控制台下拉项为准:`PASSWORD` / `LDAP` / `SMS` / `WECHAT` / `OIDC` / `SAML`。
留空表示不限制第一因素。

---

## 4. 端点清单

以 `{issuer}` = `https://{iam-host}/realms/{realmKey}` 表示。

| 端点 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| 发现文档 | `{issuer}/.well-known/openid-configuration` | 匿名 | 端点与能力发现 |
| 授权 | `{issuer}/oauth2/authorize` | 浏览器会话 | 发起授权码流程 |
| 令牌 | `{issuer}/oauth2/token` | 客户端认证 | 换码、刷新、客户端凭证 |
| JWKS | `{issuer}/oauth2/jwks` | 匿名 | 验签公钥 |
| 用户信息 | `{issuer}/userinfo` | Bearer | 取当前用户声明 |
| 吊销 | `{issuer}/oauth2/revoke` | 客户端认证 | 吊销 access/refresh token |
| 内省 | `{issuer}/oauth2/introspect` | 客户端认证 | 不透明令牌/在线校验 |
| 登出 | `{issuer}/connect/logout` | 浏览器会话 | RP 发起登出(`id_token_hint` + 可选回跳) |
| 凭据登录 | `/api/v1/auth/provider-code?realm={realmKey}` | 客户端密钥 + 第三方凭据 | 小程序等凭据直传换授权码,见 [`wechat-integration.md`](./wechat-integration.md) |
| 后通道登出(应用实现) | 应用自有的 `backchannelLogoutUri` | IAM 主动 POST | 接收单点登出通知,见 §7.4 |
| 登录页 | `/login?realm={realmKey}&return_to=...` | 匿名 | 统一登录页(含 MFA/验证码) |

协议能力:`response_type=code`、PKCE `S256`、`authorization_code` / `refresh_token` / `client_credentials`;
签名算法 `RS256`;`subject_type=public`;支持 OIDC RP-Initiated Logout 与 Back-Channel Logout。

> **不支持令牌交换(`token-exchange`)**:发现文档的 `grant_types_supported` 里可能出现该值(协议栈默认),
> 但本平台未注册客户端凭据交换所需的客户端与策略,实际调用返回 `unauthorized_client`。跨应用免登请用门户 SSO。
>
> `client_credentials` 同样只在客户端显式开启该 grant 并绑定服务账号时可用,否则返回 `unauthorized_client`。

---

## 5. 标准授权码流程

### 5.1 时序

```
应用(浏览器)            IAM                       应用服务端
    │  1 未登录，跳 /authorize  │                          │
    ├──────────────────────────>│                          │
    │  2 无会话 → 302 /login    │                          │
    │<──────────────────────────┤                          │
    │  3 提交账号/口令/MFA      │                          │
    ├──────────────────────────>│                          │
    │  4 302 回 /authorize      │                          │
    ├──────────────────────────>│                          │
    │  5 302 redirect_uri?code  │                          │
    │<──────────────────────────┤                          │
    │  6 把 code 交给后端       │                          │
    ├─────────────────────────────────────────────────────>│
    │                          │  7 POST /token (code+verifier+secret)
    │                          │<─────────────────────────┤
    │                          │  8 access/id/refresh      │
    │                          ├─────────────────────────>│
    │  9 建立应用会话(Set-Cookie)                          │
    │<─────────────────────────────────────────────────────┤
```

第 1 步之前如果用户已在 IAM 登录过(例如刚从门户点进来),第 2~4 步会被跳过,直接到第 5 步——这就是**静默单点登录**。

### 5.2 授权请求参数

```
GET {issuer}/oauth2/authorize
  ?response_type=code
  &client_id=oa-portal
  &redirect_uri=https%3A%2F%2Foa.example.com%2Flogin%2Foauth2%2Fcode%2Fnestid
  &scope=openid%20profile
  &state={应用生成，32 字节随机，存应用会话}
  &nonce={应用生成，用于 ID Token 防重放}
  &code_challenge={BASE64URL(SHA256(verifier))}
  &code_challenge_method=S256
```

| 参数 | 必填 | 说明 |
|---|---|---|
| `redirect_uri` | 是 | 必须与客户端登记的某一个值**逐字符一致**(含末尾斜杠、端口) |
| `state` | 是 | 由**应用**生成并在回调时比对,防 CSRF/串会话 |
| `nonce` | 建议 | 写入 ID Token,回调时比对 `id_token.nonce`;服务端不强制,但防重放应由应用自己把关 |
| `scope` | 是 | 至少 `openid`;空格分隔(URL 编码为 `%20` 或 `+`) |
| `code_challenge` | 客户端强制 PKCE 时必填 | `S256`,不要用 `plain` |

### 5.3 换取令牌

```bash
curl -X POST https://iam.example.com/realms/beenest/oauth2/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=authorization_code' \
  -d 'code=...' \
  -d 'redirect_uri=https://oa.example.com/login/oauth2/code/nestid' \
  -d 'code_verifier=...' \
  -u 'oa-portal:${CLIENT_SECRET}'
```

响应含 `access_token`、`id_token`(`scope` 含 `openid` 时)、`expires_in`、`token_type=Bearer`、`scope`;
客户端登记了 `refresh_token` 时还有 `refresh_token`。

**授权码严格一次性**:同一枚码只能换一次票,并发换票时只有先到的那个请求成功,其余返回
`invalid_grant`。码一旦被使用即作废,换票失败(PKCE 不匹配、回调地址不符)后也不能重试,
必须重新走授权请求。这是 RFC 6749 的要求,也是"授权码被截获后重放"的唯一防线。

### 5.4 各语言的最小实现

**Spring Boot(推荐,零手写协议)**

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          nestid:
            client-id: oa-portal
            client-secret: ${IAM_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid, profile
            client-name: 蜂巢身份
        provider:
          nestid:
            issuer-uri: https://iam.example.com/realms/beenest
```

`issuer-uri` 会触发发现文档拉取,端点、JWKS 全部自动配置。SecurityFilterChain 只需 `oauth2Login()` 与
`oauth2Client()`;登出用 `OidcClientInitiatedLogoutSuccessHandler` 指向 `{issuer}/connect/logout`。

> 若要求"IAM 未启动时应用也能启动",把 `issuer-uri` 换成显式的
> `authorization-uri` / `token-uri` / `jwk-set-uri` / `user-info-uri`,避免启动期强依赖发现文档。

**Node / 其他后端**:用 `openid-client`(或等价库)的 `discovery(issuer)` → `buildAuthorizationUrl` → `authorizationCodeGrant`,
不要手写 PKCE 与 JWT 验签。

**SPA**

```js
const verifier = base64url(crypto.getRandomValues(new Uint8Array(32)));
const challenge = base64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
sessionStorage.setItem('pkce_verifier', verifier);
sessionStorage.setItem('oauth_state', state);
location.assign(`${issuer}/oauth2/authorize?response_type=code&client_id=${clientId}`
  + `&redirect_uri=${encodeURIComponent(redirectUri)}&scope=openid&state=${state}`
  + `&nonce=${nonce}&code_challenge=${challenge}&code_challenge_method=S256`);
```

回调页取 `code` → `POST /token`(`grant_type=authorization_code` + `code_verifier`,无 secret)→ 校验 `state`/`nonce`。
**refresh_token 不要放 localStorage**;SPA 建议只拿短时 access token,刷新交给 BFF,或接受会话到期重新跳转。

---

## 6. 令牌与声明

### 6.1 已注入的声明

| 声明 | 含义 | 示例 |
|---|---|---|
| `iss` | 签发方,按 realm 区分 | `https://iam.example.com/realms/beenest` |
| `sub` | 主体标识,取值由策略决定:默认登录名,可切不可变用户主键(见 §6.4) | `zhangsan` 或 `1024` |
| `preferred_username` | 登录名,**与 sub 取值无关,恒定是登录名** | `zhangsan` |
| `aud` | 受众。**访问令牌**是所绑定应用登记的受众(可多个);**ID Token** 按 OIDC 规范仍是 `client_id` | `urn:iam:app:oa` / `["https://a/api","https://b/api"]` / `oa-portal` |
| `realm` / `realm_id` | 域标识 / 域主键 | `beenest` / `1` |
| `uid` | 用户主键(**稳定,建议作为外部主键**) | `1024` |
| `username` / `nickname` | 登录名 / 昵称 | `zhangsan` / `张三` |
| `phone_number` | 已验证手机号;用户未登记时**不出现** | `15000000000` |
| `email` | 邮箱;未登记时不出现 | `zhangsan@corp.example.com` |
| `idp_identities` | 已绑定的渠道身份,键为提供方类型(`provider_id`),值为该渠道外部标识;无绑定时不出现 | `{"wechat-mini":"o4xt..."}` |
| `token_type` | `access` 或 `id`;ID Token 被当作 Bearer 时解码直接拒绝 | `access` |
| `token_version` | 令牌版本,全端下线时递增 | `3` |
| `token_kind` | 令牌来源:`PLATFORM`(客户端未绑定应用)/ `APPLICATION`(绑定应用)/ `MACHINE`(客户端凭证) | `APPLICATION` |
| `realm_roles` | 域级角色码 | `["EMPLOYEE"]` |
| `client_roles` | `SCOPED`:仅当前客户端角色;`LEGACY_GLOBAL`:全部应用/客户端角色(迁移兼容) | `["OA_USER"]` |
| `app_roles` | `MAPPED`:映射后的应用自有角色码;`IAM_MANAGED + SCOPED`:本应用 `APP` 角色码;未命中不出现 | `["REPORT_VIEWER"]` |
| `groups` | 用户组编码(组织维度) | `["HQ","FIN"]` |
| `dept` / `dept_name` | 主部门编码 / 名称(只输出主部门,兼职部门在 `groups` 中) | `HQ` / `总部` |
| `positions` | 岗位编码 | `["FIN_MANAGER"]` |
| `permissions` | `SCOPED`:当前应用的权限码;`LEGACY_GLOBAL`:按库中当前授权关系计算的权限码全集(不用登录快照,撤角色后旧令牌续期即失效) | `["oa:expense:submit", ...]` |
| `scope` | 空格分隔字符串(RFC 9068) | `openid profile` |
| `sid` | 会话锚点(会话 id 的 SHA-256):ID Token 用它做登出定位,访问令牌用它做登出后的即时失效判定 | `62_g6XNabDaJGWJ...` |

ID Token 只携带身份与角色声明(`realm_roles` / `client_roles`),**不带** `token_kind`、`permissions`、
`app_roles`、`dept` 等授权声明;不要拿 ID Token 当 Bearer 令牌调接口。

协议映射器配置的自定义 claim 会一并出现(域级 + 客户端级)。

> **令牌档由应用决定**:新应用默认 `SCOPED`;存量应用可能仍是 `LEGACY_GLOBAL`,接入前在控制台「应用门户」确认。
> `SCOPED` 下平台或其他应用的权限码、其他客户端角色不会进入令牌;需要全域权限请在自己的应用命名空间内配置。
> 客户端**未绑定应用**时只输出身份声明(`iss`/`sub`/`uid`/`realm` 等)并打上 `token_kind=PLATFORM`,
> `aud` 保持 `client_id` 且没有任何角色/权限声明——接入前先完成绑定。

### 6.1.1 访问令牌受众(aud)

访问令牌的 `aud` 指向**令牌要交给哪个后端**,由应用服务上的「令牌受众」决定,不是签发它的客户端:

| 情况 | `aud` 取值 |
|---|---|
| 客户端绑定了应用,应用登记了受众 | 登记的受众(可多个,写成数组) |
| 客户端绑定了应用,应用未登记受众 | `urn:iam:app:{app_code}` |
| 客户端未绑定应用,但用户恰好被授权一个应用(经角色/直授/组) | 该应用的受众 |
| 客户端未绑定应用,用户被授权多个或零个应用 | `client_id`(即平台自身的第一方客户端) |

**一个应用可以登记多个受众**,管理端「令牌受众」是多值输入(输入后回车/逗号即成一个标签),
签发时全部写入 `aud` 数组。典型用法:令牌要同时被网关和后端接受,两边各校验自己的标识;
或者受众标识正在迁移,新旧两个都放上去过渡。

> `aud` 一律写成数组(单个时也是 `["urn:iam:app:drone"]`)。少数库会把单值序列化成字符串,
> 因此校验方两种形态都要接受——按"数组或字符串"取元素集合,再判断是否命中,不要比较整个字段。

**同一个后端的多个入口要登记同一组受众**。例如某后端同时有 Web 管理端和微信小程序两个客户端、
两个应用服务,把两条应用记录的受众都填成 `urn:iam:app:drone`,后端只需校验这一个值,
两个入口的令牌都能被接受;而同一域内别的应用拿令牌过来,受众不符会被直接拒掉。

**一张令牌要被多个不同后端接受**(例如小程序登录一次,后续既调 drone 也调 payment):
把各后端**各自**的受众都登记在**签发这张令牌的那个应用**上——因为是它的客户端换的票。

```
签发方:drone 应用(绑定小程序客户端)  受众 = urn:iam:app:drone, urn:iam:app:payment
drone  服务:校验 aud 含 urn:iam:app:drone    → 通过
payment 服务:校验 aud 含 urn:iam:app:payment → 通过
域内其它应用拿这张令牌:aud 不含自己的值      → 拒
```

三条落地要点:

1. **谁签发,谁登记完整受众集合**;接收方只登记/校验自己那一个值,不要反过来把别人的标识当自己的期望值。
2. 校验一律用"**命中其一**"语义(`audiences.contains(own)`),不要要求 `aud` 恰好等于单个值、更不要比较整个字段的字面量。
3. 多受众会把令牌的可用范围扩大到这些后端,这是审计时的显式边界:只登记确实要接受同一张令牌的服务,不要为了省事把所有服务都加进去。

受众最多 5 个,单个不超过 200 字符,且不能包含英文逗号(库内以逗号分隔存储)。

> **受众自动回退**:客户端未绑定应用时,若用户恰好只被授权一个应用(经角色、直授或组),
> 令牌自动使用该应用的受众,避免管理员必须配置客户端绑定。多个或零个授权时回退到平台令牌。

资源服务器用 Spring Security 内置校验器即可,不必自己写:

```java
NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/oauth2/jwks").build();
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtAudienceValidator("urn:iam:app:drone")));
```

> ID Token 的 `aud` 不改:OIDC 要求它等于客户端 id,`nonce`/`azp` 校验才有意义。
> 只把访问令牌的 `aud` 当授权依据,ID Token 只用来建立会话。

> `sid` 是会话标识的 SHA-256 摘要,不是会话票据本身;访问令牌里带它,登出后 IAM 会拒绝该会话
> 签出的令牌(401 `invalid_token`),应用无需自己做令牌吊销。应用发起登出时也不需要自己构造
> `sid`,只要把 ID Token 原样作为 `id_token_hint` 传回即可。

### 6.2 校验清单(缺一不可)

1. 用 `{issuer}/oauth2/jwks` 验签,只接受 `RS256`;
2. `iss` 与配置的 issuer **完全相等**;
3. 访问令牌的 `aud` 包含本应用的受众之一(见 §6.1.1,多受众时任一命中即可);ID Token 的 `aud` 是 `client_id`;`azp` 若存在需一致;
4. `exp` / `iat` 校验,允许 ≤60 秒时钟偏差;
5. 授权码流程必须比对 `state`、`nonce`;
6. 不要用 `sub` 做数据库外键(除非已确认该应用切到用户主键);跨系统关联先看 `phone_number` / `idp_identities`,
   `uid` 只表示 IAM 账号——账号合并、渠道接入后同一自然人可能有多个 IAM 账号;
7. **权限判定用 `permissions`,不要解析 `scope`** —— scope 是协议层的授权范围,不是业务权限;
8. **应用内角色优先用 `app_roles`**:`MAPPED` 应用取映射后的自有角色码;`IAM_MANAGED + SCOPED` 应用取本应用 `APP` 角色码;没有该声明时不要回退解析其他应用的角色;
9. `LEGACY_GLOBAL` 下 `permissions` 是全域集合、`client_roles` 含全部应用/客户端角色,不要把它当作当前应用的权限边界;新应用请使用 `SCOPED`。

两条容易被忽略的边界:

- **ID Token 不能当 Bearer 令牌用**:平台解码器会按 `token_type` 拒绝;ID Token 里也没有 `permissions`。
- **`app_roles` 不由平台的默认转换器映射**:IAM 自身的 `IamJwtAuthenticationConverter` 只把
  `permissions`、`scope`、`realm_roles`、`client_roles` 映射为 authority。第三方资源服务器若要按
  `app_roles` 鉴权,需自行写转换器;见 §12。

### 6.3 典型 access token 载荷

以下为 `SCOPED` + `IAM_MANAGED` 示例;`permissions` 只含本应用命名空间,`client_roles` 只含当前客户端角色。

```json
{
  "iss": "https://iam.example.com/realms/beenest",
  "sub": "1024",
  "preferred_username": "zhangsan",
  "aud": "oa-portal",
  "iat": 1760996400,
  "exp": 1761000000,
  "realm": "beenest",
  "realm_id": 1,
  "uid": 1024,
  "username": "zhangsan",
  "nickname": "张三",
  "phone_number": "15000000000",
  "email": "zhangsan@corp.example.com",
  "idp_identities": {"wechat-mini": "o4xt..."},
  "token_type": "access",
  "token_version": 3,
  "token_kind": "APPLICATION",
  "realm_roles": ["EMPLOYEE"],
  "client_roles": ["OA_USER"],
  "app_roles": ["REPORT_VIEWER"],
  "groups": ["HQ", "FIN"],
  "dept": "HQ",
  "dept_name": "总部",
  "positions": ["FIN_MANAGER"],
  "permissions": ["oa:access", "oa:expense:submit", "oa:expense:approve"],
  "scope": "openid profile"
}
```

### 6.4 `sub` 用哪个:登录名还是用户主键

`sub` 是应用侧识别"这是哪个自然人"的唯一锚点——应用用它建用户表外键、记审计、判数据归属。
因此它必须满足一条硬要求:**在账号整个生命周期内不变**。

| 策略 | 取值 | 优点 | 风险 |
|---|---|---|---|
| `LOGIN_NAME`(默认) | 登录名 | 可读、可直接展示,兼容已按登录名接入的应用 | 登录名可变:改名、账号迁移、跨系统合并都会让应用侧映射断裂 |
| `USER_ID` | 用户主键(与 `uid` 同值) | 不可变,登录名怎么改都不影响应用 | 可读性差,展示要另取 `preferred_username` |

**两级配置,逐应用切换**:身份域设默认值(`/console` → 身份域 → 主体标识),
**单个客户端可单独覆盖**(`/console` → 客户端 → 编辑 → 主体标识,选「继承身份域」即回到域策略)。
这样新应用可以直接用 ID,存量应用改造完再切,不必等所有应用一起动。

切换是破坏性变更,应用侧要做两件事:

1. 关联键改为按 `sub` 还是 `uid` 二选一并**保持一致**——两者值相同,但语义要写清楚;
2. 展示名一律取 `preferred_username`(该声明恒定是登录名,不受策略影响)。

> 切到 `USER_ID` 后,存量应用若仍按登录名匹配 `sub`,需要一次性做映射迁移
> (`preferred_username` → 新 `sub`),迁移完成前不要改域级默认值,只给改造好的应用单独开。

### 6.5 多端登录与本地档案归并

同一个自然人可能同时用微信、支付宝、后台建号等入口登录,各渠道的外部标识互不相同。
应用侧不要拿 IAM 主键(`uid`)当本地用户主键,也不要只按单一渠道标识建档,推荐按下面的顺序匹配本地档案:

| 顺序 | 匹配键 | 说明 |
|---|---|---|
| 1 | `phone_number` | 跨渠道归并锚点;相同手机号的微信、支付宝、后台建号落到同一条档案 |
| 2 | `idp_identities` 的任一项 | 已绑定的渠道身份;补上没有手机号的历史账号 |
| 3 | `preferred_username` / `username` | 兜底 IAM 后台建号、没有手机号的账号 |

三条都匹配不到即视为新用户,建立新档案并绑定本次渠道身份。命中的档案保留原本地主键,
只补充缺失的手机号与渠道绑定——订单、钱包等外键不受影响。

令牌只在登录/刷新时用于取档;资源服务器每次请求仍应校验令牌来源与本地档案:

- `token_type` 必须为 `access`,`token_kind` 必须为 `APPLICATION`;`PLATFORM` / `MACHINE` / ID Token 一律拒绝;
- 本地档案必须存在且未被业务封禁。**只有令牌、没有本地档案的用户不能访问业务接口**,否则未经应用可见性判定的令牌就能越权。

---

## 7. 会话、刷新与登出

### 7.1 三层会话

| 层 | 载体 | 生命周期 | 谁负责 |
|---|---|---|---|
| 浏览器 ↔ IAM | `IAM_SESSION` Cookie(平台签发的随机会话标识)+ 共享 Redis 里的会话状态 | 域策略 `ssoSessionIdle` / `ssoSessionMax` | IAM |
| 应用 ↔ 用户 | 应用自己的 Cookie/Session | 应用决定 | 应用 |
| 应用 ↔ API | Bearer access token | `exp`,默认取域 TTL | IAM 签发、应用校验 |

**登出 IAM 不等于登出应用**,反之亦然。企业级做法是三者联动,见 §7.4。

> IAM 会话不落在接入方的任何考量范围内:接入方只需照常处理浏览器跳转。
> 平台侧会话状态在共享 Redis 里,多实例部署时**不需要负载均衡做会话亲和**——
> 详见 [deployment.md](deployment.md) §4.1。

**会话 Cookie 是持久化的,关浏览器不等于退出**。因此:

- **同一账号重新登录会结束上一条会话**:平台把浏览器带来的旧会话置为 `REPLACED` 并删除其 Redis 快照,
  不会同时留下两条在线会话;不同账号之间互不影响;
- **会话列表以 Redis 快照为准**:管理端「在线会话」与用户自助的会话列表会把"Redis 里已经消失、
  但数据库行还没被巡检改状态"的会话显示为已过期,不再显示成在线。

空闲超时(`ssoSessionIdle`)仍是真正的兜底:即使浏览器一直开着,超过空闲时长也会失效。

### 7.2 刷新

`grant_type=refresh_token` 换新 access token。是否轮换 refresh token 由客户端开关决定:

| `reuseRefreshTokens` | 行为 | 适用 |
|---|---|---|
| 开(默认) | 刷新后返回**同一枚** refresh token | 存量系统、多标签页并发刷新 |
| 关 | 每次刷新签发新值,**旧值立即失效**(再用报 `invalid_grant`) | 新系统、安全敏感场景 |

> **为什么默认是"开"**:轮换的代价是并发刷新互相踢掉——两个标签页同时刷新,后到的那个拿着已作废的令牌,
> 会被应用判为会话失效直接把用户登出。要开轮换,应用侧必须先做"刷新去重"(单飞/加锁),
> 或者接受偶发的重新登录。

打开轮换后的应用侧动作:刷新失败(`invalid_grant`)时**不要重试旧令牌**,直接清本地会话重走授权码。

刷新同样会复核应用可见性:管理员收回该用户对本应用的授权后,已签发的刷新令牌在下一次刷新时返回
`invalid_grant`,应用应立即清本地会话并回到门户或重新发起授权。收回 USER 直授时 IAM 还会主动
向本应用投递后通道登出,应用侧无需等待刷新失败。

```java
// 伪代码:刷新去重,避免并发刷新互相作废
synchronized (refreshLock) {
    if (tokenStore.isExpired(currentAccessToken)) {
        tokenStore.refresh();   // 只有这里会真正打令牌端点
    }
}
```

### 7.3 主动吊销

```bash
curl -X POST {issuer}/oauth2/revoke -u 'oa-portal:secret' -d 'token=...'
```

吊销写入 `jti` 黑名单,资源服务器验签时同步失效,不必等自然过期。

### 7.4 登出(三种场景都已在平台侧闭环)

**场景 A:用户从应用退出(推荐:RP-Initiated Logout)**

应用清本地会话后,把浏览器送到:

```
{issuer}/connect/logout
  ?id_token_hint={id_token}
  &post_logout_redirect_uri=https://oa.example.com/signed-out
  &state={可选,原样回传}
```

平台行为:

| 环节 | 行为 |
|---|---|
| 校验 `id_token_hint` | 必须由本域签发、是本客户端的 ID Token 且未被吊销;已过期仍接受(会话可能已超时),但 `sid` 与当前会话对不上时不结束会话 |
| 校验 `post_logout_redirect_uri` | 必须在客户端的**登出回跳地址**白名单内;未登记一律 400,不跳转(避免变成开放重定向) |
| 结束会话 | 删除会话快照 + 记录置为已注销 + invalidate 容器会话 |
| 广播 | 向该会话登录过的**其它应用**推送后通道登出(见场景 C) |
| 回跳 | `post_logout_redirect_uri?state=...`;未带则回 IAM 登录页 |

> `id_token_hint` 里的 `sid` 是会话锚点。IAM 已按规范把 `sid`(会话 id 的 SHA-256)写进 ID Token,
> 应用保存好这个值,后续做"本次登出只影响哪个会话"的判断时用得上。

**场景 B:管理员在 IAM 强制下线**

两条路径,效果不同,别混用:

| 操作 | 接口 | 行为 |
|---|---|---|
| 踢掉单个会话 | `DELETE /api/v1/admin/sessions/{sid}` | 标记该会话吊销 → 广播后通道登出 → 删除会话快照 → 记录置为已注销。该会话签出的 access token 下一次验签即被拒(401),refresh token 也无法再换票 |
| 全端下线 | `POST /api/v1/admin/users/{id}/logout`(管理员)或 `POST /api/v1/auth/logout-all`(用户本人) | `token_version` 自增 + 逐个会话标记吊销并广播;已签发令牌在下次校验时因版本落后或会话已吊销被拒(401) |
| 撤回授权同意 | 用户中心「已授权应用」/ 管理端「授权同意」 | 删除同意记录 + 吊销该用户在该客户端上的授权记录并定向登出;第三方无法继续用旧令牌读取数据 |

单会话强制下线后,应用既可以按收到的后通道登出清理本地会话,也会在下次调用时拿到 401——
两条路径同时生效,不必依赖其中一条。
需要令牌立刻作废时,用全端下线。

**场景 C:单点登出(SLO)广播 —— 已实现**

IAM 侧登出(场景 A 或 B)后,会向该会话登录过的应用逐个投递 OIDC Back-Channel Logout:

```http
POST /backchannel-logout HTTP/1.1
Content-Type: application/x-www-form-urlencoded

logout_token=eyJraWQiOiJsay0...   # RS256 签名
```

`logout_token` 的声明:

```json
{
  "iss": "https://iam.example.com/realms/beenest",
  "aud": "oa-portal",
  "iat": 1792469359,
  "exp": 1792469479,
  "jti": "823c5e76-0b04-4613-be36-18aef1d150eb",
  "sub": "alice",
  "sid": "62_g6XNabDaJGWJ27RRSSnqJjr8WCcImAfVjJmIrNxA",
  "events": { "http://schemas.openid.net/event/backchannel-logout": {} }
}
```

应用侧校验清单(缺一不可):

1. 用 IAM 该域的 JWKS 验签,`iss` 必须是本域 issuer;
2. `aud` 必须等于自己的 `client_id`(防止别的应用的登出令牌被重放过来);
3. `events` 必须包含 `http://schemas.openid.net/event/backchannel-logout`;
4. `jti` 做短期去重(同一枚令牌可能因重试被投递多次);
5. **不得**校验 `nonce`(登出令牌按规范不带 nonce);
6. 校验通过后按 `sid` 或 `sub` 清理本地会话,返回 2xx。
   这里的 `sub` 与**本应用**拿到的 ID Token 同值(客户端可单独覆盖主体策略,
   广播按每个应用各自解析),所以按 `sub` 匹配一定对得上号。

```java
// Spring Boot 应用接收端(资源服务器侧,用同一套 JWKS)
@PostMapping("/backchannel-logout")
public ResponseEntity<Void> backchannelLogout(@RequestParam("logout_token") String logoutToken) {
    Jwt jwt = jwtDecoder.decode(logoutToken);              // 验签 + 有效期
    if (!"https://iam.example.com/realms/beenest".equals(jwt.getIssuer().toString())) return ResponseEntity.badRequest().build();
    if (!jwt.getAudience().contains("oa-portal")) return ResponseEntity.badRequest().build();
    Map<String, Object> events = jwt.getClaim("events");
    if (events == null || !events.containsKey("http://schemas.openid.net/event/backchannel-logout")) {
        return ResponseEntity.badRequest().build();
    }
    sessionRegistry.removeBySid(jwt.getClaimAsString("sid"));   // 或按 sub 清理
    return ResponseEntity.ok().build();
}
```

投递语义(平台侧保证的边界):

| 项 | 行为 |
|---|---|
| 并发与超时 | 并发投递,总预算默认 2 秒(`iam.logout.broadcast-timeout-ms`),超时记失败,**不阻塞用户退出** |
| 失败重试 | 不自动重试;失败原因落 `iam_logout_broadcast`,可按会话查 |
| 未登记地址 | 记 `SKIPPED`——这类应用在用户登出后仍在线,必须尽快补登记 |
| 可观测 | 控制台「在线会话」→「登出广播」查看每个应用的投递结果与 HTTP 码 |

> **上线顺序**:先在应用侧把接收端点做好并登记地址,再依赖 SLO。反过来做,登出会记一堆 `SKIPPED`。

### 7.5 已知限制(接入前必须知道)

| 限制 | 影响 | 规避 |
|---|---|---|
| 角色/权限变更在**下次认证**生效 | 应用会话里是登录时刻的快照 | 需要立即生效 → 管理员对该用户**全端下线**(递增令牌版本) |
| SLO 依赖应用实现接收端点 | 未登记/未实现的应用登出后仍在线 | 登记 `backchannelLogoutUri`;查「登出广播」记录 |
| 网关/代理可能拦截 POST 回调 | 广播返回非 2xx | 放行该路径;或先落队列异步处理 |
| 门户只对 `OIDC` 应用做静默 SSO | `CAS`/`SAML`/`BEARER` 应用只能直达 `entryUrl`,不能从门户带登录态进入 | 保留应用自有 SSO;或迁到 OIDC |
| `sub` 默认取登录名 | 改名后 `sub` 变化 | 用 `uid` 关联,或把该应用切到 `USER_ID`(见 §6.4) |
| `LEGACY_GLOBAL` 应用令牌偏大 | 权限多时令牌变大、传输成本上升 | 切到 `SCOPED`,只注入本应用权限;控制单应用权限码在 50 个以内 |
| 凭据直传绕过客户端准入 | `provider-code` 不经过 `/oauth2/authorize`,`allowedLoginTypes`/`requireMfa` 不生效 | 小程序等场景在业务侧自行校验 |
| 凭据登录绑定**第一个回调地址** | `redirectUri` 固定取客户端登记的第一个,不能逐次指定 | 小程序客户端把换码地址登记为唯一/第一个回调地址 |

---

## 8. 应用菜单与按钮权限怎么管

这是接入里最容易做错的部分。先明确边界:

> **IAM 管"权限码与菜单的对应关系",应用管"菜单怎么渲染、点了去哪"。**
> 菜单是展示元数据,不是安全边界;真正的拦截必须在应用的接口上。

### 8.1 三种模式(应用级开关)

每个应用在「应用门户」里有一个 **菜单托管模式**(`menuMode`),不同应用可以不同:

| 模式 | IAM 管什么 | 应用管什么 | 适合 |
|---|---|---|---|
| `IAM` | 完整菜单树(含按钮) | 只渲染 | 菜单稳定、团队小的内部系统 |
| `HYBRID`(默认) | 一级模块 + 权限码 | 深层页面、路由、图标、排序 | **多数业务系统** |
| `APP` | 只发权限码 | 完整菜单与路由 | 存量系统、菜单与业务强耦合 |

选择建议:**新应用用 `HYBRID` 起步**——先把一级模块与权限码对齐,深层菜单留在应用里;
菜单结构稳定、需要统一审计时再升级到 `IAM`;存量系统先 `APP`,迁移一个模块切一个模块。

不要为了"统一"把业务页面的动态参数塞进 IAM 菜单表——那是应用的路由,不是身份数据。
`APP` 模式下 `GET /apps/{appCode}/menus` 返回空树,应用直接用令牌里的 `permissions` 渲染本地菜单,契约不变。

### 8.2 模式 A 的数据契约

菜单归属应用(以 `appCode` 标识),按角色授予,登录后按用户取树:

```
GET /api/v1/launchpad/apps/oa-portal/menus
```

```json
{
  "appCode": "oa-portal",
  "mode": "HYBRID",
  "version": "2026-10-20T10:00:00Z",
  "menus": [
    {
      "code": "oa.expense",
      "name": "费用管理",
      "type": "MENU",
      "path": "/expense",
      "icon": "wallet",
      "sort": 10,
      "permission": "oa:expense:view",
      "children": [
        { "code": "oa.expense.list", "name": "我的报销", "type": "MENU", "path": "/expense/list", "sort": 10 },
        { "code": "oa.expense.approve", "name": "审批", "type": "BUTTON", "permission": "oa:expense:approve" }
      ]
    }
  ]
}
```

约定:

- `mode` 回显该应用的菜单托管模式;`IAM` / `HYBRID` 返回被授予的菜单树,`APP` 返回空 `menus`;
- `type=MENU` 渲染为导航;`type=BUTTON` 不渲染导航,只用于按钮级显隐;
- `permission` 为空表示"登录即可见"(纯目录);
- `version` 用于应用侧缓存失效(取菜单最大 `updated_time`);
- 应用只渲染返回的节点,**不要在前端硬编码菜单再按权限过滤**——两套事实源必然漂移。

`menuMode=APP` 的应用返回空 `menus`,此时按 `permissions` + 本地菜单表渲染,
契约与字段名保持不变,后续切换模式无需改代码。

### 8.3 权限码命名

权限码固定 `resource:action`,应用权限在 `resource` 前加应用前缀:

| 场景 | resource | action | permission_code |
|---|---|---|---|
| 应用准入 | `oa` | `access` | `oa:access` |
| 列表查看 | `oa:expense` | `view` | `oa:expense:view` |
| 审批 | `oa:expense` | `approve` | `oa:expense:approve` |
| 导出 | `oa:expense` | `export` | `oa:expense:export` |

规则:

1. 前缀用 `appCode`,保证跨应用不撞码;
2. 只给"需要在 IAM 统一治理"的权限建码。细粒度数据权限(如"只能看本部门")留在应用内,用 `groups` 或业务字段判断;
3. 权限码一旦发布不要改名,改名等于所有角色授权失效;
4. 权限码数量控制在单应用 50 个以内,否则令牌膨胀、勾选界面难用。

### 8.4 应用侧落地清单

- [ ] 登录成功后拉一次 `permissions` 与菜单树,存服务端会话(不要每次请求都解析 JWT 里的数组做菜单计算);
- [ ] 路由守卫按 `permission` 判定,无权限跳 403 页而不是空白;
- [ ] 每个写接口在**服务端**再校验一次权限码,前端隐藏按钮只是体验;
- [ ] 菜单变更后 bump `version`,应用侧按版本失效缓存;
- [ ] 记录"谁在什么时间访问了哪个菜单"到应用审计,便于与 IAM 登录事件对齐取证。

### 8.5 应用角色放哪

角色分三层,别混:

| 层级 | 例子 | 放在哪 |
|---|---|---|
| 组织角色 | 员工、主管、HRBP | IAM 域角色(`realm_roles`) |
| **应用角色** | OA 管理员、报销审批人 | IAM 应用角色(`containerType=APP`),`IAM_MANAGED + SCOPED` 输出到 `app_roles`;`MAPPED` 下经映射输出到 `app_roles`;`LEGACY_GLOBAL` 下并入 `client_roles` |
| 业务岗位/数据角色 | "XX 项目负责人" | **应用自管** |

判断标准:影响"能不能进/能不能操作"的角色进 IAM;只影响"看到哪些数据、走哪个流程"的留在应用。

应用角色的用法:

- `IAM_MANAGED`:在控制台「角色」页新建 `APP` 类型角色并关联应用,给角色授予同应用权限;`SCOPED` 下角色码进 `app_roles`,权限码进 `permissions`;
- `MAPPED`:应用保留自有角色码,在控制台「应用门户 → 角色映射」把 IAM 用户组/域角色映射为目标角色码;登录后从 `app_roles` 读取,`client_roles` 仅作兼容展示;
- 细粒度仍用 `permissions`,不要把角色当成权限用;`MAPPED` 应用若同时需要 IAM 权限码,`SCOPED` 下可按 `permissions` 判定;
- 每个应用控制在 10~15 个应用角色以内。

### 8.6 变更生效路径

```
管理员改角色权限/菜单
   → IAM 保存(role_permission / role_menu)
   → 用户下次认证时重算 permissions 与菜单
   → 需要立即生效:全端下线(令牌版本 +1)
```

应用侧不需要"监听 IAM 变更",只需要在令牌失效(401)与重新登录时重建菜单。

令牌档切换(`LEGACY_GLOBAL` → `SCOPED`)同样在下一次签发时生效;切换后请重新登录并核对
`permissions` / `client_roles` / `app_roles`,确认应用依赖的权限码都已归属本应用。

---

## 9. 登录后:门户、多应用与跳转

### 9.1 门户的定位

门户(**启动台 / Launchpad**)是 IAM 的用户面:登录后落地,展示"当前用户有权访问的应用"。
它不是导航站,数据源是 `iam_application_service` + 授权关系。

门户**不换票、不拼授权地址**。`state`、`nonce`、`code_challenge` 的生成方和验证方都必须是应用,
门户拿不到应用的会话,拼出来的值应用验证不了。所以门户只做一件事:**把浏览器送到应用入口地址**,
授权请求由应用自己发起。

```
登录成功
   → 门户(域名 A)
       ├─ 卡片:协同办公       → 点击 → 302 入口地址 → 应用发起授权 → oa.example.com
       ├─ 卡片:费用报销       → 点击 → 302 入口地址 → 应用发起授权 → expense.example.com
       └─ 卡片:数据看板(API) → 点击 → 302 入口地址(无单点)
```

一个应用只登记一个地址(`entryUrl`),门户跳过去,其余交给应用。

### 9.2 现有接口

| 接口 | 说明 |
|---|---|
| `GET /api/v1/launchpad/apps` | 当前用户可见的应用卡片列表(按 `sortOrder` 排序),含 `menuMode` / `launchable` / `launchHint` |
| `GET /api/v1/launchpad/apps/{appCode}/menus` | 该应用下当前用户被授予的菜单树(含 `mode`;`menuMode=APP` 时为空) |
| `GET /portal/launch/{appCode}` | 启动跳转,**返回 302**;目标由服务端在点击时判定 |

卡片字段:

| 字段 | 取值 |
|---|---|
| `launchable` | `true` 可进入 / `false` 缺入口地址或应用已停用 |
| `launchHint` | 不可进入的原因,可直接展示;可进入时为 `null` |

卡片不返回跳转地址:跳转统一走 `/portal/launch/{appCode}`,由服务端在点击那一刻重新判定,
列表与点击之间改了配置也不会把人送到失效地址。

可见性判定(三者取并集):

1. `PUBLIC`:全员;
2. `PERMISSION`:用户 `permissions` 含 `requiredPermissionCode`;
3. 其余(含 `ROLE`):命中 `iam_app_service_grant`(USER/ROLE 主体)。

启动时会**再判一次可见性**,防止绕过列表直接访问跳转端点;`/oauth2/authorize` 与凭据登录(小程序)
也各判一次,三处共用同一套规则。

### 9.3 点击到进入应用的时序

```
门户(已登录 IAM)
  │ 1 浏览器 GET /portal/launch/oa-portal
  ├────────────────────────────────────────────> IAM
  │ 2 302 https://oa.example.com
  │<────────────────────────────────────────────
  ▼
应用入口页
  │ 3 发现无应用会话 → 生成 state / nonce / code_challenge
  │   → 302 /realms/{realm}/oauth2/authorize
  ▼
IAM /authorize
  │ 4 已有会话 → 302 https://oa.example.com/cb?code=...&state=...
  ▼
应用回调:校验 state → 换令牌 → 建应用会话 → 进首页
```

第 4 步没有登录页,用户感知是"点一下就到了"。`state` 与应用会话中的值可比对,
`nonce` 能写进 ID Token 并被校验,PKCE 全程可用。

**单页应用要在入口路由之前发起授权**,别先渲染页面再发现未登录——那会出现"闪一下未登录"。
在路由守卫/启动钩子里判断无会话就直接 302 到 `/authorize`,用户就看不到那一帧。

> **为什么门户不代发授权地址**:代发时 `state` 由门户生成,应用没有这个值可比对,只能跳过校验;
> `nonce` 同理无法校验;强制 PKCE 的客户端更是直接无解(门户没有 `code_verifier`)。
> 结果是"点进去先报未登录、刷新一次才好"——刷新时应用走了自己的发起流程,才拿到能校验的值。

### 9.4 多应用与体验细节

- **一个用户多个应用是常态**:卡片按 `category` 分组,组内按 `sortOrder`;
- **每个应用一个独立会话**:OA 在 `oa.example.com`、报销在 `expense.example.com`,各自 `Set-Cookie`,
  互不共享。所谓"单点"靠的是 **IAM 域下的会话 Cookie**,不是应用之间共享 Cookie——所以应用域名可以完全不同;
- **点哪个进哪个**:门户不需要知道应用的技术栈,只按应用登记的入口地址导航;
- **最近使用**:门户记录本地"最近启动",置顶 3~5 个(不建议写 IAM,属于展示偏好);
- **搜索**:应用名/编码前端过滤,应用数量 <200 时无需服务端搜索;
- **收藏**:本地存储即可,除非要做跨端同步才进 IAM;
- **无权限的应用不返回**,不要返回灰卡片——会泄露应用清单。

### 9.5 深链(跳到应用的具体页面)

深链完全由应用处理,门户不参与。推荐两种做法:

1. **入口路由带查询参数**:门户跳 `entryUrl?target=/expense/123`,应用把它存进会话后跳授权端点,
   回调时再取出来;
2. **编码进 `state`**:`state = base64url({"redirect":"/expense/123","csrf":"..."})`,
   应用回调校验后取 `redirect` 做站内跳转(`return_to` 只接受相对路径,防开放重定向)。

两种都由应用生成与校验,IAM 不解析、不改写业务路径。门户固定跳 `entryUrl`,不带任何附加参数。

### 9.6 接入方式与门户行为

| 接入方式 | 门户行为 | 建议 |
|---|---|---|
| `OIDC` | 302 到入口地址,由应用在入口页发起授权 | **标准路径**;单页应用在路由守卫里发起,别等首屏渲染 |
| `OIDC` + 客户端强制 PKCE | 同上 | verifier 只在应用侧,门户本来也不参与 |
| 未配入口地址 / 应用不可见(含已停用) | 跳转退回门户并提示原因 | 补齐配置或恢复可见性后再上线 |
| `BEARER` / `CAS` / `SAML` | 302 到入口地址,由应用按自己的协议发起 | 应用保留自有 SSO;或迁到 OIDC |

门户卡片只在不可进入时显示「不可进入」角标,`launchHint` 给出原因。


## 10. 安全基线

| 项 | 要求 |
|---|---|
| 传输 | 全链路 HTTPS;回调地址不允许 `http`(本地开发除外) |
| 回调校验 | 精确匹配,禁止通配符与开放重定向;`post_logout_redirect_uri` 须登记在客户端 `postLogoutRedirectUris` 白名单内,未登记直接 400 不跳转 |
| state / nonce | 每次授权重新生成,一次性使用,服务端会话存储 |
| PKCE | 公共客户端强制 `S256`;机密客户端建议开启 |
| 令牌存放 | 服务端会话优先;浏览器内存次之;禁止 localStorage 存 refresh token |
| 令牌校验 | 验签 + iss + aud + exp;不从 token 之外的地方信任用户身份 |
| 客户端密钥 | 环境变量/KMS,定期轮换(控制台支持轮换,旧密钥立即失效) |
| 权限判定 | 服务端为准;前端隐藏按钮不作为安全措施 |
| 日志 | 不打印完整令牌、不打印口令;审计保留 ≥6 个月 |
| 跨域 | 需要前后端分离时,在 `iam.cors.allowed-origins` 显式列出,不使用 `*` |

---

## 11. 联调与排错

| 现象 | 原因 | 处理 |
|---|---|---|
| `invalid_client` | client_id 错 / 密钥错 / 客户端被停用 | 核对控制台客户端状态与密钥 |
| `invalid_request` / 400 | 回调与登记值不完全一致(斜杠、端口、http/https) | 逐字符比对,本地端口也要登记 |
| `invalid_scope` | 申请了未登记或未定义的作用域 | 控制台补 scope,或去掉该 scope |
| 授权后回到登录页 | 浏览器没有 IAM 会话,或 Realm 不对 | 检查 `realm` 参数与 Cookie 域 |
| `invalid_grant` | code 过期(一次性,约 5 分钟)/ verifier 不匹配 / 已使用 | 重新发起授权,勿重试同一个 code |
| 401(资源服务器) | issuer 配错、JWKS 拉不到、令牌过期、被吊销 | 用 `introspect` 定位;检查时钟 |
| 403 | 令牌有效但权限码不足 | 核对角色/菜单派生;变更后需重新登录 |
| 门户点应用被退回并提示 | 应用未配入口地址 / 应用已停用 | 补 `entryUrl` 或重新启用;入口地址须 `http(s)` 开头 |
| 令牌里没有 `permissions` | 客户端未绑定应用服务(`token_kind=PLATFORM`) | 控制台把客户端绑定到应用门户中的应用 |
| `/token` 报 `unauthorized_client` | 客户端未开启对应 grant(如 `client_credentials` 未绑服务账号、凭据登录客户端未勾 `authorization_code`) | 管理接口补 `grantTypes` / `serviceAccountUserId` |
| 门户列表为空 | 应用未启用 / 可见性不匹配 / 域不对 | 检查 `enabled`、`visibility`、登录账号所属域 |

自查顺序:**发现文档能打开 → 授权页能登录 → 回调收到 code → 换到令牌 → 令牌能验签 → 权限码符合预期**。
卡在哪一步就只查那一步,不要跳步。

---

## 12. 附录:资源服务器最小配置

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://iam.example.com/realms/beenest
```

```java
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers("/api/expense/*/approve").hasAuthority("oa:expense:approve")
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(iamAuthorities())));
    return http.build();
}

/**
 * permissions → 原样 authority;scope → SCOPE_*;realm_roles / client_roles / app_roles → ROLE_*。
 * 不写这个转换器时,Spring 默认只映射 scope,permissions 不会成为 authority。
 */
private JwtAuthenticationConverter iamAuthorities() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions != null) {
            permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        }
        String scope = jwt.getClaimAsString("scope");       // 空格分隔字符串
        if (scope != null) {
            for (String s : scope.split(" ")) {
                if (!s.isBlank()) authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }
        for (String claim : List.of("realm_roles", "client_roles", "app_roles")) {
            List<String> roles = jwt.getClaimAsStringList(claim);
            if (roles != null) {
                roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            }
        }
        return authorities;
    });
    return converter;
}
```

IAM 自身用的 `IamJwtAuthenticationConverter` 只映射 `permissions`、`scope`、`realm_roles`、`client_roles`;
`app_roles` 需要在应用侧按上面的写法补上。其他语言实现照此映射即可,注意权限码不加 `SCOPE_`。
