# 安全审查与链路闭环 — 2026-10-31

> 审查对象:蜂巢身份(beenest IAM)全量代码
> 审查范围:端到端链路追踪 + 安全漏洞 + 功能闭环 + 注释与坏味道
> 前置文档:`docs/code-review-2026-10.md`(2026-10-26,工程面与可交付性评估)

## 一、结论

四条主链路(浏览器登录、OIDC 授权、令牌校验、登出)本轮补齐了闭环缺口;管理面与联邦通道
收口到平台令牌与浏览器绑定。全量测试 685 项通过。

| 链路 | 闭环状态 | 本轮补的缺口 |
|---|---|---|
| 浏览器登录 | 闭环 | — |
| OIDC 授权码 | 闭环 | 授权码并发换票非原子 |
| 令牌校验 | 闭环 | 不校验签发方;停用域令牌仍被接受 |
| 登出 | 闭环 | 登出不吊销已签发令牌 |
| 管理面 | 闭环 | 特批无权限上限;工作台可改角色归属;SCIM 未收口 |
| 联邦通道 | 闭环 | state 未绑定浏览器;SLO 可伪造 |

## 二、链路地图

### 2.1 浏览器登录

```
/login → AuthenticationStepChain
         RealmResolutionStep → CaptchaStep → AccountLockStep → CredentialStep
         → AccountStatusStep → MfaGateStep → FailureResetStep
       → IamPrincipalFactory(权限现算) → IamLoginSessionService.establish
       → IAM_SESSION Cookie + Redis 快照 + iam_user_session 行
```

闭环点:`return_to` 只允许站内相对路径;会话三处(Redis 快照、数据库行、Cookie)同时写入;
登录失败计数与锁定在 `AccountLockStep`;MFA 待办票据由 `MfaPendingService` 一次性消费。

### 2.2 OIDC 授权码

```
应用入口 → 302 /realms/{realmKey}/oauth2/authorize
        → RealmContextFilter 解析域 → 会话校验 → 应用可见性闸门
        → 登录页(如需) → 同意 → 授权码
应用 → /realms/{realmKey}/oauth2/token
        → ClientSecretAuthenticationProvider(PKCE 校验,按码查一次授权)
        → OAuth2AuthorizationCodeAuthenticationProvider(再按码查一次)
        → IamJwtTokenCustomizer 签发
```

闭环点:PKCE 校验发生在客户端认证阶段,换票发生在授权类型阶段,**同一次请求内会两次
按码查询授权**——这是"授权码一次性"必须按请求而非按查询计数的原因。

### 2.3 令牌校验

```
业务请求 Authorization: Bearer <token>
→ IamJwtDecoder:按 iss 解析域 → 取该域 JWK 验签 → 比对规范签发方
→ IamTokenTypeValidator(拒绝 ID Token)→ IamTokenVersionValidator(账号状态/令牌版本)
→ IamTokenBlocklistValidator(jti 黑名单 + sid 会话吊销)
→ IamJwtAuthenticationConverter → @PreAuthorize
```

### 2.4 登出

```
/logout、/logout-all、管理端下线、撤回同意、SAML SLO
→ IamLogoutService.logout(sid)
   ① 取会话主体与域
   ② 写会话吊销标记(TTL = 会话剩余寿命)
   ③ 后通道广播(并发 4,总预算 2000ms)
   ④ 结束本地会话
```

四步缺一不可:只做 ④ 时,已签发的访问令牌在到期前仍可用,refresh 还能继续换票。

## 三、已修复

### 3.1 登出不闭环(P1)

**问题**:登出只删会话快照。访问令牌是无状态 JWT,验签不查会话,到期前一直有效;
refresh 令牌也还能换出新票。"退出登录"实际只是清掉了浏览器 Cookie。

**修复**:

