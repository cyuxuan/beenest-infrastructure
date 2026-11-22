# 微信登录接入设计（小程序 + 扫码登录 · 热插拔）

日期：2026-10-21
状态：待评审

## 1. 目标

为蜂巢 IAM 增加微信登录能力，覆盖两种微信登录形态，并以扩展包形式交付：

1. **小程序登录**：微信小程序作为客户端，用 `wx.login()` 拿到的 code 换取平台身份；平台完成联邦身份换取、本地用户自动注册（记录 openid 等信息），并签发标准令牌。
2. **扫码登录**：微信开放平台「网站应用」扫码授权，用户用微信扫一扫完成网页端登录。

两条能力都必须满足：**新增提供方只需引入扩展包，不改核心代码**（热插拔）。

## 2. 范围与边界

**做**：

- 扩展包 `beenest-iam-provider-wechat`，注册两个提供方类型：`wechat-mini`（小程序）、`wechat-web`（扫码）。
- 打通「提供方类型元数据 → 管理端接口 → 控制台动态表单」，让新增提供方不再需要改前端。
- 小程序登录的令牌签发能力（平台侧、与微信无关的通用能力）。
- 集成测试、接入文档。

**不做（本期明确排除，避免范围蔓延）**：

- 微信公众号网页授权（`wechat-official`）——形态与扫码登录同构，等 `wechat-web` 落地后按同一模式加一个类型即可。
- 微信支付、企业微信、飞书等其它微信系能力。
- 平台自建的二维码扫码登录（用户用微信扫平台自己生成的码并确认）。本期扫码登录走微信开放平台官方通道，不需要平台自建票据状态机。
- 免登/静默授权（`snsapi_base`）的额外处理。

## 3. 现状盘点

已核实（文件:行）：

| 能力 | 现状 |
| --- | --- |
| 提供方 SPI 形态 | 原先有 `MiniProgramIdentityProvider` 这个**写死场景**的接口。本次改为通用契约：提供方声明自己的登录形态（`ProviderLoginMode`）与所需凭据（通用凭据 map），核心不再认识「小程序」 |
| `ProviderConfigProperty` + `IdentityProviderFactory.getConfigProperties()` | SPI 已定义、OIDC 工厂已实现，但**全项目无消费方**；管理端是手写自由 KV 表单 |
| 浏览器跳转类联邦 | `OAuth2IdentityProvider` 基类 + `/broker/{realm}/{alias}/login`、`/callback` 全套就绪，`IamFederationController` 走浏览器会话 |
| 扩展包热插拔 | `beenest-iam-provider-oidc` 已验证：只依赖 federation+core，工厂是 Spring Bean，`IdentityProviderRegistry` 按 classpath 自动发现；**本次补上外置目录装载**（jar 丢进目录即生效） |
| 令牌签发 | `RedisOAuth2AuthorizationService` 是完整的 `OAuth2AuthorizationService` 实现（授权码/访问令牌/刷新令牌/ID 令牌/反向索引/黑名单/单点登出寻址） |
| Bearer 访问 | 业务链 `IamSecurityConfiguration.java:213` 已配置资源服务器，Bearer 请求天然豁免 CSRF（`BearerTokenRequestMatcher`） |
| 扫码登录 | 无任何代码，全新 |
| `provider_id` 约束 | `iam_identity_provider.provider_id VARCHAR(50)`，无枚举约束，新增类型无需改表 |

## 4. 总体结构

```
beenest-iam-provider-wechat        【新增扩展包】只依赖 federation + core
├── WeChatMiniProgramProvider      wechat-mini：code2session 换 openid
├── WeChatMiniProgramFactory       providerId = "wechat-mini"，形态 = CREDENTIAL
├── WeChatWebProvider              wechat-web：开放平台扫码授权
└── WeChatWebFactory               providerId = "wechat-web"，形态 = REDIRECT

beenest-iam-core                   【通用契约】提供方 SPI：形态声明 + 通用凭据 map
beenest-iam-federation             【通用能力】注册表 + 外置扩展包装载 + 换取入口
beenest-iam-authz                  【通用能力】凭据换授权码（令牌交回协议栈签发）
beenest-iam-admin / console        【通用能力】提供方类型元数据 + 动态表单
```

