# 应用接入与应用门户:现状审查与目标架构

> 审查对象:beenest IAM V1.0.5(Spring Authorization Server 7 + Spring Boot 4)
> 审查范围:客户端接入能力、应用目录、应用级菜单与权限、登录后门户与跳转、登出联动
> 配套文档:接入方视角见 [`client-integration.md`](./client-integration.md)

---

## 0. 结论

**设计方向是对的,骨架也基本齐了,但闭环缺四块。**

| 问题 | 现状 | 判定 |
|---|---|---|
| 客户端应用如何集成 | OIDC 授权码 + PKCE、多租户 issuer、JWKS、userinfo、revoke、introspect、end_session 全部可用 | ✅ 满足 |
| 应用服务是否独立建模 | `iam_application_service` 独立于 `iam_client`,含可见性、入口、分类、排序 | ✅ 满足 |
| 应用自己的菜单权限怎么管 | 有 `iam_menu` / `iam_role_menu`,菜单挂权限可派生;**但菜单不归属应用、无写接口、无下发接口** | ⚠️ 模型有,能力缺 |
| 登录后查看授权应用 | `GET /api/v1/launchpad/apps` 已实现可见性过滤;**无用户门户页面** | ⚠️ 半成品 |
| 点多个应用直接跳转 | 门户 302 到应用入口地址,授权请求由应用发起 | ✅ 满足 |
| 企业级完整性 | 缺单点登出广播、应用级审计、SDK、按客户端裁剪权限 | ❌ 未达 |

一句话:**协议面是企业级的,治理面还是"表 + 半个 API"**。把下面 P0 清单补齐,才算真正支撑多应用企业环境。

---

## 1. 判定标准

企业级 IAM 的应用接入,至少要同时满足五条:

1. **接入标准化**:任何技术栈都能用标准协议接入,不依赖私有 SDK 才能跑通;
2. **授权可治理**:应用可见性、应用内权限、菜单,都能在管理面维护并留痕,而不是改库;
3. **身份与权限分离**:认证在 IAM,业务鉴权在应用,权限码是唯一契约;
4. **会话可收敛**:登录、登出、强制下线能在合理时间内传导到所有应用;
5. **多应用可运营**:应用数量增长后,门户、分类、权限命名、审计仍然清晰。

---

## 2. 现状盘点

### 2.1 已建成

| 能力 | 实现位置 | 说明 |
|---|---|---|
| 多租户 issuer | `IamSecurityConfiguration#authorizationServerSettings` + `RealmContextFilter` | `/realms/{key}` 路径解析域,`multipleIssuersAllowed` |
| 授权码 + PKCE | Spring Authorization Server 协议链 | `response_type=code`,`S256`,公共客户端强制 |
| 客户端登记 | `ClientController` / `ClientServiceImpl` | 类型、回调、scope、PKCE、同意、TTL、轮换密钥 |
| 令牌定制 | `IamJwtTokenCustomizer` | 注入 `realm`/`uid`/`realm_roles`/`client_roles`/`app_roles`/`groups`/`permissions`/`token_version` |
| 协议映射 | `ProtocolMapper` 两级(域/客户端) | 自定义 claim |
| 权限求值 | `IamPermissionManager` | `(菜单派生 ∪ 角色直接 ∪ 组角色 ∪ GRANT) − REVOKE` |
| 应用目录 | `ApplicationServiceController` | 应用编码、类型、SSO 方式、绑定客户端、入口、分类、可见性 |
| 应用可见性 | `ApplicationVisibilityService` | `PUBLIC` / `PERMISSION` / 授权记录;门户列表、启动、授权端点与凭据登录共用同一口径 |
| 门户 API | `LaunchpadController` / `PortalLaunchController` | 卡片列表 + 菜单树 + 302 启动跳转 |
| 登出端点 | `/connect/logout` | RP 发起登出,`id_token_hint` + `post_logout_redirect_uri` |
| 强制下线 | 令牌版本 + 会话终结 | `token_version` 自增,验签时失效 |

### 2.2 缺口清单

| # | 缺口 | 证据 | 影响 | 优先级 |
|---|---|---|---|---|
| G1 | **菜单不归属应用** | `Menu` 仅 `realmId`/`parentId`,无 `appServiceId` | 多应用共用一个 realm 时菜单无法隔离,只能靠编码前缀约定 | P0 |
| G2 | **菜单无管理接口** | `MenuMapper` 只有 `selectById/selectByIds/selectByRealmId` | 菜单只能写 SQL 维护,无法运营 | P0 |
| G3 | **角色-菜单无写接口** | `RoleMenuMapper` 只有两个 select;迁移脚本无 `iam_role_menu` 种子 | 运行时该表为空,**菜单派生权限一次都不会发生** | P0 |
| G4 | **菜单无下发接口** | 无 `/api/v1/launchpad/apps/{appCode}/menus` | 应用只能拿 `permissions` 自己拼菜单,两套事实源必然漂移 | P0 |
| G5 | **应用授权名单只读** | `IamApplicationServiceManager` 仅 `listGrantedAppServiceIds`;无写控制器 | `visibility=ROLE` 实际不可用(除非改库) | P0 |
| G6 | **无用户门户页面** | `console/static/pages/` 18 个页面无 launchpad;`/console` 是管理面 | 登录后无处"看应用",API 空转 | P0 |
| G7 | ~~**launch 的 state 由 IAM 生成**~~ | 已重构:门户不再代发授权地址,`state`/`nonce`/PKCE 由应用生成 | — | ✅ P0 完成 |
| G8 | ~~**launch 固定取第一个回调地址**~~ | 已随 G7 一并消失:门户不再解析回调地址 | — | ✅ P1 完成 |
| G9 | ~~**卡片不含 entryUrl**~~ | 已改为卡片不下发地址,统一由 `/portal/launch/{appCode}` 302 | — | ✅ P1 完成 |
| G10 | ~~**无单点登出广播**~~ | 已补:`IamLogoutBroadcastService` + `iam_client_session` + `iam_logout_broadcast` | — | ✅ P1 完成 |
| G11 | **`sub` 取登录名** | `IamUserPrincipal#getName` → `username` | 改名后 `sub` 变化,应用侧关联断裂 | P1 |
| G12 | **`permissions` 全量注入** | `IamJwtTokenCustomizer` 无按客户端裁剪 | 权限多时令牌膨胀,且越权面变大 | P1 |
| G13 | **CAS/SAML/BEARER 门户适配未实现** | 门户对非 OIDC 应用只跳入口地址 | 存量系统可由应用自己发起,但门户不代为驱动 | P2 |
| G14 | **无应用级审计** | 无 `APP_LAUNCH` 动作 | 无法回答"谁在何时进了哪个应用" | P2 |
| G15 | ~~**客户端无登出回跳地址**~~ | 已补:`post_logout_redirect_uris` 字段 + `RegisteredClient.postLogoutRedirectUris` | — | ✅ P1 完成 |
| G16 | **`client_credentials` 未真正开放** | 发现文档列出该 grant,但 `RegisteredClient` 只注册了 code + refresh | 服务间调用报 `unauthorized_client`,能力"看起来有、实际没有" | P1 |
| G17 | ~~**refresh token 不轮换**~~ | 已补:按客户端 `reuse_refresh_tokens` 开关,关闭即轮换 | — | ✅ P1 完成 |

