# 管理控制台设计说明

管理控制台是 IAM 平台的管理面(Control Plane),面向域管理员完成账号、授权、应用接入与安全治理。
本文说明它的分层、权限模型与扩展方式,便于二次开发与评审。

## 1. 分层与依赖

```
浏览器
  │  GET /console/**         (页面外壳,Thymeleaf 渲染,内嵌 CSRF;子路径回退到 SPA)
  │  GET /console-static/**  (JS/CSS,静态资源处理器)
  ▼
beenest-iam-web      ConsolePageController + console/index.html + console/static/*
  │  fetch /api/v1/auth/me、/api/v1/admin/**
  ▼
beenest-iam-admin    REST 控制器(@PreAuthorize)+ Service + DTO/Converter
  ▼
beenest-iam-persistence / authz   MyBatis Mapper、策略、令牌与会话能力
```

- 控制台**不持有任何业务规则**:所有校验、域隔离、审计都在服务端;前端的菜单与按钮过滤只是体验层。
- 控制台与登录主题(`classpath:/themes/{active}/`)**互不耦合**:主题换肤不影响控制台,
  控制台升级也不影响登录/同意页。两者通过独立的模板解析器与静态资源前缀共存
  (`console/index` 与 `login/login` 分别解析)。
- 未认证分流由 `IamPageAwareAuthenticationEntryPoint` 完成:`/api/**` 返回 401 JSON,
  其余路径 302 到 `/login?return_to=...`,避免浏览器直接访问页面时拿到空 401。

## 2. 页面结构

Vue 3 单页应用,History 路由(`/console/users` 等无 `#`),路由级懒加载;页面配置由权限码驱动:

| 分组 | 页面 | 主要能力 |
|---|---|---|
| 概览 | 运营概览 | 账号/会话/客户端/用户组存量、今日认证态势、最近登录事件、常用入口 |
| 身份与访问 | 用户 | 开户、编辑、角色授予、**组织归属(部门/岗位/主部门)**、重置口令、锁定/解锁、强制下线、批量启停、详情(账号概览 / **权限治理:有效权限诊断 + GRANT/REVOKE 特批**,有效权限可看出岗位来源) |
| | 组织架构 | 部门与用户组共用一棵树:类型(部门/组)、负责人、排序;成员按**任职**编辑(岗位 + 主部门),支持含下级成员只读视图;组角色(组继承授权) |
| | 岗位 | 岗位字典 CRUD(编码域内唯一、职级、排序)与**岗位角色**分配;岗位角色经"任职"进入权限公式,详见 `docs/organization-position.md` |
| | 角色 | 域角色/应用角色/客户端角色 CRUD、按应用筛选、角色权限与**角色菜单**整体覆盖式授予;应用角色只能授予所属应用的权限/菜单 |
| | 权限 | 权限清单与增删改(平台权限或应用权限)、按应用/资源筛选;删除前检查引用 |
| 应用与协议 | 客户端 | 登记、回调地址、作用域、PKCE/同意开关、TTL、密钥轮换、**变更类型(机密/公开)**、作用域分配、跳转协议映射、**登出配置(回跳白名单 / 后通道登出地址 / 刷新令牌是否轮换)**、**主体标识 sub(可继承身份域)** |
| | 客户端作用域 | 作用域 CRUD(含默认作用域) |
| | 协议映射 | claim 映射 CRUD(按客户端筛选) |
| | 身份提供方 | IdP 接入、连接配置(只写不读)、属性映射;类型由扩展包自报(OIDC / 微信 / SAML 2.0),表单按声明动态渲染,接入步骤见 `docs/saml-integration.md` |
| | 应用门户 | 启动台应用条目、可见性、单点接入方式、**授权模式(IAM 托管/角色映射/应用自管)**、**令牌档(SCOPED / LEGACY_GLOBAL)**、**应用授权名单(角色/用户)**、**应用管理员(用户/用户组)**、**角色映射(组/域角色 → 应用角色码)** |
| | 应用菜单 | 按应用维护菜单与按钮、挂载权限(只显示同应用权限);角色页「菜单」完成授予 |
| 应用工作台 | 应用管理 | **应用管理员的独立交付台**:概览(存量与接入档案)、菜单树、应用角色、应用权限、角色映射、管理员名单(只读);全部走 `/api/v1/admin/apps/{appId}/workspace/**` 嵌套接口,详见 §3.2 |
| 治理与策略 | 身份域 | 域 CRUD、TTL/会话策略、**主体标识 sub 默认策略**、签名密钥列表与轮换、切换域登录 |
| | 安全策略 | 口令策略、登录策略(锁定/验证码/强制 MFA) |
| | 扩展属性 | 属性定义 CRUD(类型、约束、可检索) |
| 运行监控 | 在线会话 | 会话检索、单会话强制下线、**登出广播投递记录**(逐应用 HTTP 状态与失败原因) |
| | 令牌 | 按账号检索授权、单条吊销、按账号全部吊销 |
| | 授权同意 | 同意记录查询与撤销 |
| | 登录事件 | 认证轨迹检索、按 IP 溯源 |
| | 审计日志 | 管理操作检索(动作/操作人/时间) |