依赖方向不变：扩展包 → federation → core。扩展包不依赖 persistence，不认识任何具体微信接口以外的东西。

**扩展包有两条装载路径**：

1. **内置**：作为 Maven 依赖进入 classpath，工厂是 Spring Bean，随应用上下文生效。
2. **外置**：打成独立 jar 放进 `iam.provider.dir` 指向的目录，启动时由 `ExternalProviderLoader` 用独立类加载器 + 服务声明装载（`META-INF/services`）。

外置路径的价值：**新增第三方提供方不必改本项目的 Maven 依赖、不必重新打包服务端**，丢 jar + 重启即可。

## 5. 微信扩展包

### 5.1 两个提供方类型

分成两个类型而不是一个类型加 `mode` 配置项，理由有三：

1. 小程序与开放平台网站应用是**两套 appid、两套密钥、两套资质**，运维心智里就是两件事；合并成一个类型会让配置表单出现互斥字段。
2. `IdentityProviderFactory` 一个工厂对应一个 `providerId`，配置项声明（`getConfigProperties()`）是**按类型**的，类型拆开才能给出各自最小且精确的表单。
3. 登录页需要区分「可浏览器跳转」与「仅凭据直传」两类提供方，能力差异挂在类型上最直接。

形态声明（`getLoginModes()`）：

| 类型 | 形态 | 自定义授权类型 |
| --- | --- | --- |
| `wechat-mini` | `CREDENTIAL` | `urn:beenest:iam:grant-type:wechat-mini` |
| `wechat-web` | `REDIRECT` | 无（走浏览器跳转 + `/broker` 回调） |

### 5.2 `wechat-mini`（小程序登录）

**所需凭据**（键名由本提供方定义，平台原样转交）：`jsCode` —— 小程序 `wx.login()` 拿到的��次性 code。

配置项：

| 键 | 名称 | 必填 | 机密 | 说明 |
| --- | --- | --- | --- | --- |
| `clientId` | 小程序 AppID | 是 | 否 | 微信小程序 AppID |
| `clientSecret` | 小程序 AppSecret | 是 | 是 | 加解密会话密钥使用 |
| `apiBaseUrl` | 接口基址 | 否 | 否 | 默认 `https://api.weixin.qq.com`，供私有部署/测试覆盖 |

换取流程：

1. 小程序调用 `wx.login()` 取得一次性 code（客户端上送，5 分钟内有效、只能用一次）。
2. 平台调用 `GET {apiBaseUrl}/sns/jscode2session?appid=&secret=&js_code=&grant_type=authorization_code`。
3. 响应含 `openid`（必返）、`session_key`（必返）、`unionid`（小程序绑定开放平台账号时才有）。

身份口径：

- **外部标识**：`unionid` 存在时取 `unionid`，否则取 `openid`。
  - 理由：同一微信用户在开放平台与小程序下 openid 不同、unionid 相同。只有在「小程序已绑定该开放平台账号」时两边才会统一，否则天然是两个账号——这是微信侧的既有约束，平台无法绕过，文档需写明。
- **落地属性**：`openid`、`unionid`（若有）、`session_key` 不落库（属敏感会话密钥，仅在换票瞬间存在）。
  - `session_key` 不进用户属性：它是加密通信用的会话密钥，长期保存没有收益，留存反而多一份泄露面。
- `nickname`/`avatar`：小程序 `code2session` 不返回，需另调 `wx.getUserProfile`（前端行为），本次不纳入；用户可在管理端补填。

### 5.3 `wechat-web`（扫码登录）

配置项：

| 键 | 名称 | 必填 | 机密 | 说明 |
| --- | --- | --- | --- | --- |
| `clientId` | 开放平台 AppID | 是 | 否 | 开放平台「网站应用」AppID |
| `clientSecret` | 开放平台 AppSecret | 是 | 是 | 换 access_token 使用 |
| `scope` | 授权范围 | 否 | 否 | 默认 `snsapi_login`（拿到昵称头像需要用户授权） |
| `authorizationUrl` | 扫码授权端点 | 否 | 否 | 默认 `https://open.weixin.qq.com/connect/qrconnect` |
| `tokenUrl` | 令牌端点 | 否 | 否 | 默认 `https://api.weixin.qq.com/sns/oauth2/access_token` |
| `userInfoUrl` | 用户信息端点 | 否 | 否 | 默认 `https://api.weixin.qq.com/sns/userinfo` |