---

## 3. 目标架构

### 3.1 分层

```
┌──────────────────────────── 体验面 ────────────────────────────┐
│  /login 主题页      /portal 用户门户        /console 管理控制台  │
│  (登录/MFA/同意)    (我的应用/最近使用)     (应用/菜单/授权/审计)│
└───────────────┬──────────────────────┬─────────────────────────┘
                │ 会话 + JSON           │ 会话 + JSON
┌───────────────▼──────────┐  ┌────────▼─────────────────────────┐
│ 协议面 authz              │  │ 控制面 admin                      │
│ authorize/token/jwks      │  │ 应用目录 CRUD、可见性授权          │
│ userinfo/revoke/logout    │  │ 菜单 CRUD、角色-菜单授予           │
│ 令牌定制、会话、MFA        │  │ 权限码、角色、审计                 │
└───────────────┬──────────┘  └────────┬─────────────────────────┘
                │                      │
        ┌───────▼──────────────────────▼───────┐
        │ 领域 + 数据面 persistence             │
        │ service 目录 / menu / role_menu /     │
        │ permission / grant / client           │
        └───────────────────┬──────────────────┘
                            │
                 ┌──────────▼──────────┐
                 │ 集成面(Phase 3)    │
                 │ Java SDK / TS SDK   │
                 │ 网关插件(可选)      │
                 └─────────────────────┘
```

### 3.2 边界原则

| 原则 | 含义 | 反例(不要做) |
|---|---|---|
| IdP 不承载业务规则 | 数据权限、审批链、字段级权限留在应用 | 在 IAM 配"报销金额 >5000 需总监审批" |
| 应用会话自治 | 应用自己维护登录态,IAM 只发令牌 | 让应用每次请求回调 IAM 校验 |
| 权限码是唯一契约 | 应用只认 `permissions` 字符串 | 应用解析 IAM 的角色层级 |
| 菜单是元数据,不是安全边界 | 隐藏菜单不等于拦截接口 | 只在前端按菜单隐藏按钮 |
| 应用 ≠ 身份域 | 一个 realm 承载多个应用 | 每个应用建一个 realm |
| 授权最终一致 | 变更在下次认证生效 | 实时推送权限到所有会话 |

**为什么"应用 ≠ 身份域"**:realm 隔离的是用户体系与密钥。同一批员工用的 OA、报销、报表必须共享用户与角色,
建三个 realm 会导致三套账号、三次登录。应用级隔离靠 `client` + `appCode` + 权限码前缀完成。

### 3.3 菜单托管的边界:不是所有业务系统的菜单都进 IAM

这是最容易做错的一步。业务系统一多(10 个应用 × 每个 80 个菜单 = 800 行),
"IAM 统一管所有菜单"会同时伤害**复杂度**与**易用性**:

| 全托管的代价 | 具体表现 |
|---|---|
| IAM 变成发布瓶颈 | 业务系统改个菜单文案、调个排序都要走 IAM 发版与审批 |
| 管理界面不可用 | 800 行菜单树,管理员找不到目标;跨团队同时编辑互相干扰 |
| 职责错位 | IAM 开始理解业务路由、页面参数、图标——这是应用的知识 |
| 双份维护 | 应用的路由表与 IAM 的菜单表必然漂移,谁也不是事实源 |
| 多环境困难 | 开发/测试/生产的菜单差异要在 IAM 里分别配一遍 |

反过来,"全部应用自管"也不行:无法回答"谁能进哪个模块",入职离职要逐个系统开通,审计没有统一口径。

**结论:按应用选择托管模式,默认只托管"一级模块"。**

| `menuMode` | IAM 管什么 | 应用管什么 | 适用 |
|---|---|---|---|
| `IAM` | 完整菜单树(含按钮) | 只渲染,不维护 | 菜单稳定、团队小的内部系统;需要统一审计 |
| `HYBRID`(默认) | 一级模块 + 权限码 | 深层页面、路由、图标、排序 | **大多数业务系统** |
| `APP` | 只发权限码,不发菜单 | 完整菜单与路由 | 存量系统、菜单与业务强耦合、频繁改版 |

> 菜单模式与授权模式相互独立:当 `authorization_mode=APP_MANAGED` 时,连 `permissions` 也可能为空,应用完全按本地 RBAC 鉴权(ADR-12)。

配套的三条硬约束:

1. **菜单只决定可见性,权限码/应用角色决定能不能做**——`IAM_MANAGED` 下服务端鉴权只认 `permissions`;`MAPPED` / `APP_MANAGED` 下应用按 `app_roles` 或本地 RBAC 鉴权(见 [ADR-12](./adr-app-scoped-authorization.md));
2. **`IAM_MANAGED` 下权限码由 IAM 统一发**;`APP_MANAGED` / `MAPPED` 的应用本地权限码不进入 IAM,通过 `app_roles` 或本地 RBAC 表达(见 [ADR-12](./adr-app-scoped-authorization.md));
3. **深层菜单不回传 IAM**:IAM 不解析业务路径,深链由应用把目标路径编码进 `state`。

### 3.4 应用角色放哪:分三层,别混在一起

角色比菜单更容易失控。企业里同时存在三种"角色",归属必须分清:

| 层级 | 例子 | 放在哪 | 理由 |
|---|---|---|---|
| L1 组织角色 | 员工、部门主管、HRBP | IAM **域角色**(`containerType=REALM`) | 跨系统通用,跟组织架构走,入职离职一次调整 |
| L2 应用角色 | OA 管理员、报销审批人、报表查看者 | IAM **应用角色**(`containerType=APP`,`appServiceId`;兼容期保留 `CLIENT`) | 需要统一开通与审计;门户按角色控制可见性 |
| L3 业务岗位/数据角色 | "XX 项目负责人"、"华东区客户经理" | **应用自管** | 数量随业务数据增长、语义只有应用懂、变更频繁 |

判断标准很简单:**"这个角色会不会影响用户能否进入某个功能?"**
会 → L1/L2,进 IAM;只是决定"看到哪些数据、走哪个流程" → L3,留在应用。

控制手段:

- 每个应用的 L2 角色控制在 **10~15 个**;超过说明建模错了,应该拆权限码或用 L3;
- 命名统一 `{appCode}:{role}`,如 `oa:approver`,避免跨应用撞名;
- 应用角色与菜单/权限一样,**在账号下次认证时生效**,需要立即生效就强制下线;
- 应用角色通过 `app_service_id` 绑定应用,不再依赖 OAuth client;一个应用多个 client 时仍能共享同一组应用角色(详见 ADR-12);
- 存量客户端角色由迁移脚本回填 `app_service_id`,兼容期内按应用角色处理。

> `MAPPED` 应用可以用「角色映射」把 IAM 用户组/域角色转换为应用自有角色码。映射保存后仅在应用授权模式为 `MAPPED` 且客户端绑定该应用时参与令牌计算,输出到 `app_roles`;重复目标角色码自动去重排序。映射可提前配置,切换模式后生效。

> 实现状态:域角色/客户端角色、用户授予、组继承均已具备;`APP` 应用角色、`app_service_id` 归属与应用作用域校验、应用管理员、`MAPPED` 角色映射、Token 按应用裁剪(`SCOPED`)、用户 GRANT/REVOKE 治理均已落地(V1.0.9–V1.1.1);应用管理员独立工作台(嵌套接口 `/admin/apps/{appId}/workspace/**` 与控制台入口)已实施,详见 [ADR-12](./adr-app-scoped-authorization.md) §17。
> L3 业务角色不在 IAM 建模——那是应用的领域模型,强行搬进来只会制造一张永远对不上的表。

---

## 4. 领域模型

### 4.1 现有关系

```
iam_realm 1 ─── n iam_client          (协议身份)
    │ 1
    └── n iam_application_service     (门户条目)
              │ sso_client_pk
              └──────────────> iam_client
iam_menu n ─── 1 iam_permission       (菜单挂权限 → 角色授予菜单即派生权限)
iam_role n ─── n iam_menu             (iam_role_menu)
iam_application_service n ─── n 主体   (iam_app_service_grant: USER / ROLE)
```

### 4.2 需要的变更

**(1) 菜单归属应用** —— 同时替换唯一索引(现为 `(realm_id, menu_code)`):

```sql
ALTER TABLE iam_menu ADD COLUMN app_service_id BIGINT;
COMMENT ON COLUMN iam_menu.app_service_id IS '所属应用服务;为空表示平台级公共菜单';

DROP INDEX IF EXISTS uk_iam_menu_code;
CREATE UNIQUE INDEX uk_iam_menu_app_code
    ON iam_menu(realm_id, app_service_id, menu_code) WHERE deleted = FALSE;
CREATE INDEX idx_iam_menu_app ON iam_menu(realm_id, app_service_id) WHERE deleted = FALSE;
```

**(2) 权限码归属应用(已实施,V1.0.9)** —— 权限码在“域 + 应用命名空间”内唯一:

```sql
DROP INDEX IF EXISTS uk_iam_permission_code;
CREATE UNIQUE INDEX uk_iam_permission_platform_code
    ON iam_permission(realm_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;
CREATE UNIQUE INDEX uk_iam_permission_app_code
    ON iam_permission(realm_id, app_service_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NOT NULL;
```

命名仍保持 `resource:action`,应用权限的 `resource` 以 `appCode:` 开头(`oa:expense`),两者互为校验:
`app_service_id` 用于归属、筛选与作用域校验,前缀用于人工可读与跨系统传递。
不同应用可以复用同一权限码;Token 收敛上线前,扁平 `permissions` 中同码权限仍会合并。

**(3) 应用会话(支撑登出与审计,P1)**:

```sql
CREATE TABLE iam_app_session (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    iam_session_id VARCHAR(128) NOT NULL,
    app_session_id VARCHAR(128),
    login_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_time TIMESTAMP,
    logout_time TIMESTAMP,
    logout_reason VARCHAR(30),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_iam_app_session_user ON iam_app_session(realm_id, user_id) WHERE deleted = FALSE;
```

### 4.3 菜单类型

保持 `MENU` / `BUTTON` 两类,不引入 `API` 类型:

- `MENU`:参与导航渲染,可以有子节点;
- `BUTTON`:不参与导航,只用于按钮级显隐与"该权限是否被授予"的判断;
- 接口级鉴权仍然只认权限码,不要在菜单表里描述 API 路径——那是应用的路由与 Controller,放进来就会腐化。

---

## 5. 功能服务设计

> **实现状态(V1.0.6 起)**:P0 已落地——菜单归属应用、菜单 CRUD、角色菜单授予、菜单下发、
> 应用授权名单读写、用户门户 `/portal`、启动接口 state/nonce 与回调白名单校验。
> 下表对已实现项标注 ✅,P1 项保留为设计。

### 5.1 应用目录与可见性授权

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/v1/admin/application-services` | `app-service:view` | 已有,分页 |
| POST/PUT/DELETE | `/api/v1/admin/application-services[/{id}]` | `app-service:create/update/delete` | 已有 |
| GET | `/api/v1/admin/application-services/{id}/grants?subjectType=ROLE` | `app-service:view` | ✅ 主体授权名单 |
| PUT | `/api/v1/admin/application-services/{id}/grants?subjectType=ROLE` | `app-service:assign` | ✅ 整体覆盖 `{subjectIds:[...]}`;USER 与 ROLE 分开维护 |

规则:

- 保存前校验主体属于同一 realm,且角色/用户未删除;
- 整体覆盖式写入(与角色权限一致),避免增量接口的并发歧义;
- 写入后清该应用的启动台缓存(P1 引入缓存时才需要);
- `visibility=PUBLIC` 时名单允许为空,切回 `ROLE` 立即生效。

### 5.2 菜单管理

| 方法 | 路径 | 权限码 | 说明 |
|---|---|---|---|
| GET | `/api/v1/admin/menus?appServiceId=` | `menu:view` | ✅ 返回该应用菜单扁平列表(含停用项) |
| POST | `/api/v1/admin/menus` | `menu:create` | ✅ 新建,校验应用归属、父节点同应用、编码应用内唯一 |
| PUT | `/api/v1/admin/menus/{id}` | `menu:update` | ✅ 改名称/路径/图标/排序/权限/启用 |
| DELETE | `/api/v1/admin/menus/{id}` | `menu:delete` | ✅ 逻辑删除并级联子节点,清理角色-菜单关联 |
| GET | `/api/v1/admin/roles/{id}/menus` | `role:view` | ✅ 已授予菜单 |
| PUT | `/api/v1/admin/roles/{id}/menus` | `role:assign-menu` | ✅ **整体覆盖**角色的菜单集合 |

约束:

1. 菜单与父节点必须同属一个 `app_service_id`;
2. 挂 `permissionId` 时,该权限必须属于同一应用(或平台公共权限);
3. 删除菜单时同步清理 `iam_role_menu`(逻辑删除),否则会留下悬空派生;
4. 角色的菜单授予与权限授予是两个维度,求值时取并集(`IamPermissionManager` 已如此),不要合并成一个接口。

### 5.3 菜单下发(用户面)

```
GET /api/v1/launchpad/apps/oa-portal/menus    ✅ 已实现
```

响应见接入文档 §8.2;实际返回 `{appCode, mode, version, menus}`,`menuMode=APP` 时 `menus` 为空。要点:

- 只返回**启用**且**角色已授予**的节点,父节点未授予时子节点不返回;
- `version` 取该应用菜单的最大 `updated_time` + 用户角色版本,供应用侧做 ETag;
- 支持 `ETag` / `If-None-Match`,未变更返回 304;
- 鉴权:已登录 + 用户对该应用可见(否则 403),不复用管理面的 `app-service:view`。

> 为什么不让应用直接读 `permissions` 拼菜单:菜单结构(层级、排序、图标)属于展示契约,
> 放在应用里就要在每个应用重复实现一遍,且与 IAM 的授权结果无法对账。托管下发是唯一能保证一致的做法。

### 5.4 门户启动

```
GET /portal/launch/{appCode}   → 302
```

启动是一次导航,不是一个返回地址的接口。目标由服务端在点击那一刻按
`LaunchPlanner` 判定,卡片与跳转共用同一份 `LaunchPlan`。

| 条件 | 目标 |
|---|---|
| 应用不存在或对当前用户不可见(含已停用) | 退回门户,提示"应用不存在或未授权" |
| 未配置入口地址 | 退回门户,提示"应用未配置入口地址" |
| 其余 | 应用入口地址 |

"可见"由可见性服务判,已包含启用状态;规划器不再重复判一次,避免两条规则各自漂移。

**一个应用只有一个地址**。任何能提供 `/sso/start` 的应用,同样能让入口页自己 302 到
授权端点;而纯静态单页应用既给不出 `/sso/start`,也照样能在入口页发起。多一个字段
只会多一个多数时候空着、空着就把卡片判成不可进入的配置项,并带来告警疲劳。

**门户不代发授权地址**。`state` 是应用的防 CSRF 随机值,`nonce` 是应用的防重放随机值,
`code_challenge` 是应用 PKCE 的承诺值,三者的验证方都是应用;门户拿不到应用的会话,
生成的值应用无法校验,强制 PKCE 时更是连生成都做不到。代发的实际结果是:应用要么跳过校验
(安全降级),要么拒绝这次回调(用户看到"点进去先报未登录,刷新一次才好")。

所以门户只负责把浏览器送到应用入口,授权请求、`state`/`nonce`/PKCE 全部由应用自己完成。

配套约束:

1. **入口地址须可跳转**:非空时必须是 `http://` 或 `https://` 开头,保存时校验;
2. **卡片不下发地址**:跳转统一走 `/portal/launch/{appCode}`,配置变更立即生效;
3. **未登录不返回 JSON**:该路径不在 `/api/v1` 下,未登录时走页面链登录跳转并回到原地址;
4. **启动审计**:`APP_LAUNCH` 记在跳转端点上,`iam_client_session` 另有"该会话登录过哪些应用"的权威记录。