- 新增 `SessionRevocationService`,登出时写入 `iam:blocklist:sid:{sidHash}`。
- `IamJwtTokenCustomizer` 给**访问令牌**也写 `sid`(原先只有 ID Token 有)。
- `IamTokenBlocklistValidator` 同时查 jti 黑名单与 sid 吊销集合。
- `IamLogoutService` 改为四步,新增 `logoutAll(realmId, userId)`。
- `AuthController`、`AuthServiceImpl`、`ConsoleSessionServiceImpl`、`AccountOverviewService`、
  `SamlIdpController` 全部改走统一入口,不再各自 `endSession`。
- `ApplicationAccessRefreshProvider` 刷新前查会话吊销,已登出的会话不得续期。
- `ApplicationAccessRevocationService.revokeClientGrant`:撤回同意时吊销授权记录 + 定向登出。

### 3.2 授权码并发换票非原子(P1)

**问题**:换票流程是"读授权 → 校验未失效 → 标记失效 → 写回",两个并发请求都读到未失效的码,
各自换出一组令牌。

**修复**:`findByToken` 对授权码做 Redis 原子抢占(`iam:authz:code-claim:{code}`)。
抢占值存的是**请求标识**而非固定值——同一次请求会两次按码查询(PKCE + 换票),
按固定值会在 PKCE 阶段就把自己的换票机会用掉。

### 3.3 令牌签发方不校验(P1)

**问题**:`iss` 只被用来选密钥,从未与域的规范签发方比对。

**修复**:`IamJwtDecoder.decode` 验签后比对 `iss == {基址}/realms/{realmKey}`。
基址取 `iam.issuer-base-url`,未配置时按当前请求推导;无请求上下文(内部调用)时跳过。
配套新增 `IamIssuerBaseUrlResolver.resolveCurrent()`。

### 3.4 停用域的令牌仍被接受(P2)

**问题**:`RealmJwkSource.jwksFor` 只判域是否存在,不判是否启用。停用域只是界面上失效,
它此前签出的令牌在密钥轮换前一直能用。

**修复**:域不存在或 `enabled() == false` 时返回空密钥集合,验签直接失败。

### 3.5 `token_profile` 正向匹配 fail-open(P1)

**问题**:`"SCOPED"` 之外的任何值(空串、`"SCOPED "`、拼错)都落到 LEGACY 分支,
把全域扁平权限发给业务应用。

**修复**:只有显式 `LEGACY_GLOBAL` 才走全域,其余一律按收敛档;写侧加枚举校验,
配置意图与实际行为不再可能不一致。

### 3.6 LEGACY 档权限不重算(P1)

**问题**:刷新与重签时沿用登录快照里的权限,撤掉角色后旧 refresh 仍能换出高权限 access。

**修复**:改为按库中现值 `computePermissionCodes` 计算。

### 3.7 协议映射器按 `client_key` 查主键(P1)

**问题**:`registeredClientId` 是客户端主键的字符串形式,按 `client_key` 查通常查不到
(映射全部失效);若恰有另一客户端 `client_key` 等于该数字,则跨客户端泄露 claim。

**修复**:改为 `clientProvider.findById(Long.valueOf(registeredClientId))`。

### 3.8 Host 头注入(P1)

**问题**:口令重置链接由 `request.getServerName()` 拼接,`curl -H 'Host: evil.com'` 即可
让受害者收到指向攻击者域名的重置链接,票据被劫持后接管账号。同源问题污染邮箱验证链接、
broker 回调地址与 SAML SP 实体 ID。

**修复**:四处全部改用 `IamIssuerBaseUrlResolver`(`iam.issuer-base-url`),
配置优先,不再直接读 Host。

### 3.9 管理面越权(P1/P2)

- **`assignRoles` 自授权**:持 `user:assign-role` 的低权管理员可给自己分配高权角色。
  改为拒绝修改当前登录账号的角色。
- **权限特批无上限**:只挡"给自己",可给第二个账号特批任意权限再用它登录。改为
  **授出与撤销都不得超出操作者自身权限**。