换取流程（完全走现有 `/broker` 链路，**核心零改动**）：

1. 浏览器访问 `/broker/{realm}/{alias}/login` → 平台 302 到微信扫码授权地址。
2. 微信渲染二维码，用户扫一扫并授权 → 微信回调 `/broker/{realm}/{alias}/callback?code=&state=`。
3. 平台用 code 换 access_token 与 openid，再取用户昵称头像。
4. 平台建立自己的会话并回跳。

授权地址的三个微信特有约束（必须由 `WeChatWebProvider` 自己拼，不能沿用基类的参数顺序）：

- `appid` 必须是**第一个**参数（微信按位置解析，不接受 `client_id` 这个名字）；
- `redirect_uri` 必须整体 URL 编码（基类已编码，但微信要求编码后的值不能再被二次编码）；
- 地址末尾必须带 `#wechat_redirect` 片段，否则微信不渲染二维码。

### 5.4 扩展包的边界纪律

- 提供方类只负责「怎么从微信换到身份」，不负责「身份怎么变成本地用户、怎么建会话、怎么发令牌」——那是联邦运行时与平台的职责。
- 提供方不做重试、不做熔断：换票失败统一按「第三方不可用」上抛，由 `IdentityBrokerService` 归一为 `IDP_UNAVAILABLE`，与平台其它三方登录口径一致。
- 提供方不写库、不读库。

## 6. 凭据类登录的登录态交付

### 6.1 结论

凭据类登录（小程序等）**由平台签发授权码，令牌一律由标准授权码流程签发**。平台核心不自己签令牌。

理由：

- 协议栈那条路（签发、刷新、吊销、userinfo、单点登出）是现成且被完整验证过的；
- 自行另起一套签发逻辑，等于把协议栈已经解决过的问题重新踩一遍——实现过程中确实踩到了：框架的 ID 令牌生成只对 OIDC 流程主体生效，平台授权存储的投影又会丢掉 ID 令牌的声明，自签令牌的路走不通；
- 接入方多一次换令牌请求，换来的是「令牌完全标准、刷新吊销零维护」。

### 6.2 接口形态

**第一步：凭据换授权码**

```
POST {平台地址}/api/v1/auth/provider-code?realm={域标识}
Content-Type: application/json

{
  "provider": "wx-mini",
  "credentials": { "jsCode": "..." },
  "clientId": "...",
  "clientSecret": "...",
  "codeChallenge": "..."
}
```

响应 `{ "code": "...", "redirectUri": "..." }`。

**第二步：标准授权码换令牌**

```
POST {平台地址}/realms/{域标识}/oauth2/token
grant_type=authorization_code&client_id=...&client_secret=...&code=...&redirect_uri=...
```

响应是标准 OAuth2 令牌响应。

### 6.3 处理链

1. **客户端认证**：机密客户端（`client_secret`），密钥比对走平台的口令编码器。
2. **凭据换外部身份**：按提供方别名取提供方，凭据原样转交（键名由提供方定义）。
3. **自动注册**：首次登录建号并写绑定关系，同时执行属性/角色映射。
4. **签发授权码**：写一条授权记录（授权码 + OIDC 授权请求 + 主体快照），有效期 5 分钟。
5. **留痕**：登录成功事件与其它登录方式同一套风控与审计口径。

### 6.4 为什么是「授权码」而不是「直接发令牌」

凭据类登录没有浏览器跳转，天然不适用标准授权码流程；但**签发权**仍应留在协议栈：授权码是协议栈认得的凭证，把凭据换成一个协议栈认得的凭证，协议栈就能自己完成签发、刷新、吊销，平台不必重写令牌生命周期。

### 6.5 扩展点

- 凭据的键名由提供方定义（微信小程序是 `jsCode`），平台只转交不解释。
- 提供方通过 `getGrantType()` 声明自己的自定义授权类型，平台据此识别「这条凭据归谁换」。
- 平台拒绝与标准授权类型重名的自定义授权类型（否则等于给平台留后门）。

## 7. 用户自动注册与 openid 记录

