# 蜂巢身份 (beenest IAM / nestid)

组织自研的 **Spring 原生统一身份认证中心 + IAM 平台**,取代基于 Apereo CAS 7.3.6 overlay 的 `beenest-cas`。

## 命名约定

| 用途 | 命名 |
|---|---|
| Git 仓库 | `beenest-iam` 或 `nestid` |
| Maven 父工程 | `beenest-iam-parent` |
| 认证服务(唯一进程) | `beenest-iam-server` |
| 协议页面(库模块) | `beenest-iam-web` |
| 管理后台 API(库模块) | `beenest-iam-admin` |
| 用户中心 API(库模块) | `beenest-iam-console` |
| 客户端 SDK | `beenest-iam-sdk`(Phase 3,Java + TS) |
| Java 包名 | `club.beenest.iam` |
| 中文简称 | 蜂巢身份、蜂巢 IAM |

## 模块结构(13 模块)

```
beenest-iam-parent                (pom 聚合)              club.beenest:beenest-iam-parent
├── beenest-iam-common            通用基础:Result<T>/异常/RedisUtils/常量/链路标识/一次性消息/
│                                 国际化(MessageSource + 语言解析,中英双语)
├── beenest-iam-core              纯领域模型 + SPI 接口(无 Spring)
├── beenest-iam-persistence       MyBatis entity/mapper/XML、Flyway 迁移
│                                 (用户/组/角色/菜单/客户端/会话/客户端会话/登出广播/
│                                  审计/属性定义/协议映射器/客户端作用域/身份提供方/安全策略/口令历史/
│                                  Passkey 凭证/SAML 服务提供方登记)
├── beenest-iam-federation        联邦运行时框架:Provider 注册表 + broker 流程 + 外部身份绑定
│                                 + 外置扩展包装载
├── beenest-iam-provider-oidc     第三方登录扩展包:通用 OIDC 提供方(引入即启用)
├── beenest-iam-provider-wechat   第三方登录扩展包:微信(小程序登录 + 开放平台扫码登录)
├── beenest-iam-provider-saml     第三方登录扩展包:SAML 2.0 SP 方向(以本平台为 SP 对接企业 IdP;
│                                 反方向 IdP 能力在 authz,见 docs/saml-idp.md)
├── beenest-iam-authz             SAS 多租户接线、密码认证、MFA/TOTP、Passkey/WebAuthn、令牌定制、
│                                 口令/登录安全策略、验证码、Redis 存储、会话生命周期与单点登出、
│                                 账户待办、找回口令、邮件通道与通知投递、用户中心数据装配与个人数据导出、
│                                 SAML 2.0 IdP 模式(元数据/SSO/SLO,与授权服务器同级)
├── beenest-iam-web               协议页面(登录/MFA 二步验证/授权同意/错误页/账户自助/用户中心)
│                                 + 可插拔主题目录(按身份域选择,`/theme-static/**` 动态解析;页面文案中英双语)
│                                 + 管理控制台(单页应用外壳,`/console/**`、`/console-static/**`)
├── beenest-iam-admin             管理端 REST API(库,/api/v1/admin/**)
│                                 (用户/组/角色/角色权限授予/菜单/客户端/属性定义/协议映射器/
│                                  客户端作用域/身份提供方/审计/登录事件/会话/安全策略/
│                                  令牌/授权同意/仪表盘/Realm 密钥轮换/SAML 服务提供方登记)
│                                 + SCIM 2.0 同步端点(/scim/v2/Users、/scim/v2/Groups)
├── beenest-iam-console           用户自助端 REST API(库,/api/v1/console/**)
│                                 (个人资料/改密/MFA 绑定与恢复码/Passkey 凭证管理)
└── beenest-iam-server            唯一可执行进程(boot jar)
```

依赖方向(无环):`common → core → {persistence, federation} → provider-* → authz → {web, admin, console} → server`。

### 第三方登录扩展包(热插拔)

第三方登录(微信/支付宝/飞书/通用 OIDC)全部以**扩展包**形式交付,不在核心内:

- 核心只保留 SPI 契约(`beenest-iam-core` 的 `core.spi.federation`)与联邦运行时框架
  (`beenest-iam-federation`:注册表、broker 流程、身份绑定、OAuth2 基类、外置扩展包装载);
- 每个提供方一个独立模块(如 `beenest-iam-provider-oidc`、`beenest-iam-provider-wechat`),
  只依赖 `federation` + `core`,不触碰持久层;
- **形态由提供方自己声明**:`ProviderLoginMode`(浏览器跳转 / 凭据直传)与配置项声明
  (`getConfigProperties`),核心不认识任何具体场景;
- **两条插拔路径**:
  - *内置*:server 的 pom 引入哪个扩展包,该提供方即随 classpath 注册到 `IdentityProviderRegistry`;
  - *外置*:把扩展包打成独立 jar 放进 `iam.provider.dir`(默认 `./providers`)指向的目录,
    启动时按 `META-INF/services` 服务声明装载。**新增第三方提供方不必改本项目依赖、不必重新打包服务端**,
    丢 jar + 重启即可生效(类无法卸载,故需重启)。

## 架构要点

- **统一认证中心**:基于 Spring Authorization Server 7 提供 OIDC/OAuth2 标准协议端点
  (`/realms/{realmKey}/oauth2/{authorize,token,jwks,introspect,revoke}`、
  `/realms/{realmKey}/.well-known/openid-configuration`)。任何语言(Java/Go/Python/Node…)的
  标准 OIDC 客户端均可零 SDK 接入;同时作为 **SAML 2.0 IdP** 为不具备 OIDC 能力的存量应用
  提供 `/realms/{realmKey}/saml/{metadata,sso,slo}`(见 [`docs/saml-idp.md`](docs/saml-idp.md));
  CAS 协议端点(兼容存量 beenest-cas 客户端)在 Phase 4 落地。
- **多租户 (Realm)**:每 Realm 独立 issuer、独立 RSA 签名密钥(`iam_realm_key`)、Realm 级管理。
- **前后端分离**:管理控制台与用户门户只消费 REST API,权限判定全部在服务端;
  两端为同一个 Vue 3 + TypeScript + Vite 工程(`beenest-iam-web/frontend`)的两个入口,
  构建产物分别输出到 `classpath:/console/static/` 与 `classpath:/portal/static/`
  (`npm run build` 一次构建两端);控制台按路由懒加载并按权限裁剪导航;
  与登录主题(`themes/{active}`)解耦,升级/替换前端不影响协议页面。
- **管理控制台**:`beenest-iam-web` 的 `classpath:/console/` 承载外壳与资源,
  视图经独立模板解析器解析(`console/index`),资源经 `/console-static/**` 映射;
  History 路由下 `/console/**` 均回退到同一外壳,刷新与书签直达不会 404;
  侧栏按权限码裁剪、顶栏呈现身份域上下文、读写请求回传 `X-XSRF-TOKEN`,
  接口 401 统一回登录页并保留回跳地址,页面链路与接口链路的未认证表现由
  `IamPageAwareAuthenticationEntryPoint` 分流(页面 302 / 接口 401 JSON)。
- **协议页面主题**:页面模板位于 `beenest-iam-web` 的 `classpath:/themes/{active}/`
  (`login/login.html`、`login/mfa.html`、`consent/consent.html`、`error/error.html`),样式经
  `/theme-static/**` → `classpath:/themes/{active}/static/` 映射(该路径需在安全配置中显式放行);
  企业级分屏 UI:左侧品牌叙事区(深墨蓝 + 琥珀金 + 蜂巢纹理),右侧浅色表单区;
  登录表单内置验证码条件展示(失败达阈值自动展开)、CapsLock 提示、密码显隐、提交防重;
  二步验证页为 OTP 分格输入(自动前移/退格回退/粘贴填充/填满自动提交,兼容恢复码整段输入);
  授权同意页按 scope 逐项勾选,`openid` 为必选锁定项,取消勾选即不授予该项;
  切换主题仅需修改 `iam.theme.active` 配置并放置同名目录,零代码接入。
- **三端一套主题包**:设计令牌集中在主题目录的 `static/css/tokens.css`(唯一事实源,
  规范词汇 `--canvas/--surface/--ink/--line/--gold/--navy` 等,不带端前缀);
  登录/同意/错误页(SSR)与门户、控制台(SPA 外壳)都按"当前域主题 → 全局主题"顺序链同一文件,
  控制台/门户样式表只留默认值、运行时被令牌覆盖——**换肤 = 改一个 CSS 文件,三端生效,
  不需要重新构建前端产物**;定制一套主题 = 复制 `themes/nestid/` 改目录名与令牌值。
- **响应规范**:REST 接口统一 `Result<T>`(`code/message/data/timestamp`),
  HTTP 状态码表达传输层事实(200/400/401/403/404/409/500),业务码表达业务语义。
- **企业级高可用**:服务无状态,**浏览器会话也放共享 Redis**(平台签发会话标识并随 Cookie 下发,
  任何实例都能服务同一个会话),授权存储、权限缓存、登录失败计数、定时任务锁同样在 Redis,
  横向扩容直接加副本,**不需要负载均衡做会话亲和**。

## 企业级能力

平台按国内企业复杂场景补齐了以下能力(全部经管理端 `/api/v1/admin/**` 与自助端 `/api/v1/console/**` 配置):

| 能力 | 说明 |
|---|---|
| **多因子认证 (MFA)** | TOTP(RFC 6238,兼容 Google/Microsoft Authenticator),登录二步验证;10 个一次性恢复码(bcrypt 哈希存储);TOTP 码防重放;可强制全员启用 |
| **Passkey / WebAuthn** | 设备本地密钥 + 指纹/人脸登录,可单独作为一级凭据(含无用户名登录);单用户可绑多把,支持列表/重命名/删除;签名计数器克隆检测;`sign_count` 回退即拒绝。协议解析与签名校验由 WebAuthn4J 承担,不自行实现 CBOR/COSE |
| **国际化 (i18n)** | 中英双语,覆盖全部协议页与错误码。语言解析顺序:显式选择(`?lang=`)→ Cookie(`IAM_LOCALE`,365 天)→ 浏览器偏好 → 默认中文;语言切换链接保留原查询参数(不会丢 `state`/`client_id`)。文案集中在 `i18n/messages*.properties`,`BusinessException` 区分"错误码文案"(可翻译)与"调用方自拟文案"(不翻译);页面内联脚本的提示文案同样走服务端注入 |
| **口令策略** | 每 Realm 独立配置:最小长度、大小写/数字/特殊字符复杂度、禁止包含登录名、有效期、历史口令防重用(仅追加式口令历史表) |
| **登录策略** | 失败锁定(窗口期计数 + 锁定时长分离)、验证码阈值(失败 N 次后强制图形验证码)、强制 MFA;策略经 Redis 5 分钟缓存,改配置即时生效 |
| **验证码** | 登录失败达到阈值后强制校验,一次性消费,Redis 存储;REST 登录(`POST /api/v1/auth/login` 携带 `captchaId/captchaCode`)与服务端表单登录(`POST /login` 同名参数,认证 Provider 前置校验)双路径生效;`GET /api/v1/auth/captcha-required?username=` 供登录页按需展示验证码框 |
| **用户组** | 组是**权限主体**:成员集合 + 角色集合,成员经组继承组上的角色;**权限公式**:`(角色-菜单派生 ∪ 角色直接 ∪ 组角色 ∪ 用户 GRANT) − 用户 REVOKE`。组可按层级归属(`parent_id`),**层级只表达归属、不参与授权继承**(继承会让"撤掉一个组的权限"变得做不到);控制台按树呈现(缩进 + 层级路径),上级组可选可清空,**挂到自己的下级会被拒绝**(成环后任何层级遍历都绕不回来);删组时子组自动提升到上一级;成员数聚合查询,组成员可分页查看;可反查"某人属于哪些组" |
| **角色授权** | 角色权限整体覆盖式授予(`GET/PUT /api/v1/admin/roles/{id}/permissions`),按资源分组勾选,菜单派生权限与直接权限分列;权限变更在账号下次认证时生效 |
| **用户权限治理** | 按账号对单个权限 GRANT/REVOKE,可设理由与到期时间;控制台「用户 → 权限治理」提供有效权限来源诊断、特批增删改与范围筛选。求值与令牌签发共用同一公式并即时失效权限缓存;禁止自我特批,目标用户/权限强制同域;`USER_PERMISSION_OVERRIDE_SET/DELETE` 全量审计 |
| **应用门户** | 登录后落地 `/portal`:Vue 3 SPA(与控制台同工程第二入口,产物 `/portal-static/**`),展示当前用户有权访问的应用卡片,点击即静默单点进入(无二次登录);可见性支持全员/按角色/按权限码;非 OIDC 应用按入口地址直达。接口 `GET /api/v1/launchpad/apps`、`POST .../apps/{appCode}/launch`(支持应用传入 state/nonce/code_challenge 与回调白名单校验)。**启动路径只有两条且判定集中在服务端**:`SSO` 由 IAM 代发授权地址(客户端未强制 PKCE 时),`DIRECT` 直达应用入口;客户端强制 PKCE(公共客户端)时门户拿不到 verifier,代发必然被协议栈拒绝,故须把应用标记为**自带 SSO 发起端点**(`ssoSelfInitiated`),由应用自己生成挑战值再跳授权端点。卡片带"单点就绪状态"角标,未就绪的应用点击时给出可操作提示而不是协议错误页;登出后回跳同样受客户端 `postLogoutRedirectUris` 白名单约束。门户含应用搜索(名称/编码,`/` 快捷聚焦)、按分类分组、最近使用(本机记录)、实时问候与时钟 |
| **应用菜单与授权** | 菜单按应用归属,`menuMode` 可选 IAM 全量托管 / HYBRID 仅一级模块 / APP 应用自管;菜单挂权限即随角色授予派生;`GET /api/v1/launchpad/apps/{appCode}/menus` 下发已授权菜单树;应用授权名单支持 USER/ROLE 分类型整体覆盖 |
| **应用级授权 (SCOPED 令牌)** | 权限按应用命名空间(平台权限 `app_service_id IS NULL` 与应用权限互不冲突,应用内权限码可复用);角色新增 `APP` 容器绑定应用;令牌档 `SCOPED` / `LEGACY_GLOBAL`,`SCOPED` 只输出绑定应用的 `permissions` / `app_roles`,新应用默认 `SCOPED`、存量应用逐个切换;授权模式 `IAM_MANAGED` / `MAPPED` / `APP_MANAGED` 对接不同自管程度的老系统 |
| **权限回填** | 存量应用切 `SCOPED` 的前置工具:回填报告列出本应用正在依赖、但仍属平台命名空间或他应用的权限与菜单,给出建议策略与来源;导入按主键显式选择,`MOVE` 归属迁移(平台级且无域角色持有)或 `COPY` 复制到应用(域角色持有或已被他应用占用),幂等可重跑;域角色持有的平台资源只允许复制,否则其它应用的令牌会彻底失去它;控制台「应用门户 / 应用工作台 → 权限回填」抽屉完成全流程 |
| **管理控制台** | `/console` 单页控制台:24 个管理域(概览/用户/用户组/角色/权限/客户端/作用域/协议映射/身份提供方/SAML 应用/应用门户/应用菜单/身份域/安全策略/扩展属性/会话/令牌/授权同意/登录事件/审计日志/LDAP 目录/通知投递/发信配置/邮件模板),菜单按权限码裁剪,抽屉式表单、批量操作、破坏性操作二次确认、统一错误提示 |
| **应用管理员工作台** | `/console/workspace`:应用管理员无需任何全局角色即可独立交付本应用——概览/菜单树/应用角色/应用权限/角色映射/管理员名单六个页签,全部走应用作用域嵌套接口 `/api/v1/admin/apps/{appId}/workspace/**`(`@appAuthz.canManage`)。创建时服务端强制写入路径中的应用主键(菜单、权限)与 APP 容器(角色),跨应用/跨域主键一律按"不存在"处理;管理员名单只读,扩权须由全局管理员在「应用门户」操作 |
| **属性定义** | 自定义用户属性(STRING/NUMBER/BOOLEAN/ENUM/JSON),ENUM 类型校验可选值;唯一冲突检测 |
| **协议映射器** | 用户属性/Claim 模板注入 ID Token 与 Access Token,支持 Realm 级与 Client 级两级配置,按声明类型转换 |
| **客户端作用域** | 可选/默认作用域,与客户端多对多分配 |
| **身份提供方** | 第三方登录管理(敏感配置只写不读):客户端密钥以主密钥加密落库(与域签名私钥同级的信封加密,密文绑定域与别名),编辑时留空表示保持原值;IdP 级 Mapper 在回调时**真正生效**——属性映射把外部属性搬进本地扩展属性,角色映射把外部角色换成本地域级角色授权;外部 IdP 故障归为专用错误码(`IDP_UNAVAILABLE`,HTTP 502)并记失败登录事件,不落成"系统内部错误";删除提供方时解绑全部外部身份(本地账号保留) |
| **微信登录** | 扩展包 `beenest-iam-provider-wechat` 提供两种形态:**小程序登录**(客户端把 `wx.login()` 的 code 直送平台,平台换身份、自动建号、记录 openid,再签发标准授权码,令牌由标准授权码流程签发)与**扫码登录**(微信开放平台网站应用,浏览器跳扫码授权页,回调后建号并建立会话);接入说明见 `docs/wechat-integration.md` |
| **审计日志** | 关键操作全量审计(`@LogAudit`),按操作人/动作/目标类型/时间范围分页查询;**数据库级只追加约束**(触发器拒绝改写,留存期清理走事务内受控通道);**留存期**默认 180 天,每日按域清理并逐域留痕;**导出** CSV(带 UTF-8 BOM、公式注入防护),独立权限码 `audit:export` |
| **登录事件** | 认证链路留痕:`LOGIN_SUCCESS / LOGIN_FAILURE / MFA_FAILURE / LOGOUT / SESSION_TIMEOUT`,账号不存在也记录(撞库检测);支持按事件类型、用户、IP 溯源查询 |
| **会话管理** | 跨 Realm 在线会话查询、登录过的客户端明细、强制下线(统一走登出入口,下线同时触发单点登出广播) |
| **主体标识 (sub)** | OIDC `sub` 的取值可按身份域配置、按客户端覆盖:默认登录名(兼容存量接入),可切不可变用户主键(登录名改名不影响应用侧映射);切换逐应用推进。ID Token / Access Token / userinfo / 后通道登出令牌的 `sub` 同源;另恒定注入 `preferred_username` 作为可读登录名 |
| **单点登出** | OIDC RP-Initiated Logout(`/connect/logout`,`id_token_hint` + 登出回跳白名单 + `state` 回传,非法回跳 400 不跳转)与 Back-Channel Logout 广播:按会话逐个应用投递签名的 `logout_token`(RS256,含 `events`/`sid`,禁带 `nonce`),并发投递 + 2 秒总预算,失败限时不阻断退出;未登记通知地址的应用记为 `SKIPPED` 留痕。控制台「在线会话 → 登出广播」可查每次投递的 HTTP 状态与错误原因 |
| **SAML 2.0 IdP** | 与「SAML SP」相反的方向:本平台作为**企业 IdP** 向存量应用签发 SAML 断言,存量 SP(AD FS、Entra ID 联邦方、老 Java/.NET 系统)不改代码即可单点进入。每域三个端点 `/realms/{realmKey}/saml/{metadata,sso,slo}`,元数据自动发布 RSA 证书;证书由域密钥**确定性派生**(序列号取公钥 SHA-256 摘要、有效期取密钥生效时间、`SHA256withRSA` 签名确定),重启、多实例、未轮换密钥时字节完全一致,SP 侧登记一次长期有效。SSO 走**两段式**而不暂存 AuthnRequest:原请求地址原样编进 `return_to` 跨过登录页,防重放靠请求 ID 在 Redis 一次性消费(仅真正签发断言那次登记);HTTP-Redirect 绑定按**原始查询串**验签(解码后重拼必然验不过),HTTP-POST 绑定走自动提交页(带 `X-Frame-Options: DENY` 与最小 CSP);NameID 支持 persistent/transient/email/unspecified 四种格式,属性映射可按 `attr.xxx` 表达式取扩展属性。**SLO 在登记了 SP 证书时强制验签**——未验签的登出等于一个人人可用的踢下线接口。控制台「应用与协议 → SAML 应用」登记,详见 [`docs/saml-idp.md`](docs/saml-idp.md) |
| **SCIM 2.0** | `/scim/v2/Users` 与 `/scim/v2/Groups`,HR/AD 按标准协议同步人员与组织,不用为每家 HR 系统写一次适配。支持 `filter`(`eq`/`co`)、分页、`PUT` 与 `PATCH`(RFC 7644 PatchOp:成员增删在现有任职基础上进行,已有成员的岗位与主部门标记原样保留)。鉴权用 Bearer 令牌 + `scim:manage` 权限码,数据范围是令牌所属域;**组上的角色授权不通过 SCIM 变更**——HR 一次全量同步不该改掉权限。详见 [`docs/scim.md`](docs/scim.md) |
| **租户级主题** | 每个身份域可指定协议页主题(`iam_realm.theme`,留空跟随全局 `iam.theme.active`):登录页、MFA 二步验证页、授权同意页、错误页及其静态资源(`/theme-static/**`)按域解析;管理端「身份域」编辑时下拉选择已有主题目录(`themes/{name}/`),保存即校验主题存在;主题静态资源带缓存头(1 小时),换主题即时生效。**一套主题包覆盖三端**:设计令牌集中于主题目录 `static/css/tokens.css`(唯一事实源),门户与控制台(SPA 外壳)按同一顺序链接该文件,样式表只留默认值、运行时被按域覆盖——换肤不重新构建前端产物 |
| **账号运维** | 锁定/解锁(独立于启用禁用)、全端强制下线(令牌版本自增)、批量启停(仅同域) |
| **令牌管理** | 按用户列举已签发授权、一键吊销全部、吊销单条;吊销即写入 `jti` 黑名单,业务接口的无状态验签同步失效(不必等自然过期) |
| **授权同意 (consent)** | 客户端级 `require_consent` 开关,开启后首次授权展示主题化同意页(逐项勾选 scope);同意记录集中存储,管理端可查询与撤销(撤销后重新征求同意) |
| **密钥轮换** | Realm 签名密钥在线轮换:旧密钥立刻退役但公钥继续发布,已签发令牌自然过期前仍可验签;签名始终使用唯一生效密钥 |
| **运营仪表盘** | 用户/客户端/在线会话/今日登录成功失败/MFA 绑定率等聚合指标 |
| **机器对机器 (client_credentials)** | 机密客户端可绑定**服务账号**(`user_type=SERVICE`)换取令牌,令牌携带服务账号的权限全集,用于服务间调用与自动化运维;服务账号**禁止交互式登录**(只走凭据模式) |
| **三方登录接线** | `/broker/{realm}/{alias}/login` 发起、`/callback` 回调:state 一次性防伪(Redis 存,GETDEL 消费)、本地账号 find-or-create 绑定(登录名按 `别名-外部标识` 生成,超长或含非法字符时补摘要后缀,避免外部标识不可控导致账号永远建不出来)、登录页按域渲染提供方入口;新增提供方只需加一个扩展包;**有端到端集成测试**(内嵌 mock OIDC 提供方跑完发起→回调→建号→登录态) |
| **会话生命周期** | 会话状态存**共享 Redis**(平台签发会话标识并随 `IAM_SESSION` Cookie 下发,任意实例可服务同一会话,横向扩容无需粘性会话):空闲超时按请求滑动续期、最大存续时长由守护过滤器判定(取"域最大时长"与"刷新令牌有效期"的较小者——**令牌到期即会话注销**);会话异常结束由守护过滤器即时收尾(记录置为 `EXPIRED` + 清快照 + 记 `SESSION_TIMEOUT` 事件),用户不再回来的会话由**巡检任务**兜底收尾,不留僵尸"在线会话";巡检与审计清理等定时任务在多实例下由**分布式锁**保证只有一个实例执行。登录策略可设**同账号并发会话上限**(0 表示不限制):超限时新登录顶掉最旧会话,被顶会话记为 `REPLACED`、Redis 快照立即失效,管理端「在线会话」可区分"被顶下线"与"超时失效" |
| **IP 维度限流** | 账号维度的锁定挡不住密码喷洒(同一 IP 轮换账号,每个账号只失败一次);登录策略新增 `ipMaxFailAttempts` / `ipLockDurationMinutes`,从来源 IP 维度独立限流 |
| **短信验证码登录** | 手机号 + 短信验证码作为**独立第一因素**(不是 MFA 附属):域策略开关(`smsLoginEnabled`)+ 有效期/每小时发送上限/校验失败上限四项参数;短信通道为 SPI(内置阿里云,RPC 签名算法有单测固化),换短信商只改 `iam.sms.channel` 配置。**未注册/未验证手机号静默不发送**——响应与已发送不可区分,攻击者无法批量探活号码,平台也不给空号烧短信;验证码 GETDEL 一次性消费,连续输错达上限即作废;登录失败与"号码未注册"统一文案,真实原因只进登录事件。仅**已验证**手机号(`phone_verified`,同域唯一)可作登录定位键;管理端用户页可代标验证态(HR 导入场景) |
| **LDAP/AD 用户联合** | 企业目录(AD/OpenLDAP)作为**兜底认证源**:本地账号优先,本地不存在、或本地账号没有口令凭证(JIT 账号)时按登录名到目录 bind 认证(用户 DN + 输入口令,覆盖目录口令策略),通过则 JIT 创建本地账号(EXTERNAL 类型,记录来源 DN/UUID 于扩展属性),此后每次登录仍走目录校验口令、复用同一本地账号。每域一条配置(地址、只读搜索账号、用户搜索基、过滤器、属性映射、超时),搜索基限定员工 OU 避免目录管理员被 JIT;登录名进过滤器前按 RFC 4515 转义防注入;bind 口令信封加密、管理接口只写不读;**保存前可测试连接**(用表单值直连目录,不落库)。除 JIT 外支持**目录用户全量同步**:按页读取目录(突破 AD 单次 1000 条限制),只补建缺失账号、只补全空缺资料与目录来源属性,已有本地口令的账号整体跳过,不删除、不覆盖本地口令/角色/状态;支持先预演(dry-run)评估影响面,报告给出新建/更新/跳过/失败计数与样例原因。目录不可达/账号不存在/口令错误三种失败对用户一律"账号或密码错误",真实原因分开记登录事件;与登录失败锁定、验证码门禁、IP 限流同一套风控计数。控制台「LDAP 目录」页配置(`ldap-config:view/update` 权限码) |
| **客户端准入策略** | 客户端可声明**允许的第一因素集合**(账号口令 / 域目录 LDAP / 短信 / 微信 / 外部 OIDC / 外部 SAML)与**是否强制 MFA**;授权端点按目标客户端判定,不满足时转补验页(用被接受的方式再证明一次),补验成功**升级原会话**而非新建;白名单与域级可用方式无交集时管理端拒绝保存 |
| **账户自助** | 三项能力共用一套一次性票据设施(`GETDEL` 消费、限时有效、链接只带不可推导票据):**找回口令**(邮箱链接或短信验证码,账号不存在/通道故障/发送失败对外响应完全一致,不构成账号枚举;IP 与账号双层限流)、**邮箱验证**(落地页无需登录,从任意设备的邮件客户端点开即可)、**账户待办**(`UPDATE_PASSWORD`/`VERIFY_EMAIL`/`CONFIGURE_TOTP`,拦截发生在会话可用范围上——页面 302 到待办页、接口 403,授权端点同样拦截,避免绕过待办直接换令牌)。管理员建号设初始口令、重置口令均自动挂"必须改密";改密/验证/绑定完成后自动摘除。邮件通道为 SPI(内置 SMTP),投递结果落 `iam_notification_log` 供排障。详见 [`docs/account-self-service.md`](docs/account-self-service.md) |
| **通知与发信** | 邮件通道按域配置:每域可单独启用 SMTP(地址/端口/账号口令/发件人/STARTTLS 或 SSL/超时),口令信封加密、管理接口只写不读;未配置的域回落到部署级全局 SMTP。**找回口令、邮箱验证、安全提醒、账号开通**四类模板均可在管理端改写与恢复默认,找回/验证模板强制保留 `resetUrl`/`verifyUrl` 占位,避免改坏跳转链接。安全事件(口令变更、动态口令绑定/解绑、Passkey 增删、账号开通,以及**新 IP 登录**——按 IPv4 /24、IPv6 /64 归并,同网段换地址不打扰)异步发信,不阻塞登录链路;每次投递(成功或失败)落「通知投递」页,可按渠道、结果、收件地址、模板与时间筛选,**投递失败必须可查**;投递记录默认保留 90 天,由定时任务分批清理(保留期可配,0 表示永久)。权限码 `mail-config:view/update`、`notification:view` |
| **用户中心** | 登录用户自助页 `/account`,一页收齐**账号资料 / 登录凭证 / 我的授权 / 登录设备 / 个人数据导出**:凭证区列出口令、动态口令(含剩余恢复码)、Passkey 并可绑定/关闭/增删;授权区列出应用名、scope 与首次授予时间,可逐个撤销;设备区可注销非本机会话(当前会话请走退出登录);导出为 JSON 附件,含资料、属性、角色、组、凭证元数据、授权记录、设备与最近 50 条登录事件,**不含**任何密钥。所有数据以当前登录主体为边界,跨域客户端一律剔除。详见 [`docs/account-center.md`](docs/account-center.md) |
| **OpenAPI 文档** | springdoc 接入,按调用方分四组(管理端 / 用户自助端 / 应用门户 / 认证接口),Swagger UI 带 bearer 授权;**生产缺省整组关闭**(`springdoc.api-docs.enabled=false`,端点 404,不暴露 API 结构),联调环境显式开启,dev 档默认开 |

### 安全设计要点

- **软删除**:所有表带 `deleted` 字段,唯一约束使用 `WHERE deleted = FALSE` 部分索引;
- **防重放**:TOTP 码与恢复码均一次性消费,恢复码 bcrypt 哈希;
- **定长时间比较**:TOTP 校验防时序侧信道;
- **失败关闭**:认证路径上的校验方法对任何非法输入返回拒绝,不泄露内部异常;
- **令牌类型与用途分离**:访问令牌与 ID Token 都带 `token_type` 标记,解码链拒绝把 ID Token
  当 Bearer 令牌用;ID Token 不写权限码,即使泄露也换不到接口权限。访问令牌另带 `token_kind`
  (`PLATFORM` / `MACHINE` / `APPLICATION`),IAM 自身接口(`/api/v1/admin|console|launchpad/**`)
  只接受会话与平台类令牌——业务应用令牌的授权范围是该应用的权限命名空间,不该反过来调管理接口;
- **CSRF 与来源双保险**:CSRF 豁免的判据从"请求头带 Bearer 前缀"收紧为"令牌验签通过"
  (只看前缀的话,一行伪造请求头就能关掉整个 CSRF 防护);状态变更请求另校验 `Origin`/`Referer`
  为本站或已登记前端来源,覆盖登录类接口的登录 CSRF(把受害者登录成攻击者账号);
- **口令存储**:新口令用 Argon2id,历史 `{bcrypt}` 密文继续可校验、下次改密时自动升级;
  不注册 `{noop}`——该前缀允许明文通过校验,一旦有残留就等于口令校验形同虚设;
- **租户边界**:身份域本身不能由租户管理。非平台域管理员只能查看与修改自身域,
  建域、删域、轮换签名密钥只允许平台域(`iam.platform-realm-key`,默认 `default`)管理员执行;
- **会话与令牌同源同新鲜度**:浏览器会话与 Bearer 令牌是同一份授权决策的两个视图,必须用同一套规则判断"这份权限还算不算数":
  - **账号状态 + 令牌版本**:账号被禁用/锁定/删除,或执行了全端下线 → 会话立即作废(接口 401、页面跳登录),与令牌路径的判定完全一致;
  - **权限代次**:主体里记着"这批权限是第几代算出来的",当前代次变了就重算并**就地刷新会话**——用户不必重新登录,权限却已经是新的,不留任何"撤销还没生效"的时间窗;
  - 已签发的访问令牌不随权限变更即时作废(暴露窗口 = 访问令牌有效期,默认 30 分钟),需要立即收口时用「全端下线」;
- **会话与实例无关**:浏览器会话状态存共享 Redis,平台签发会话标识并随 `HttpOnly` + `SameSite=Lax` 的 Cookie 下发;
  登录态不落在任何实例的进程内存里,请求落到哪台实例都能接着服务——这是"横向扩容直接加副本"的前提;
  登录失败消息这类一次性文本也走共享存储的一次性票据,URL 里只带不可推导的令牌,既不泄露内部状态也不受实例分布影响;
- **审计不可篡改**:`iam_audit_log` 由数据库触发器保护,改写与删除一律拒绝;
  留存期清理是唯一例外,且必须由平台在事务内显式打开清理通道,清理动作按域留痕;
- **主体锚点不可变**:`sub` 可配置为不可变用户主键(默认仍是登录名以兼容存量接入),登录名变更不影响应用侧映射;
- **私钥信封加密**:身份域 RSA 签名私钥以 AES-256-GCM 加密落库(PBKDF2-HMAC-SHA256 21 万次迭代派生密钥,每条记录独立随机盐,认证标签绑定域标识,密文无法跨域搬运);未配置主密钥时平台**拒绝启动**,不会退化成明文落库
- **权限缓存代次**:权限求值连查角色/菜单/组/特批多张表,每次签发令牌全量求值在用户量上来后成为热点;缓存键带"权限代次",任何影响权限的写操作让代次自增,旧代次键自然不再命中——**一次自增让整域缓存失效**,既不必逐用户扇出删键,也不会漏掉某个改动点导致撤销不生效
- **测试覆盖**:TOTP RFC 6238 官方向量、Base32 RFC 4648 已知答案、口令策略、登录锁定计数器、MFA 绑定全生命周期、
  Redis 授权存储(用户维索引 / state 索引 / scope 持久化 / jti 黑名单 / 授权码签发时记录客户端会话)、
  后通道登出广播(真实 HTTP 接收端验证 `logout_token` 声明、未登记地址留痕、5xx 记失败)、
  主体标识解析(域策略 / 客户端覆盖 / 客户端覆盖优先于域)、有效角色合并、用户特批生命周期与来源诊断、
  应用作用域工作台(强制作用域、跨应用主键拒绝、管理员边界)与审计目标主键解析
  均有单元测试;
  另有**集成测试**(`beenest-iam-server/src/test/.../integration/`)用 Testcontainers 起真实的
  PostgreSQL 与 Redis 跑完整协议栈,不依赖外部服务、CI 上可直接跑,覆盖:
  授权码+PKCE 全链路、**RFC 7009 吊销后令牌立即失效**、**刷新后旧访问令牌立即失效**、
  **凭据模式令牌可访问管理接口**、**权限撤销后立即生效**;
  用户组(树装配与筛选、层级成环拦截、上级组清空、删组子组提升、成员分页与全量主键、用户反查所属组);
  会话授权时效(禁用账号 → 已登录会话立即失效;撤销角色/特批 → 会话就地刷新且不被登出);
  **应用门户单点闭环**(门户启动 → 授权 → 换令牌 → userinfo 全链路;强制 PKCE 的客户端得到可操作提示而非协议错误页;
  应用自发起时门户直达入口)、**三方登录闭环**(内嵌 mock OIDC 提供方跑完发起→回调→建号→登录态;
  属性映射在回调时真正生效;外部 IdP 故障归为上游错误而非系统内部错误)、
  **审计合规**(数据库级只追加约束拒绝改写、超期记录按域清理并留痕、按条件导出 CSV)、
  **分布式会话**(登录态只存在于共享 Redis,删掉会话键即掉登录态;巡检把失效会话收尾)、
  **Passkey 报文与选项**(Base64URL 无填充往返、非法报文转业务错误、注册选项含 excludeCredentials、
  无凭证时不下发 allowCredentials);
  **SAML IdP 协议闭环**(本平台签发的断言能被 Spring Security 的 SP 端校验器完整验回;未签名断言与
  篡改断言均被拒;元数据同时发布证书与两种 SSO 绑定;Redirect 绑定签名校验;请求 ID 一次性消费;
  证书确定性派生——两次独立启动得到逐字节相同的证书);
  **令牌安全控制**(ID Token 不得作为访问令牌、缺受众即拒绝、应用令牌不得调 IAM 自身接口、
  匿名一律拒绝、CSRF 豁免必须验签通过、跨站来源的写请求被拒)、
  **SCIM 过滤表达式**(`eq`/`co` 解析、属性名大小写不敏感、多条件与未支持运算符显式报错)、
  **认证步骤链编排**(阶段顺序不受 bean 注册顺序影响、同阶段按 order 与步骤名定序、
  `appliesTo` 跳过不视为失败、任一步骤失败即终止后续、上下文默认值与状态流转)、
  **跨源与来源判定**(本站来源按 host:port 判定不比对协议、代理转发头兜底、未登记来源拒绝、
  预检响应头完整);
  合计 547 个测试(全模块 `./mvnw test`,含 44 项真实 PostgreSQL/Redis 容器集成),CI 见 `.github/workflows/ci.yml`;
  已在本机 Docker(PostgreSQL/Redis)完成运行时冒烟:登录/验证码门禁/失败锁定、MFA 绑定→二步登录→恢复码→防重放、
  组角色权限继承、审计落库、OAuth2 授权码+PKCE 全链路(含同意页与令牌吊销黑名单)、密钥轮换后新旧令牌共存、
  V1.0.2→V1.1.1 存量库平滑升级、刷新令牌轮换、`/connect/logout` 登出回跳白名单、后通道登出真实投递、
  `sub` 切换为不可变用户主键后 ID Token 与 userinfo 保持一致、客户端覆盖优先于域策略;
  管理控制台另经真实浏览器逐页巡检(19 个管理域无脚本错误)并完成
  开户→编辑→角色授予→重置口令→删除、客户端登记→轮换密钥→删除、角色权限勾选保存、
  用户权限治理(GRANT 创建 → 生效权限与来源刷新 → 自我特批拦截 → 删除归零 → 审计留痕)等端到端操作;
  应用管理员(无任何全局角色)登录后控制台只出现「应用工作台」入口,可在工作台内完成菜单/角色/权限/角色映射
  的增删改与授予,访问他应用工作台与平台接口一律 403;
  权限回填已在真实环境跑通:回填报告列出 83 项候选(域角色持有,全部只能复制),执行复制后应用下生成
  81 条权限 + 2 条菜单副本并改写授权引用,平台原件保持不变,重复导入幂等、刷新后报告清零,
  域角色持有的资源执行归属迁移被拒绝并给出冲突说明;
  V1.2.4(Passkey 凭证表)在真实 PostgreSQL 17 上验证:全量迁移按序应用通过,
  唯一索引拒绝跨账号复用同一凭证标识、软删后可重新绑定同一把密钥、`U1.2.4` 回滚后表与索引全部移除、
  回滚后可再次升级;
  V1.2.5(SAML 服务提供方登记)同样在真实 PostgreSQL 17 上验证:**全量 26 个迁移按序应用零报错**,
  两条部分唯一索引分别挡住"同域重复实体 ID"与"同域重复别名"、软删后可重建同一实体 ID、
  跨域可复用同一实体 ID,`U1.2.5` 回滚后表、索引与两条 `saml-sp:*` 权限及其角色授予全部移除,
  回滚后可再次升级。

## 已知能力边界

下列能力**当前不具备**,属于新功能而非缺陷,已单独排期,不在本轮修复范围内。使用前请确认场景不落在其中:

| 能力 | 现状 | 影响 |
|---|---|---|
| 自助注册 | 无自助注册入口,账号由管理员或 JIT(三方登录 / LDAP)创建 | 对外注册场景需自行加注册页 |
| 行级 / 字段级数据权限(ABAC) | 只有 RBAC + 应用级命名空间,无属性条件策略 | 数据范围过滤需在业务应用侧实现 |
| 客户端凭据自助轮换 | 客户端密钥只能由管理员在管理端轮换 | 接入方无法自助换密钥 |
| 告警推送 | 已提供 Prometheus 告警规则模板与 SLO 基线(`ops/prometheus/iam-alerts.yml`,见部署文档第 9 节),但**告警通道与值班路由不在平台内**:需在运维侧接入 Alertmanager(邮件/钉钉/企微) | 规则要落到监控系统才会响,未接入时仍靠人看指标 |
| 备份执行 | 已提供备份脚本与恢复流程(`ops/backup/iam-backup.sh`),但**平台不代管备份**:定时调度、异地存放与恢复演练由运维侧建立 | 未执行脚本时无备份可用 |
| 扩展包热重载 | 外置扩展包在**启动时**装载,换包需重启(类无法卸载) | 换提供方版本需要一次重启 |
| 小程序账号与扫码账号合并 | 同一微信用户在两入口下靠 `unionid` 合并,**仅当小程序已绑定同一开放平台账号**时成立 | 未绑定时是两个账号 |
| Passkey attestation 信任链 | 非严格模式,不校验认证器证书链到受信根;任何厂商的认证器都能绑定 | "只允许公司发的安全密钥"需切严格模式并配信任锚 |
| Passkey 作为第二因素 | Passkey 当前是一级凭据,不能"口令之后再验一次 Passkey";二步验证仍走 TOTP | 认证步骤链已就绪(`Stage.SECOND_FACTOR` 是扩展点),补一个 Passkey 验证步骤即可,无需改主干 |
| SAML IdP 的协议覆盖范围 | 支持 **SP 发起**的 SSO 与 SLO;不支持 IdP 发起 SSO(无 AuthnRequest 就没有 `InResponseTo`)、断言加密、metadata 签名、back-channel 登出 | 要求"门户点一下就把所有人推到各系统"或要求加密断言的金融类对端,需另行扩展 |
| SCIM 同步规模 | 按 `externalId` 查询最多扫描 1000 条(该字段无独立索引);单个组的成员读写单页上限 10000;不支持 `bulk`、`/Me`、`ServiceProviderConfig`、组嵌套 | 万人以上租户按 `userName` 定位后再同步;超大组改用管理端维护 |
| 离线令牌(`offline_access`) | **不支持**:刷新令牌随会话结束失效,客户端登记含该 scope 会被拒绝 | 需要"无人值守长期刷新"的场景需另行评估,当前口径是"会话结束即令牌失效" |
| 客户端密钥双密钥并存 | 轮换后旧密钥立即失效,换密钥需要一次短暂中断 | 第三方接入方要求零中断时需补并存期 |

## 快速开始

> 已发布镜像:`registry.cn-hangzhou.aliyuncs.com/beenest/beenest-iam:1.0.0`(linux/amd64)。
> 生产部署不要用下面的本地编排:用 `deploy/docker-compose.yaml` 配合
> `deploy/.env.example` 生成的配置,镜像与配置项全部由部署侧注入。
> 构建与推送步骤见 [`docs/deployment.md`](docs/deployment.md) 第 1.2 节。

### 方式一:容器一键部署(PostgreSQL + Redis + 应用)

```bash
# 1. 打包(离线,复用本地 Maven 缓存)
./mvnw -o -B -DskipTests package
mkdir -p .docker-runtime
cp beenest-iam-server/target/beenest-iam-server-*.jar .docker-runtime/app.jar

# 2. 起全栈:数据库 + 缓存 + 应用(走 prod 配置档)
IAM_DOCKERFILE=Dockerfile.runtime docker compose up -d --build

# 3. 验证(宿主端口默认 8090:8080 常被本机其他服务占用,容器内仍是 8080)
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/login          # 登录页 200
curl -s http://localhost:8090/.well-known/openid-configuration                # 发现文档
docker compose exec iam curl -s http://127.0.0.1:9090/actuator/health         # 管理端口未发布到宿主
docker compose logs -f iam                                                    # 启动日志(含 32 个迁移)
```

默认引导账号 `admin` / `Admin@12345`(仅本地默认值,由 `IAM_BOOTSTRAP_ADMIN_PASSWORD` 覆盖)。
容器按安全基线运行:非 root、只读根文件系统、丢弃全部 capability、禁止提权,
管理端口 9090 不对宿主发布。细节见 [`docs/deployment.md`](docs/deployment.md) 第 1.1 节。

> `IAM_DOCKERFILE=Dockerfile.runtime` 表示复用上一步的产物,不重复编译。
> 去掉该变量则走根目录 `Dockerfile` 的多阶段构建——从源码一步出镜像,
> 代价是首次构建要在容器内下载全部 Maven 依赖。

### 方式二:源码运行(改代码时用)

```bash
# 1. 启动基础设施(位于 beenest-iam-server/)
docker compose -f beenest-iam-server/compose.yaml up -d

# 2. 安装到本地仓库(首次或改动后)
./mvnw -DskipTests install

# 3. 启动(必须在模块目录内执行,否则插件绑定到父工程会报 "Unable to find a suitable main class")
#    dev 档面向本地开发;生产用 prod 档(默认档),缺失必填项会直接启动失败
cd beenest-iam-server && SPRING_PROFILES_ACTIVE=dev ../mvnw spring-boot:run

# 4. 验证
curl http://localhost:8080/realms/default/.well-known/openid-configuration
curl http://localhost:9090/actuator/health        # 独立管理端口
curl http://localhost:9090/actuator/prometheus    # 指标采集入口

# dev 档附带 API 文档(prod 档缺省关闭,不暴露 API 结构)
open http://localhost:8080/swagger-ui.html        # 四组:管理端/自助端/门户/认证接口

# 5. 控制台前端(改了控制台才需要)
cd beenest-iam-web/frontend
npm install
npm run test                                     # 控制台纯函数单元测试(node 内置运行器,零额外依赖)
npm run build                                    # 构建产物直接落到 beenest-iam-web 的静态资源目录
```

> **管理端口**:actuator 跑在独立端口(默认 9090,`MANAGEMENT_PORT` 可改),
> 不与业务端口共用安全过滤链——放在业务端口上会被登录页拦截成 302,采集器拉不到指标。
> 该端口**不得对公网开放**,由网络策略/防火墙限制在运维网段内。

> **两个必须显式提供的配置**(prod 档缺失即启动失败):
>
> | 变量 | 用途 |
> |---|---|
> | `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接 |
> | `IAM_REALM_KEY_SECRET` | 身份域签名私钥的加密主密钥。**私钥以 AES-256-GCM 信封加密落库**,没有主密钥则私钥只能明文入库,平台拒绝启动。生成:`openssl rand -base64 32` |
>
> 改主密钥前必须先用旧密钥把私钥重新加密(或轮换签名密钥),否则旧密文解不开,域将无法签发令牌。
>
> **邮件通道按需启用**(缺失不影响启动,只是找回口令/邮箱验证不可用):
> `IAM_MAIL_CHANNEL=smtp` + `IAM_MAIL_HOST` / `IAM_MAIL_PORT` / `IAM_MAIL_USERNAME` /
> `IAM_MAIL_PASSWORD` / `IAM_MAIL_FROM`。全局配置作为兜底;管治理由更强的场景可在控制台
> 「发信配置」按域覆盖(域级启用且填写完整的 SMTP 优先,未配置的域回落到全局)。
> 配置项清单见 [`docs/account-self-service.md`](docs/account-self-service.md)。
>
> **Passkey 按需启用**(缺失不影响启动,只是登录页不出现免密入口):
> `IAM_WEBAUTHN_ENABLED=true` + `IAM_WEBAUTHN_RP_ID`(如 `id.example.com`)+
> `IAM_WEBAUTHN_ORIGIN`(如 `https://id.example.com`)。`rp-id` 必须是 `origin` 的注册域或父域,
> 且上线后不可变更。详见 [`docs/passkeys.md`](docs/passkeys.md)。

浏览器打开 `http://localhost:8080/` 登录后落到应用门户 `/portal`;管理员可再进 `/console` 管理控制台。
首次部署建议先在「应用门户」登记应用、在「应用菜单」建菜单、在「角色」里授予菜单与权限。控制台资源位于 `/console-static/**`,与登录页主题互不影响。

> 引导管理员账号**只**在设置了环境变量 `IAM_BOOTSTRAP_ADMIN_PASSWORD` 时创建,口令不落在任何配置文件、脚本或文档里;
> 未配置该变量时服务照常启动但不创建账号,需自行在数据库或管理端建号。
> 已存在同名账号时跳过创建(重启幂等),不会覆盖已修改的口令。

> 连接本地已有的 PostgreSQL/Redis(非 compose 自建)时,须关闭 Boot 4 的 Compose 自动覆盖:
> `SPRING_PROFILES_ACTIVE=dev SPRING_DOCKER_COMPOSE_ENABLED=false DB_URL=jdbc:postgresql://<host>:<port>/<db> REDIS_PORT=<port> ../mvnw spring-boot:run`
> (在 `beenest-iam-server/` 目录内执行;Boot 4 启动时会自动拉起 `compose.yaml` 里的 postgres/redis 并以更高优先级覆盖数据源配置)
>
> **开发时请用 fat-jar 或先 `mvn install`**:在模块目录里跑 `spring-boot:run` 会从本地仓库取兄弟模块的
> jar,仓库里若还是旧版本,运行期会加载旧代码与旧迁移脚本(现象:日志出现 "Schema has a version newer
> than the latest available migration" 且新表缺失)。

详细设计文档见 `docs/`:

| 文档 | 内容 |
|---|---|
| [`docs/client-integration.md`](docs/client-integration.md) | 客户端接入指南:OIDC 流程、令牌声明、菜单与权限对接、门户跳转、排错 |
| [`docs/saml-integration.md`](docs/saml-integration.md) | 企业 IdP 接入指南(SAML 2.0 **SP** 方向,本平台当 SP):端点、配置项、AD FS / Entra ID / Keycloak 侧步骤、校验项与故障对照 |
| [`docs/saml-idp.md`](docs/saml-idp.md) | SAML 2.0 **IdP** 方向(本平台当 IdP 给存量应用发断言):端点与登记字段、属性映射表达式、确定性证书派生、两段式 SSO 与防重放、SLO 验签、排障对照 |
| [`docs/app-access-architecture.md`](docs/app-access-architecture.md) | 应用接入与门户架构:现状审查、缺口清单、目标模型与功能服务设计 |
| [`docs/admin-console.md`](docs/admin-console.md) | 管理控制台分层、权限模型与扩展方式 |
| [`docs/organization-position.md`](docs/organization-position.md) | 组织架构与岗位:部门/组同树、岗位字典、任职模型、权限链路、令牌声明与验证清单 |
| [`docs/adr-app-scoped-authorization.md`](docs/adr-app-scoped-authorization.md) | ADR-12 应用级授权模型:权限命名空间、应用角色、令牌裁剪、应用管理员与权限回填的决策与实施记录 |
| [`docs/deployment.md`](docs/deployment.md) | 部署与高可用:多实例部署的依赖前提、会话与锁的存放位置、配置项清单、扩容与排障 |
| [`docs/runbook.md`](docs/runbook.md) | 运维手册:账号锁定潮、密钥轮换、第三方 IdP 故障、主密钥遗失、Redis 故障等场景的处置步骤 |
| [`docs/code-review-2026-10.md`](docs/code-review-2026-10.md) | 代码 Review 与企业级可交付性评估:已修复项、交付前必须收口的安全项、架构与能力对照 |
| [`docs/keycloak-gap-analysis.md`](docs/keycloak-gap-analysis.md) | 对照 Keycloak 26 的功能差距分析:19 项差距分级(P0 必补 / P1 值得 / P2 不建议)与补齐路径 |
| [`docs/account-self-service.md`](docs/account-self-service.md) | 账户自助:找回口令、邮箱验证、账户待办(Required Action)的语义、拦截规则、票据设计与邮件通道配置 |
| [`docs/passkeys.md`](docs/passkeys.md) | Passkey(WebAuthn):配置口径、挑战一次性设计、签名计数器克隆检测、接口与排障 |
| [`docs/i18n.md`](docs/i18n.md) | 国际化:语言解析顺序、文案键约定、新增文案与新增语言的步骤、脚本内联文案写法 |
| [`docs/account-center.md`](docs/account-center.md) | 用户中心(`/account`):能力边界、授予时间与同意记录的双键设计、越权处理、个人数据导出的字段范围 |
| [`docs/scim.md`](docs/scim.md) | SCIM 2.0:端点、资源映射、过滤器、PATCH 支持范围、错误体、与规范的差异、同步规模边界 |
| [`docs/authentication-steps.md`](docs/authentication-steps.md) | 认证步骤链:四阶段与七个内置步骤、扩展方式(加前置检查/第一因素/第二因素)、与准入判定的边界、排障 |
| [`docs/api-versioning.md`](docs/api-versioning.md) | API 版本演进与弃用策略:什么算兼容变更、弃用流程与时长、响应头约定、已登记的弃用 |
| [`docs/production-readiness.md`](docs/production-readiness.md) | 投产交付清单:安全基线收口项、本轮不做项及理由、验收记录 |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更日志:新增能力、安全变更、**行为变更与升级指引**、数据库迁移清单 |

登录/MFA/授权同意/错误页/控制台视觉稿见 `docs/screenshots/`。