- **工作台可改角色归属**:应用管理员可把本应用角色改成 REALM 容器或挂到别的应用。
  改为工作台下只改名称与启用状态,归属由路径决定。
- **SCIM 未收口**:业务应用令牌带 `scim:manage` 即可全量读写用户与组。改为纳入平台令牌闸门。
- **`redirect_uris` 零校验**:写入时校验必须是绝对 `http(s)` 地址且不含英文逗号
  (库内逗号分隔存储,含逗号的一条会被拆成两条,其中一条成为新的白名单项)。
- **LDAP 地址可作内网探测**:只接受 `ldap://`/`ldaps://`,拒绝 `169.254.*` 与元数据主机名。
  回环地址明确允许(目录服务与身份服务同机部署是常见形态)。

### 3.10 联邦与 SAML(P2)

- **登录 CSRF**:联邦 state 未绑定浏览器,攻击者可把自己的外部账号塞进受害者浏览器完成登录。
  发起时把 state 写进 `IAM_FED_STATE` Cookie(HTTPS 下 `SameSite=None; Secure`,
  以覆盖 SAML 的跨站 POST),回调时比对。
- **发起端点无限流**:每次发起都落一条 state,不限制即为放大写入点。加单来源 30 次/分钟。
- **SLO 可伪造**:未登记证书时原本直接按 NameID 结束该主体全部会话,等于一个人人可用的
  踢下线接口。改为未验签只结束**当前浏览器自己的**会话;同时给 LogoutRequest ID 加一次性。
- **`ProviderCode` 直换授权码绕过 MFA**:这条口子不经过登录页,拿不到第二因素。
  改为要求 MFA 的客户端直接拒绝。

### 3.11 其他

- 短信验证码"错误次数上限"是死代码(每次取码只有 1 次机会):改为先比对后删,失败累计,
  达上限作废该码。
- 短信与口令重置的 IP 限流在 ip 为 null 时跳过,不再全站共用 `"unknown"` 桶;
  口令重置路径原本传 `null`,已透传真实 IP。

## 四、未修复与接受的风险

| 项 | 级别 | 判断 |
|---|---|---|
| 刷新链路不校验账号状态/令牌版本 | P2 | 已加会话吊销校验;账号禁用后 refresh 仍可换票,但换出的令牌在验签侧会被 `IamTokenVersionValidator` 拒绝,实际不可用 |
| SAML IdP 默认接受未签名 AuthnRequest | P2 | `saml_service_provider.require_signed_authn_request` 默认 false 是登记时的显式选择;Destination 与 ACS 地址已强制比对,请求 ID 一次性 |
| 密钥首次生成/轮换无并发保护 | P3 | 并发下可能多生成一把密钥并落库,旧密钥保留在发布列表,不导致验签失败 |
| `sub` 默认取登录名 | P3 | 改名即换 `sub`。改为稳定标识会破坏已建立的外部账号关联,需要迁移窗口,单独立项 |
| 外部 IdP 属性键名不受控 | P3 | 可覆盖 `ldap_dn` 等内部扩展属性键;影响限于该用户自身的属性展示 |
| 发信测试可投递任意地址 | P3 | 需要平台级配置权限,且属于运维动作 |
| SCIM 可改邮箱/手机号 | P2 | 端点已收口到平台令牌;持平台令牌者本就具备管理面能力,不再单独限制 |

## 五、部署要求

- **`iam.issuer-base-url` 必须在生产配置**。留空时按请求推导,签发方与回调地址都来自
  Host 头;配合反向代理时不配置会让多域名访问得到不同 issuer,也让 Host 头注入重新成立。
  该配置同时是"令牌签发方校验"的比对基准。
- 明文部署下浏览器会丢弃 `SameSite=None` 的 Cookie,联邦 state 的浏览器绑定只在 HTTPS 下
  生效,这是有意的降级(明文部署本身已无传输安全)。