深链由应用处理:门户跳入口地址,应用在入口页自行发起授权并把业务路径编码进自己生成的 `state`,
IAM 不解析、不改写业务路径。

### 5.5 登出与失效(✅ 已实现)

Spring Authorization Server **不内置** Back-Channel Logout(7.0.7 的类清单里没有任何 `*BackChannel*`),
只提供 RP-Initiated Logout(`/connect/logout`)。所以这块是自研的,分三层:

| 层 | 机制 | 实现要点 |
|---|---|---|
| 会话锚点 | ID Token 与访问令牌的 `sid` | 会话 id 的 SHA-256(Base64URL)。**没有这个声明,`/connect/logout` 会被判非法请求**——SAS 的 `OidcLogoutAuthenticationProvider` 会用 `SessionRegistry` 找到当前会话,再拿 `sid` 声明与摘要比对。访问令牌也带它,用于登出后的即时失效判定 |
| 令牌吊销 | `iam:blocklist:sid:{sidHash}` | 登出时按会话剩余寿命写入。访问令牌是无状态 JWT,只删会话不足以让它失效 |
| 回跳白名单 | `iam_client.post_logout_redirect_uris` | 映射为 `RegisteredClient.postLogoutRedirectUris`,由 SAS 校验;未登记直接 400,不跳转 |
| 单点登出广播 | `iam_client.backchannel_logout_uri` + `iam_logout_broadcast` | 登出时按 `iam_client_session` 取出"该会话登录过哪些应用",并发投递 OIDC Logout Token |

广播的关键设计:

```
用户登出(五种入口:浏览器 /logout、/logout-all、/connect/logout、管理员强制下线、
         撤回同意、SAML 单点登出)
   └─ 统一走 IamLogoutService.logout(sid)
        ├─ 先取主体与域(会话快照删了就取不到 realm 与 sub)
        ├─ 写会话吊销标记 → 该会话签出的访问令牌立即失效
        ├─ 按 sid 查 iam_client_session → 得到应用清单
        ├─ 每个应用:签发 aud 绑定的 logout_token → POST backchannel_logout_uri
        │    └─ 未登记地址 → 记 SKIPPED(这正是"应用还在线"的根因,必须可见)
        ├─ 并发投递 + 总预算 2 秒(iam.logout.broadcast-timeout-ms),超时记 FAILED
        └─ 结束本地会话(Redis 快照 + iam_user_session 状态)
```

"吊销标记"与"结束会话"必须都做:只做前者,该用户下次访问仍能凭残留快照续期;
只做后者,已签发的访问令牌在到期前照样能过验签。

为什么要有 `iam_client_session`:"该用户登出时应该通知哪些应用"这个问题,如果不落库就只能全量广播,
既打扰无关应用,也无法回答"这个会话到底登录过什么"。写入点是**授权码签发时**(浏览器在授权端点,
拿得到 sid),不是令牌交换(应用后端,拿不到浏览器会话)。

已知边界:

- 广播**不重试**:失败落 `iam_logout_broadcast`,运营在「在线会话 → 登出广播」里查,必要时人工补;
- `sid` 在应用侧只用于区分会话,不能反推会话 id(存的是摘要);
- 应用侧必须实现接收端点并校验 `iss`/`aud`/`events`,否则等于没开。

对照方案取舍(保留在此,便于后续复盘):

| 方案 | 机制 | 成本 | 结论 |
|---|---|---|---|
| A. 短令牌 + 刷新 | access token 5~15 分钟 | 低 | 仍建议作为兜底,覆盖应用未实现接收端点的阶段 |
| B. 后通道登出(已选) | 标准 OIDC Logout Token,签名 + 幂等 + 可观测 | 中 | **采用**:标准化、应用侧有现成库支持 |
| C. 在线内省 | 每请求调 `/oauth2/introspect` | 高(性能) | 仅高风险应用按需启用 |

---

## 6. 端到端流程

### 6.1 首次登录 → 门户 → 进入应用

```
1 用户访问 https://iam.example.com/  → 无会话 → /login?realm=beenest
2 登录(口令 + MFA)→ 建立 IAM 会话 → 302 /portal
3 /portal 调 GET /api/v1/launchpad/apps → 卡片列表(已按可见性过滤)
4 点"协同办公" → 浏览器 GET /portal/launch/oa-portal
5 IAM 302 https://oa.example.com
6 应用入口页发现无应用会话,生成 state/nonce/code_challenge,302 /realms/beenest/oauth2/authorize?...
7 授权端点已有会话 → 直接 302 https://oa.example.com/callback?code=..&state=..
8 应用校验 state → 后端换令牌 → 校验 id_token(nonce/iss/aud) → 建应用会话
9 进入应用首页(target 有值时进具体页)
```

关键:**第 7 步没有登录页**。这是"点一下就到"的全部秘密,不是靠共享 Cookie。

### 6.2 用户从应用退出

```
应用清本地会话 → 302 {issuer}/connect/logout?id_token_hint=..&post_logout_redirect_uri=..
→ IAM 校验 id_token_hint(aud/exp/sid)与回跳白名单
→ 结束会话 → 向该会话登录过的其它应用投递 logout_token
→ 302 回应用登出页(带 state)
```

「其它应用」的判定依据是 `iam_client_session`:该会话在授权端点登录过哪些客户端。
未登记 `backchannelLogoutUri` 的应用会记 `SKIPPED`,在「在线会话 → 登出广播」里能看到并补登记。

### 6.3 管理员强制下线

```
控制台 → 强制下线 → 与用户主动退出走同一条路径(IamLogoutService.logout)
→ 广播 logout_token → token_version+1、会话删除
→ 应用下次校验令牌:401 → 清会话 → 跳登录
→ 应用若有本地用户缓存,须 ≤ 令牌 TTL
```