## 3. 权限模型

- 菜单项声明 `perm`(如 `user:view`),`/api/v1/auth/me` 返回的 `authorities` 决定可见性;
  行内操作再声明各自权限码,无权限的按钮不渲染。
- 页面内所有写操作仍由服务端 `@PreAuthorize("hasAuthority('...')")` 兜底:
  绕过前端直接调用接口会被 403 拒绝,并返回统一 `Result`。
- 权限变更的生效时机:
  - 角色权限、用户角色、组角色的调整 → 账号**下一次认证**时按权限公式重算;
  - 需要立即生效 → 在「用户」页对目标账号执行**强制下线**(令牌版本自增 + 会话终结)。
- 数据范围由**登录账号所属身份域**决定,不由请求参数决定;「身份域」页的
  “以此域登录”会退出当前会话并以目标域重新登录(避免出现“看着是 A 域、写的是 B 域”).
- 应用作用域接口由 `@appAuthz.canManage(appId)` 做第二道边界:全局 `app-service:update`
  或应用管理员名单(用户直授 / 用户组继承)均可管理该应用;管理员名单本身只允许全局管理员
  通过 `app-service:assign` 修改。`/api/v1/admin/apps/managed` 返回当前账号可管理的应用集合
  (判定口径与 `canManage` 一致,避免"看得到入口却 403")。
- 应用管理员导航按"是否至少管理一个应用"裁剪:`navigation.ts` 的 `requiresManagedApp` 标记,
  `canAccessNav` 同时校验权限码与可管理应用;路由守卫与命令面板复用同一判定,
  因此应用管理员登录后控制台只出现「应用工作台」一个入口。
- 用户权限治理的读取(诊断、特批列表、候选权限)接受 `user:view` **或** `user:grant-permission`;
  写入特批只接受 `user:grant-permission`(初始化脚本默认授予 `SUPER_ADMIN`)。

### 3.1 用户权限治理(特批)

求值口径:`(角色直授 ∪ 菜单派生 ∪ 未过期 GRANT) − 未过期 REVOKE`,角色部分包含组继承角色。
控制台诊断、令牌 `permissions` 声明共用 `IamPermissionManager.inspect`,避免"能看到的权限"与"实际签发权限"漂移。

| 接口 | 权限码 | 说明 |
|---|---|---|
| `GET /api/v1/admin/users/{userId}/effective-permissions?appServiceId=` | `user:view` 或 `user:grant-permission` | 有效权限及来源明细(角色直授 / 菜单派生 / GRANT / REVOKE),可按应用命名空间过滤 |
| `GET /api/v1/admin/users/{userId}/permission-overrides` | 同上 | 特批记录(含已过期) |
| `GET /api/v1/admin/users/{userId}/permission-scopes` | 同上 | 当前身份域可选的权限范围(平台 + 应用) |
| `GET /api/v1/admin/users/{userId}/permission-overrides/candidates` | 同上 | 可授予权限分页,支持关键词与应用范围筛选 |
| `PUT /api/v1/admin/users/{userId}/permission-overrides/{permissionId}` | `user:grant-permission` | 新建或覆盖 GRANT/REVOKE,记录理由、到期时间与操作人 |
| `DELETE /api/v1/admin/users/{userId}/permission-overrides/{permissionId}` | `user:grant-permission` | 逻辑删除特批,账号回归角色计算结果 |