- 首次登录自动建号：登录名沿用联邦运行时的生成规则（`{别名}-{外部标识}`，超长与非法字符已由 `UserFederationLinkService.buildUsername` 处理）。
- 外部身份绑定关系写入绑定表，是「同一个微信用户再次登录复用同一账号」的唯一依据。
- openid 等属性落地走**联邦运行时的属性搬运**：`IdpMappingService.apply` 会把外部身份的全部属性拷进本地扩展属性，因此提供方把 `openid`、`unionid` 放进 `BrokeredIdentityContext` 后，这两个值**无需管理员另配映射规则**即会写进 `iam_user.attributes`。
  - 属性映射规则的作用是**改名与筛选**（把 `openid` 映射成业务侧认的键 `wechat_openid`），不是「让属性落库」的前提。

## 8. 热插拔：类型元数据与动态表单

这是「扩展包不进核心」能否真正成立的最后一环。当前后台是硬编码的：控制台的提供方类型下拉写死 `['oidc']`，配置表单是自由 KV 编辑器。加一个微信类型就要改前端并发版——那不是热插拔。

### 8.1 类型元数据

扩展点（`IdentityProviderFactory`，core SPI）：

- 已有：`getProviderId()`、`getName()`、`getConfigProperties()`；
- 新增 `getLoginModes()`：声明该类型支持的登录形态（`REDIRECT` 浏览器跳转 / `CREDENTIAL` 凭据直传），默认 `{REDIRECT}`；
- 新增 `getGrantType()`：凭据类提供方在标准令牌端点上的自定义授权类型，跳转类返回空。

注册表（`IdentityProviderRegistry`）新增查询方法，返回每个已注册类型的元数据：`providerId`、`name`、`loginModes`、`grantType`、`configProperties`。

**注册时的防线**：自定义授权类型若与标准授权类型（`authorization_code` / `client_credentials` / `refresh_token` / 设备码 / 令牌交换等）重名，**直接拒绝注册**——重名等于让扩展包劫持平台的标准登录流程。

### 8.2 管理端接口

新增 `GET /api/v1/admin/identity-providers/provider-types`（权限 `idp:view`），返回上述元数据列表。

配置回显保持「只给键名」；标签、必填、说明由类型声明提供，管理端据此渲染。

### 8.3 控制台改造

- 提供方类型下拉改为从接口拉取，拉不到时退化为自由输入，不阻塞管理员。
- 配置表单按所选类型的 `configProperties` **动态渲染**：键名、标签、必填标记、密码框（机密项）、数字框、开关、文本域。
- 类型下拉与配置项**联动**：改类型，配置项立刻换成新类型声明的字段（表单组件支持按当前取值重算字段）。
- 机密项（`secret = true`）在编辑态留空表示保持原值，与现有 `clientSecret` 语义一致。

### 8.4 移除扩展包后的表现

内置依赖去掉、或外置 jar 移走 → 类型不再注册 → 管理端类型下拉不再出现该项；已配置该类型的提供方在登录时被明确拒绝（`未接入的身份提供方类型`），而不是静默失败。

## 9. 机密配置处理

现状：机密键只有 `clientSecret` 一个（`IamIdpConfigKeys.SENSITIVE`）。微信的密钥也叫 `clientSecret`（小程序 AppSecret / 开放平台 AppSecret 都是客户端密钥），**键名沿用即可，核心不需要为微信新增任何键名**。

但为了让「新增扩展包不必改核心」真正成立，机密判定要从白名单改为规则：

- `isSensitive(key)`：`clientSecret` 命中，或键名以 `Secret`/`secret` 结尾。
- 管理端的「编辑留空保留原值」逻辑同步改为按该规则判定（现在写死了 `clientSecret` 一个键）。
- 兜底：`ProviderConfigProperty.secret = true` 的项，即使键名不含 secret，也按机密处理——但这需要写入侧知道类型声明。写入侧（管理服务）可以按提供方类型从注册表取声明实现，**代价是写入路径要依赖注册表**。本期先落规则判定（覆盖 `*Secret` 命名），类型声明的 `secret` 标记用于**表单展示**，加密判定以规则为准，并在文档中写明约定：**扩展包的机密项一律以 `Secret` 结尾**。

## 10. 多实例与可观测