---

## 7. 权限与生效模型

`IAM_MANAGED` 下的权限公式与现有实现保持一致;`APP_MANAGED` 由应用本地 RBAC 决定,`MAPPED` 使用映射后的 `app_roles`,两者都不套用该公式。

```
最终权限 = (角色-菜单派生 ∪ 角色直接 ∪ 组角色(含菜单派生) ∪ 用户 GRANT) − 用户 REVOKE
SCOPED 应用权限 = 最终权限 再按 permission.app_service_id = 当前应用 过滤
应用可见性 = PUBLIC ∪ PERMISSION(命中 requiredPermissionCode) ∪ 授权名单(USER/ROLE/GROUP)
```

> `SCOPED` 的菜单派生只接受当前应用的启用菜单;平台权限(`app_service_id IS NULL`)不进入业务应用令牌。`APP_MANAGED` 不输出 `permissions` / `app_roles`。

> **已实施(V1.0.9 第一批):** `IamEffectiveRoleResolver` 统一直授角色 ∪ 组继承角色,权限计算、菜单下发、应用可见性与 Token 角色输出现已一致;菜单派生权限增加菜单/应用启用过滤;APP 角色与绑定应用的客户端角色只能授予同应用权限/菜单。**第二批:** 应用管理员鉴权 Bean、管理员/角色映射 API 与 MAPPED `app_roles` 输出已落地。**第三批:** `SCOPED` 令牌裁剪已落地,新应用默认 `SCOPED`,存量应用保持 `LEGACY_GLOBAL` 并按应用逐个切换。**第四批(应用授权):** 应用管理员独立工作台(嵌套接口 + 控制台入口 + 跨应用越权防护)已落地。**第五批(回填):** 权限回填报告与幂等导入已落地,存量应用切 `SCOPED` 前先把平台级权限/菜单归到该应用名下,回填完成后报告清零。

> `SCOPED` 令牌只输出绑定应用的授权声明:平台或其他应用的权限码、其他客户端角色均不进入;客户端未绑定应用时只输出身份声明并记录 WARN。控制台会话仍使用域级全集。详见 [ADR-12](./adr-app-scoped-authorization.md)。

> **令牌受众即准入(ADR-13)**:绑定应用的访问令牌 `aud` 是该应用服务登记的受众标识(可登记多个,签发时全部写入),未绑定客户端保持 `client_id`;同一后端的多个入口(Web、小程序)登记同一组受众,应用侧只校验自己的标识,别的应用的令牌拿过来直接拒。**受众自动回退**:客户端未绑定应用时,若用户恰好被授权一个应用(经角色/直授/组),令牌自动使用该应用受众,避免管理员必须配置客户端绑定。

| 变更 | 生效时机 | 立即生效手段 |
|---|---|---|
| 角色权限 / 角色菜单 | 下次认证 | 强制下线 |
| 用户角色 / 组成员 | 下次认证 | 强制下线 |
| 应用可见性 | 下次拉取门户列表(实时查库);授权端点、刷新令牌与凭据登录每次实时判定 | 移除 USER 直授:事务提交后立即吊销该用户在该应用上的授权(访问令牌进黑名单)、定向登出并关闭客户端会话;移除 ROLE/GROUP 授权:已签发令牌在下次刷新时失效 |
| 应用停用 | 立即(启动时校验 `enabled`) | — |
| 应用受众配置 | 下次签发令牌(已签发的按旧受众用到过期) | 强制下线 |
| 客户端停用 | 新授权/换令牌立即失败 | 已签发令牌到期失效 |

收回只作用于"用户 × 应用"这一对关系,不结束 IAM 会话、不递增 `token_version`,因此其它应用正在使用的令牌与页面不受影响:

- 移除 USER 直授且应用为授权式可见(`visibility` 非 `PUBLIC` / `PERMISSION`)时,`ApplicationAccessRevocationService` 在事务提交后执行:先复核用户是否仍能通过角色、组或其它直授进入,仍能进入则不动;否则吊销该用户在该客户端上的授权记录(访问令牌 `jti` 进黑名单,刷新令牌因记录删除而失效),向该应用的后通道登出地址定向投递,并关闭对应的客户端会话记录。
- 移除 ROLE / GROUP 授权涉及成员全量展开,不做批量踢下线;刷新时由 `ApplicationAccessRefreshProvider` 按库中当前授权关系复核,无权即返回 `invalid_grant`。这是授权收回的最终兜底,即使主动收回失败或应用从未建立客户端会话,也无法靠刷新令牌续期。
- 未绑定客户端的应用(如 CAS / SAML)没有协议授权记录,刷新闸门不适用,以应用侧会话超时与应用自身校验为准。
- 闸门与授权端点共用 `iam.authorization.enforce-application-visibility` 开关;关闭后回到"首次授权后不再复核"的旧行为。

---

## 8. 非功能设计

| 维度 | 设计 |
|---|---|
| 缓存 | 菜单树按 `(realm,userId,appCode)` 缓存 5 分钟,key 含 `version`;`ETag` 让应用侧也缓存 |
| 令牌体积 | 单应用 `permissions` 建议 ≤200 条;`SCOPED` 按应用裁剪,超限时改用按需权限接口(ADR-12) |
| 多租户 | 所有查询带 `realm_id`;跨域读写一律拒绝,不做"超管跨域" |
| 审计 | 新增动作码:`APP_LAUNCH`、`APP_GRANT_UPDATE`、`APP_ADMIN_CREATE/DELETE`、`APP_ROLE_MAPPING_CREATE/DELETE`、`MENU_CREATE/UPDATE/DELETE`、`ROLE_MENU_ASSIGN` |
| 可观测 | 指标:`portal_apps_total`、`launch_total{result}`、`menu_api_latency`、`token_size_bytes` |
| 限流 | `/oauth2/token` 与 `/portal/launch/**` 按会话 + IP 限流,防枚举与撞库放大 |
| 兼容 | 老应用继续只读 `permissions`;菜单下发是增量能力,不强制 |