- 一条记录 = 用户 × 权限;GRANT 与 REVOKE 互斥,重复设置覆盖原记录,历史通过审计日志
  (`USER_PERMISSION_OVERRIDE_SET` / `USER_PERMISSION_OVERRIDE_DELETE`)追溯。
- 到期时间可空(永久);已过期记录保留但不再参与求值。应用范围视图只接受该应用的权限,
  平台权限(`app_service_id IS NULL`)不会混入业务应用令牌。
- 不允许修改自己的权限特批,避免自助提权或规避审批;目标用户与权限必须属于当前身份域,
  跨域主键按不存在处理。
- 写入后清除该账号的权限缓存;对已登录账号,变化在下一次认证/令牌签发时体现,需要立即生效
  仍使用「用户 → 强制下线」。

### 3.2 应用管理员工作台

应用管理员**不需要任何全局角色**:只要在 `iam_app_admin` 中是某应用的管理员(用户直授或用户组继承),
就能登录控制台并只在该应用范围内工作。入口 `/console/workspace`,页面用 `?app=&tab=` 记录当前应用与页签。

| 接口 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/v1/admin/apps/{appId}/workspace` | `@appAuthz.canManage` | 概览:应用档案 + 菜单/角色/权限/映射/管理员存量 |
| `GET/POST/PUT/DELETE /api/v1/admin/apps/{appId}/workspace/menus[/{menuId}]` | `@appAuthz.canManage` | 应用菜单树 CRUD |
| `GET/POST/PUT/DELETE /api/v1/admin/apps/{appId}/workspace/roles[/{roleId}]` | `@appAuthz.canManage` | 应用角色 CRUD(强制 `containerType=APP`) |
| `GET/PUT /api/v1/admin/apps/{appId}/workspace/roles/{roleId}/permissions` | `@appAuthz.canManage` | 角色直接权限,整体覆盖 |
| `GET/PUT /api/v1/admin/apps/{appId}/workspace/roles/{roleId}/menus` | `@appAuthz.canManage` | 角色菜单授予,整体覆盖 |
| `GET/POST/PUT/DELETE /api/v1/admin/apps/{appId}/workspace/permissions[/{permissionId}]` | `@appAuthz.canManage` | 应用权限命名空间 CRUD |
| `GET /api/v1/admin/apps/{appId}/workspace/mapping-sources?sourceType=&keyword=` | `@appAuthz.canManage` | 映射来源检索(`GROUP` / `REALM_ROLE`) |
| `GET /api/v1/admin/apps/{appId}/admins` | `app-service:view` 或 `canManage` | 管理员名单(应用管理员**只读**) |
| `GET/POST /api/v1/admin/apps/{appId}/role-mappings[/{mappingId}]` | `app-service:update` 或 `canManage` | 组/域角色 → 应用角色码映射 |

作用域规则(服务端强制,前端只做提示):

- **路径中的应用主键是唯一授权边界**:所有资源主键都会二次校验归属,
  跨应用或跨域的主键按"不存在"处理(404 语义),不会泄露他应用资源是否存在;
- **创建时强制收敛作用域**:菜单与权限写入路径上的应用主键,角色强制为 `APP` 容器并绑定该应用;
  请求体里带的应用主键只作提示,不会被采纳;
- **授予只接受同应用资源**:把平台权限或他应用权限授予应用角色被拒绝(10001),
  引用中的权限不可删除(10004);
- **管理员名单不可自扩**:名单写操作只接受 `app-service:assign`,应用管理员增删自己一律 403;
  接入参数、可见性、令牌档同样由全局管理员维护。

审计:`MENU_CREATE/UPDATE/DELETE`、`ROLE_CREATE/UPDATE/DELETE`、`ROLE_ASSIGN_PERMISSION/MENU`、
`PERMISSION_CREATE/UPDATE/DELETE` 等动作落 `iam_audit_log`,`target_id` 记录被改动的资源主键
(不是作用域里的应用主键),便于事后定位"谁改了哪个菜单"。

### 3.3 权限回填报告与导入

存量应用切到 `SCOPED` 令牌档之前,必须先把"本应用正在使用、但仍挂在平台命名空间"的权限与菜单
归到该应用名下,否则切换后这些授权会从令牌里消失。回填抽屉就是这件事的操作面:
应用门户页(全局管理员)与应用工作台(应用管理员)共用同一个 `AppBackfillDrawer`。

| 接口 | 鉴权 | 说明 |
|---|---|---|
| `GET /api/v1/admin/apps/{appId}/backfill/report` | `@appAuthz.canManage` | 回填报告:待回填的权限/菜单候选、建议策略、来源与共用情况 |
| `POST /api/v1/admin/apps/{appId}/backfill/import` | `@appAuthz.canManage` **且** `app-service:backfill` | 按主键显式选择资源执行回填,幂等 |

报告口径:

- 候选 = 本应用(应用角色直授 / 菜单派生)正在依赖、但 `app_service_id` 还不是本应用或为空(平台级)的权限与菜单;
- 应用自有的菜单、已经回填完成(应用下已有同码副本且持有平台原件的角色都持有副本)的资源**不再出现**,
  所以回填后刷新报告应当清零;
- 每个候选带 `strategy` 建议:`MOVE`(归属迁移)或 `COPY`(复制到应用),
  并给出 `reasons` / 应用侧角色 / 域角色 / 共用应用清单,便于人工判断。

两种策略的差别:

| 策略 | 动作 | 适用 | 副作用 |
|---|---|---|---|
| `MOVE` 归属迁移 | 直接改写 `app_service_id` | 平台级资源,且**没有域角色持有** | 平台原件进入本应用命名空间,不再对其它应用可见 |
| `COPY` 复制到应用 | 应用下建同码副本,授权引用改指向副本,域角色追加副本引用 | 资源已被域角色持有、或已被其它应用占用 | 平台原件保持不变,其它应用的授权引用不受影响 |

关键约束:

- **域角色持有的平台资源只能复制**:域角色(如 `SUPER_ADMIN`)对全域生效,一旦把平台资源迁进单个应用,
  其它应用的 `SCOPED` 令牌就再也拿不到它;这类资源执行 `MOVE` 会被拒绝并给出冲突说明;
- **导入只处理显式选择的主键**,不会顺手搬走整个平台的资源;空选择等于不动作;
- **幂等**:同一批主键重复导入不产生新行,计数不变;
- 导入有审计(`APP_BACKFILL_IMPORT`),`target_type=APP`,`target_id` 为应用主键;
- 报告对应用管理员开放只读,导入需要全局权限 `app-service:backfill`(V1.1.2 迁移补种并授予 `SUPER_ADMIN`),
  没有该权限时控制台只展示报告并提示所需权限码。

## 4. 交互与容错约定

| 场景 | 处理 |
|---|---|
| 接口 401 | 跳转 `/login?return_to=<当前页>`,登录后原路返回 |
| 接口 403/400/409 | 统一 toast 展示 `Result.message`;表单类错误就地显示在抽屉内 |
| 破坏性操作 | `confirmDialog` 二次确认,文案说明影响范围(如“旧密钥立即失效”) |
| 敏感值 | 客户端密钥仅在轮换成功后一次性展示并提供复制;IdP 配置只写不读 |
| 长任务 | 按钮进入 busy 态并禁用,避免重复提交 |
| 空/异常 | 表格空态、抽屉骨架屏、页面级错误块(含服务端 message) |

## 5. 前端工程约定

技术栈:**Vue 3.5 + TypeScript 5.9 + Vue Router 4(History)+ Vite 7**,不引入 UI 组件库与状态库,
视觉与交互全部由本项目组件与 CSS 设计令牌实现。源码位于 `beenest-iam-web/frontend`,构建产物
输出到 `beenest-iam-web/src/main/resources/console/static`(随 Maven 打进 jar,生产环境无需 Node)。

```
frontend/src/
├── main.ts        引导:先取 /api/v1/auth/me 与 /api/v1/admin/apps/managed 再挂载,失败时渲染页面级错误块
├── navigation.ts  导航项(路径/图标/权限码/是否需要可管理应用),canAccessNav 与首可达路由
├── router.ts      路由由 navigation.ts 生成;无权限访问统一落到首个可访问页或 /forbidden
├── components/    AppShell、ResourcePage、DataTable、SchemaForm、BaseModal、
│                  BaseDrawer、PagerBar、AssignmentModal、ConfirmHost、ToastHost…
├── views/         每个管理域一个视图(CRUD 域用 ResourceConfig 声明字段/列/行操作),
│                  AppWorkspaceView 为应用作用域工作台(独立实现,含页签与作用域提示)
├── lib/           api(CSRF/JSON/401 回跳/统一解包/silent)、toast、confirm、
│                  ui(单元格渲染)、format(safeHttpUrl/时间)、types
└── styles/        console.css:设计令牌、蜂巢纹理、布局与组件样式
```

构建命令:

```bash
cd beenest-iam-web/frontend
npm ci
npm run typecheck   # vue-tsc --noEmit
npm run build       # 输出 app.js、console.css 与带 hash 的 chunks/*
```

新增一个管理域的步骤:

1. 服务端补 REST 接口与权限码(迁移脚本 + `SUPER_ADMIN` 授予);
2. 在 `navigation.ts` 增加导航项(路径、图标、权限码)并在 `router.ts` 注册懒加载路由;
3. 简单 CRUD 在 `views/*.vue` 声明 `ResourceConfig`(字段、列、过滤、行操作);
   有特殊交互(密钥轮换、权限矩阵、按账号查询)时写自定义视图或弹窗;
4. 重新 `npm run build`,再按需打包服务端。

### 5.1 运行与安全约定

- 所有写请求由 `lib/api.ts` 统一设置 `Content-Type: application/json`,并回传
  `<meta name="_csrf">` 中的令牌;401 统一 `location.replace('/login?return_to=…')`。
- `toastState` / `confirmState` 是 `reactive` 模块级状态,宿主组件通过 computed 订阅;
  二次确认与提示在路由切换后仍然可用。
- 表格中的用户数据一律以文本节点渲染(禁止 `v-html`);外链经 `safeHttpUrl` 校验协议,
  仅允许 http/https。
- 客户端密钥只在创建/轮换成功后一次性展示,详情与列表不返回密钥材料;IdP 连接配置只写不读。
- 前端权限过滤只是体验层,真正的边界仍是服务端 `@PreAuthorize`。

### 5.2 视觉与组件规范

主题为「明亮蜂巢」:象牙底色、墨蓝文字、蜂蜜金点缀,蜂巢六边形纹理只作低对比背景;
大面积高饱和金色仅用于品牌与关键动作,工作区保持低饱和以突出数据。

| 层级 | 类名 | 用途 | 外观 |
|---|---|---|---|
| 主操作 | `.btn-primary` | 一屏一个的创建/保存动作 | 墨色实心 `#131a2a`,金图标,内高光 + 极轻投影 |
| 次操作 | `.btn` | 重置、取消、批量动作 | 白底 + `--line-strong` 描边,悬停加深底色 |
| 强调次操作 | `.btn-accent` | 筛选区「查询」 | 白底金边,金色只落在图标,悬停泛浅金 |
| 轻操作 | `.btn-ghost` | 抽屉/弹窗的取消 | 无边框,悬停浅灰底 |
| 危险(实心) | `.btn-danger` | 确认弹窗的最终确认 | 红实心 |
| 危险(软) | `.btn-danger-soft` | 工具条里的高危批量动作 | 浅红底 + 红字 |

- 尺寸:标准控件 34px(`--control-h`)、紧凑 28px(`--control-h-sm`),圆角 8/7;输入框与按钮同高,
  保证筛选条基线对齐。
- 表格操作列:`position: sticky` 固定右侧,列多横向滚动时操作不被裁切;行内动作超过 3 个时,
  前 2 个直出、其余收进「⋯」菜单(危险项前有分隔线),菜单用 fixed 定位避免被滚动容器裁剪。
- 表格排版:表头 11.5px + 0.03em 字距,单元格 12px 纵向留白,主字段/编码列不换行。
- 焦点可访问性:键盘聚焦时 2px 金色 outline(`:focus-visible`),鼠标点击不出现。
- 统计卡不加装饰性色块,靠字号与留白建立层级。

## 6. 已知边界

- 角色权限授予为“直接权限”维度;菜单派生权限由角色页的「菜单」抽屉产生,两者求值取并集。
- 应用角色(以及已绑定应用的客户端角色)的权限/菜单授予被服务端限制在所属应用内;域角色仍可跨应用授权。
- 应用级权限码允许跨应用重名;`SCOPED` 令牌只含绑定应用的权限、当前客户端角色与本应用角色。**新应用默认 `SCOPED`**,存量应用保持 `LEGACY_GLOBAL`,由应用页逐应用切换;切换只影响新签发令牌,已签发令牌在过期/刷新前不变。客户端未绑定应用时只输出身份声明(默认拒绝),接入前先完成绑定。
- 切换 `SCOPED` 前确认应用依赖的权限码已归属本应用、角色菜单已迁移;需要回滚时把令牌档改回 `LEGACY_GLOBAL`。
- `APP_MANAGED` 在 `SCOPED` 下不输出 `permissions` / `app_roles`,鉴权完全由应用本地负责;`MAPPED` 的 `app_roles` 不受令牌档影响。
- 应用管理员名单与角色映射接口、控制台入口已提供;应用管理员独立导航与工作台已落地(见 §3.2)。
- **客户端类型不在通用更新里改**:类型决定令牌端点的认证方式(密钥凭证还是 `none`),
  只能走「变更类型」(`POST /api/v1/admin/clients/{id}/type`),且仅对**从未产生客户端会话、
  无授权同意**的客户端开放——否则已签发的授权会跨类型继续有效,机密性取决于配置历史。
  降级为公开客户端会清空密钥并强制 PKCE,与 `client_credentials` 冲突时直接拒绝;
  升级为机密客户端现场签发新密钥,明文只在响应中返回一次。已投入使用的客户端请新建并迁移。
- **角色归属只在"干净"时可改**:容器类型与所属应用/客户端决定授权面(有效角色解析不按容器过滤),
  因此只有未被用户/组/岗位分配、未授予权限与菜单、无应用授权的角色允许变更,且需通过目标作用域下
  的角色编码唯一性校验;已承载授权关系的角色会拒绝并给出条数,需新建目标归属的角色再迁移授权。
- **删除客户端前先解除引用**:授权同意、在线会话、客户端角色、绑定该客户端的应用服务都会阻塞删除,
  错误信息按类别给出条数与解除入口;客户端自身的**作用域分配不算引用**,删除时随之清理。
- 工作台的边界:创建的资源强制收敛到当前应用(菜单/权限挂当前应用、角色固定为 APP 容器),
  跨应用或跨域主键按“不存在”处理;管理员名单在工作台只读,扩权须由全局管理员在「应用门户」操作。
- 存量应用仍需逐个从 `LEGACY_GLOBAL` 切到 `SCOPED`,切换前需要权限回填报告与幂等导入接口。
- `MAPPED` 应用的角色映射可提前配置,但只有授权模式为 MAPPED 且客户端绑定该应用时才写入令牌 `app_roles`;映射变更下次认证生效,需要立即生效请强制下线。
- 控制台的域切换是“重新登录”,不支持同一会话内跨域读写(与服务端的域隔离策略一致)。
- 用户特批按单条权限维护,不提供"按应用一键授权";删除为逻辑删除,已过期记录保留用于审计。
  给自己授权、跨域用户/权限都会被服务端拒绝。
- 「强制下线」与用户自助登出走**同一个登出入口**(`IamLogoutService`):先向该会话登录过的所有客户端广播
  `logout_token`,再终结本地会话。广播失败/超时**不阻断**下线(退出优先),结果记为 `FAILED`;
  未配置后通道登出地址的应用记为 `SKIPPED`——两种情形都可在「会话 → 登出广播」中查到,
  用于向业务方追溯“为什么用户退了、应用还在线”。
- 广播当前只留痕、不自动重试,也不外发告警;需要兜底时按 `iam_logout_broadcast` 的 `FAILED` 记录做补偿。
- 「主体标识 sub」是**破坏性配置**:从登录名切到用户 ID 后,尚未改造的应用会认不出人。
  因此域级默认值先保持登录名,配合客户端的「继承身份域」逐应用切换;切换前确认应用侧用的是
  `sub` 还是 `preferred_username`,切换后按 `preferred_username` 做一次映射回填。