- 所有新增的跨请求状态一律放共享存储：小程序登录不产生服务端会话（令牌自包含 + 授权记录在 Redis）；刷新令牌与访问令牌的对应关系在 `RedisOAuth2AuthorizationService`（Redis）中，任意实例都能续期。
- 微信换票是外部 HTTP 调用，无状态，不涉及实例亲和。
- 审计：登录成功/失败沿用 `LoginEventPublisher`，来源标记为联邦；失败原因（第三方不可用/客户端密钥错误/jsCode 无效）分别留痕。
- 失败计数：小程序登录不进入口令失败计数（不是口令喷洒），但客户端密钥错误应有限流保护——**本期按 IP 维度限流**，复用登录失败计数器的 IP 计数能力。

## 11. 测试策略

1. **扩展包单元测试**：用内嵌 HTTP 服务模拟微信接口，验证 `jscode2session` 换票、unionid/openid 选择、错误响应（errcode）处理、授权地址拼接（`appid` 在首位、`#wechat_redirect` 结尾、`redirect_uri` 编码）。
2. **联邦集成测试**（`beenest-iam-server`）：注册 `wechat-mini` 提供方 → 调小程序登录端点 → 断言：用户自动创建、绑定关系写入、openid 属性落地、令牌可访问 `/api/v1/auth/me`、刷新令牌可换新令牌、令牌可被吊销。
3. **扫码登录集成测试**：注册 `wechat-web` → 走 `/broker` 发起 → 模拟微信回调 → 断言建立会话并回跳。
4. **类型元数据接口测试**：断言已注册类型的元数据正确返回、`browserLogin` 正确区分。
5. **多实例**：小程序登录与刷新在两个实例交替请求（沿用 `IamDistributedSessionIntegrationTest` 的多实例手法）。

## 12. 交付物

| 交付物 | 说明 |
| --- | --- |
| `beenest-iam-provider-wechat` 模块 | 两个提供方类型 + 工厂 + 单元测试 |
| 核心/联邦改动 | 提供方类型元数据出参、机密键规则、注册表查询 |
| authz 改动 | 小程序登录端点、令牌签发、客户端认证 |
| admin 改动 | 提供方类型元数据接口 |
| console 改动 | 动态类型下拉 + 动态配置表单 |
| 登录页改动 | 只为可浏览器跳转的提供方渲染按钮 |
| 迁移脚本 | 无需改表（`provider_id` 无约束） |
| 文档 | `docs/wechat-integration.md`：小程序接入、扫码接入、开放平台资质说明、openid 统一条件 |
| server pom | 引入扩展包依赖（引入即启用，移除即下线） |

## 13. 实施顺序

1. 核心与联邦：类型元数据出参 + 机密键规则（独立可测，先落地）。
2. 扩展包：微信两个提供方 + 单元测试。
3. authz：小程序登录端点 + 令牌签发。
4. admin + console：动态表单。
5. 集成测试 + 文档。

## 14. 已知取舍

- **凭据类登录多一次换令牌请求**：平台签授权码、令牌由标准授权码流程签发。换来的是令牌完全标准（刷新、吊销、userinfo 零维护），代价是接入方两次调用。
- **自定义授权类型只用于标识「这条凭据归谁换」**：客户端实际上不直接用它换令牌，它先经平台接口换授权码。保留这个标识是为了让平台能拒绝与标准类型重名的声明。
- **扩展包生效需要重启**：类一旦加载就无法卸载，换掉扩展包内容只能重启。
- **小程序公共客户端（PKCE）变体本期不做**：后端代理换票是当前推荐形态，安全模型更简单。若确有「小程序直连 IAM」的需求，再补一个公共客户端流程，届时 `code_verifier` 的校验由平台自建票据承载，不依赖协议栈内部实现。
- **openid 无需映射规则即落库**：联邦运行时会把外部身份的全部属性搬进本地扩展属性，openid/unionid 直接可查。属性映射规则是可选的「改名与筛选」手段，不是落库前提。
- **开放平台资质是外部依赖**：网站应用扫码登录需要开放平台企业认证，平台侧无法绕过；文档需写明，未认证时 `wechat-web` 不可用。
- **编辑提供方会清掉未填的非机密配置**：配置项是整体覆盖语义（机密项留空保留原值）。若要改一个非机密项，需把该项连同其它项一起重填。