---

## 9. 演进路线

> 应用级授权模型见 [ADR-12](./adr-app-scoped-authorization.md);第一批(权限命名空间、APP 角色、作用域校验、有效角色统一)、第二批(应用管理员、MAPPED 角色映射、`app_roles`)、第三批(`SCOPED` 令牌裁剪、默认值切换)、第四批(应用管理员独立工作台)与第五批(权限回填报告与幂等导入)均已实施。存量应用仍需逐应用从 `LEGACY_GLOBAL` 切换到 `SCOPED`,切换前先跑一次回填报告确认无缺口,再用回填导入把平台级资源归到该应用名下。

### P0:补齐闭环(✅ 已完成)

1. ✅ `iam_menu.app_service_id` + `iam_application_service.menu_mode` + 唯一索引替换(V1.0.6);
2. ✅ 菜单 CRUD + 角色菜单整体覆盖 + 权限码补种(`menu:*`、`role:assign-menu`、`app-service:assign`);
3. ✅ `GET /api/v1/launchpad/apps/{appCode}/menus`(按 `menuMode` 决定是否下发);
4. ✅ 应用授权名单读写接口(USER / ROLE 分类型覆盖);
5. ✅ `/portal` 用户门户页(独立静态前缀、仅需登录、不要求管理权限);
6. ✅ 启动链路:门户 302 到应用入口地址,`state`/`nonce`/PKCE 由应用生成(见 §5.4)。

验收:

- 新建应用 → 配菜单 → 角色授予 → 用户登录门户只看到该应用与其菜单;
- 同一用户两个应用,菜单互不串;
- 门户点应用 3 秒内进入,无二次登录;
- 撤销角色后强制下线,再登录菜单消失。

### P1:治理与体验

**已完成(本轮):**

1. ✅ 客户端登出配置:`post_logout_redirect_uris` / `backchannel_logout_uri` / `reuse_refresh_tokens`(V1.0.7);
2. ✅ `iam_client_session` 真正落库(授权码签发时记录),`iam_logout_broadcast` 记录投递结果;
3. ✅ OIDC Back-Channel Logout 广播(并发 + 总预算,失败留痕不阻断退出);
4. ✅ RP-Initiated Logout 打通:ID Token 下发 `sid`,回跳白名单校验,`state` 透传;
5. ✅ 管理端 `/api/v1/admin/sessions/{sid}/broadcasts` 与控制台「登出广播」抽屉;
6. ✅ 刷新令牌按客户端轮换(默认复用,显式关闭即轮换)。

**待办:**

深链(应用把目标路径编码进 `state`)、菜单缓存与 ETag、
按客户端开启 `client_credentials`、门户最近使用/收藏(已实现 localStorage 版,待做跨端)。

> `permissions` 按客户端/应用裁剪已升级为独立设计,见 [ADR-12](./adr-app-scoped-authorization.md)。

### 存量应用切换 `SCOPED`(✅ 工具链已具备,2026-10-21)

存量应用从 `LEGACY_GLOBAL` 切到 `SCOPED` 前,必须先确认本应用依赖的权限与菜单已归到该应用名下,
否则切换后这些授权会从令牌里消失。第五批补齐了这条路径的工具:

1. ✅ `GET /api/v1/admin/apps/{appId}/backfill/report`——回填报告:列出本应用正在依赖、
   但仍属平台命名空间或他应用的权限与菜单,给出建议策略与来源;
2. ✅ `POST /api/v1/admin/apps/{appId}/backfill/import`——按主键显式选择后执行,
   `MOVE` 归属迁移(平台级且无域角色持有)或 `COPY` 复制到应用(域角色持有或已被他应用占用),幂等;
3. ✅ 控制台回填抽屉(应用门户页 + 应用工作台共用),报告清零后即可切换令牌档。

详见 [ADR-12 §18](./adr-app-scoped-authorization.md) 与 [admin-console.md §3.3](./admin-console.md)。

### P1+:主体标识(已实现,V1.0.8)

1. ✅ `iam_realm.subject_mode` / `iam_client.subject_mode`:域级默认 + 客户端覆盖(NULL 继承);
2. ✅ `IamSubjectResolver` 统一解析,ID Token / Access Token / userinfo / 后通道登出令牌同源;
3. ✅ ID Token 恒定写入 `preferred_username`:sub 变成 ID 后应用仍有一个可读的登录名;
4. ✅ 控制台:身份域页「主体标识」、客户端页「主体标识(可继承)」。

**为什么做成两级:**切 sub 是逐应用的破坏性变更。一刀切会让所有已按登录名接入的应用
集体断裂;两级配置允许新应用先走 `USER_ID`,存量应用改造完再单独切。

### P2:规模化(按需)

CAS/SAML 适配、应用自注册与审批、应用健康检查、Java/TS SDK、网关插件、广播失败自动重试与告警。

---

## 10. 决策记录

| 编号 | 决策 | 理由 | 放弃的方案 |
|---|---|---|---|
| ADR-1 | 菜单由 IAM 托管并按应用归属 | 多应用共享 realm 时唯一能保证一致的做法;权限派生已有实现 | 每个应用自管菜单(治理分散,无法对账) |
| ADR-2 | 应用可见性用独立授权表 | 可见性是"能否看到入口",与角色语义解耦;支持按人特批 | 用 `appCode:access` 权限码 + 角色间接表达(绕且难查) |
| ADR-3 | 门户是 IAM 的用户面,不是独立产品 | 复用会话与可见性数据,零额外登录 | 单独做一个应用市场系统(多一次登录、多一套权限) |
| ADR-4 | 不为每个应用建 realm | 用户与角色必须共享;realm 是租户边界不是应用边界 | per-app realm(账号割裂) |
| ADR-5 | 授权变更最终一致 | 会话持登录快照,实时推送成本高、易不一致 | 权限变更实时广播到所有会话 |
| ADR-6 | 菜单只描述展示,不描述 API | 避免菜单表腐化成第二套路由表 | 菜单里挂 API 路径做接口鉴权 |
| ADR-7 | 登出用标准 Back-Channel Logout 而非自定义 webhook | 应用侧有现成库;令牌可验签、可审计 | 自定义 `logout_webhook` JSON(每家应用都要自己解析) |
| ADR-8 | 广播同步等待但有总预算(2 秒) | 用户退出必须"立刻确定",不能后台漂着;预算内并发投递足够覆盖内网应用 | 纯异步队列(需要额外中间件,且退出后结果不可见) |
| ADR-9 | 刷新令牌默认复用,轮换按客户端开关 | 轮换会让并发刷新互相踢掉;存量系统切换成本高 | 全局强制轮换 |
| ADR-10 | `sub` 默认登录名,可逐应用切到不可变用户主键 | 一刀切会让存量应用集体断裂;两级配置让新应用先走 ID、老应用改造完再切 | 全局强制 `USER_ID` / 只发登录名不管不变性 |
| ADR-11 | sub 之外恒定注入 `preferred_username` | sub 变 ID 后应用需要一个标准的可读展示名,否则每家都要回查 | 让应用自己查 `uid` 反查登录名 |
| ADR-12 | 权限按应用命名空间;应用角色绑定 `app_service_id`;老系统用 `APP_MANAGED` / `MAPPED`;Token 按应用裁剪 | 多应用必须允许权限码重复;老系统不能被强制全量纳管 | 继续域内全局权限码 / 强制所有应用迁管 RBAC |
| ADR-13 | 访问令牌的 `aud` 指向应用服务登记的「令牌受众」(可多个,留空按 `urn:iam:app:{app_code}` 派生),ID Token 与后通道登出令牌仍用 `client_id` | 受众是"令牌交给哪个后端"的标准表达,应用侧可直接用 Spring Security 的 `JwtAudienceValidator`;受众由 IAM 签发,收回应用授权即令令牌受众落回 `client_id`,应用侧无需改配置重新部署 | 用自定义 `token_kind` 声明让每家应用自己写判定 / 用客户端 scope 表达应用归属(scope 是协议层范围,语义不匹配且改动客户端就要动令牌) |
| ADR-14 | 门户只做导航,不代发授权地址;跳转目标就是应用入口地址(`entry_url`) | `state`/`nonce`/`code_challenge` 的验证方都是应用,门户生成的值应用无法校验,强制 PKCE 时更无从生成。把发起时机交回应用,协议校验才完整,门户也不必理解每家应用的协议细节 | 门户拼 `/authorize`(应用只能跳过校验或拒绝回调,表现为首屏未登录后刷新才好) / 额外登记「单点发起端点」(能给 `/sso/start` 的应用同样能让入口页自己 302,而纯静态单页应用两边都给不出,该字段只对"能却不做"的应用有意义,代价是多数时候空着、空着就把卡片判成不可进入) / 一次性票据换票(与授权码职责重叠,多一套凭据生命周期与交换端点) |

---

## 11. 与业界对照

| 能力 | Keycloak | Okta | 本平台 |
|---|---|---|---|
| 应用目录 | Client + 自建 account console | App catalog | `iam_application_service` ✅ |
| 应用启动台 | Account Console 内置 | End-User Dashboard | `/portal` ✅ |
| 应用级角色 | Client Role(挂在 client) | App role | `APP` + `appServiceId` ✅(兼容 `CLIENT`) |
| 菜单托管 | 无(靠 client scope/roles) | 无 | 三档 `menuMode`(IAM/HYBRID/APP)✅ |
| 单点登出 | Back-Channel Logout | 支持 | 自研 Back-Channel Logout ✅ |
| 主体标识 | `sub` = 用户 UUID(不可变) | `sub` = 用户 ID | 可配置:登录名(兼容)/ 用户主键,客户端可覆盖 ✅ |
| 多租户 | Realm | Org | Realm ✅ |

可以看出:**"应用有自己的菜单权限"在 Keycloak/Okta 里也不托管菜单**,只托管角色。
本平台把菜单纳入 IAM 是更进一步的企业内网选择(内网应用多、菜单变更不频繁、需要统一审计),
代价是菜单模型必须带应用归属——这正是 G1 必须修的原因。

---

## 12. 一句话回答"IAM 是否这样设计"

**是,方向正确;到现在为止,协议面与治理面都已闭环。**

- 协议面(怎么登录、怎么发令牌、怎么登出):企业级,OIDC + PKCE + RP-Initiated Logout + Back-Channel Logout;
- 治理面(应用怎么被看见、菜单怎么被授权、登出怎么传导):P0 六项与 P1 四项已实现并端到端验证;
- 当前形态即可支撑"一个用户、多个应用、一次登录、点哪进哪、权限各管各的、一处退出处处下线"。

仍未做的主要是"规模化"课题(`docs/app-access-architecture.md` §9 P1 待办与 P2):深链、菜单缓存、
权限回填与导入、CAS/SAML 适配、SDK 与广播失败重试告警。

其中应用级权限命名空间、应用角色、应用管理员、角色映射、`SCOPED` Token 收敛、用户权限特批与
应用管理员独立工作台均已形成 [ADR-12](./adr-app-scoped-authorization.md) 并完成实施(V1.0.9–V1.1.1);
存量应用的权限回填仍待后续批次。

接入方按 [`client-integration.md`](./client-integration.md) 实施即可,其中 §7.4 的**应用侧登出接收端点**
是唯一必须在应用里写的代码,其余都是配置。
