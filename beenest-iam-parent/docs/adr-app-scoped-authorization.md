# ADR-12:应用级授权模型(权限命名空间、应用角色与老系统接入)

- 状态:**已接受,五批实施完成(2026-10-21)**
- 日期:2026-10-21
- 关联:[app-access-architecture.md](./app-access-architecture.md)、[client-integration.md](./client-integration.md)、[admin-console.md](./admin-console.md)
- 结论:**保留 realm / application / client 分层,不重写框架;在模型层做一次定向重构。**

---

## 1. 背景

当前模型在“一个域、少量应用”下可以工作,但接入应用数量上升后,会出现四类结构性问题:

1. 权限码在域内全局唯一,不同应用无法使用相同权限码;
2. 所有 OIDC 客户端都会收到用户的全部权限和全部客户端角色;
3. 应用角色绑定的是 OAuth Client,不是应用服务;
4. 没有应用管理员边界,也没有让老系统继续自管 RBAC 的正式模式。

这些问题不是 UI 或单个接口的问题,继续用补丁方式处理会让后续迁移成本持续上升。因此需要一次**应用级授权模型**的定向重构。

### 1.1 现状证据

| 问题 | 现状 | 影响 |
|---|---|---|
| 权限命名空间 | `uk_iam_permission_code` 仍是 `(realm_id, permission_code)`;`app_service_id` 只加了列和普通索引 | 多应用无法复用 `user:view`、`admin` 等编码 |
| 权限管理 | 只有 GET 接口,`PermissionResponse` 无 `appServiceId` | 应用无法注册、筛选、维护自己的权限目录 |
| Token 收敛 | `IamJwtTokenCustomizer` 写入全部 `permissions` 和压平的 `client_roles` | 越权暴露、角色撞名、JWT 膨胀 |
| 角色归属 | `iam_role.container_type=CLIENT` + `client_id`,没有 `app_service_id` | 一个应用多个 client、非 OIDC 应用都无法表达应用角色 |
| 应用管理员 | 所有管理接口都要求全局管理权限 | 应用管理员无法只管理自己的应用 |
| 老系统接入 | `menu_mode=APP` 只是不发菜单 | 没有 `APP_MANAGED` / 角色映射 / 外部权限同步契约 |
| 一致性 | 角色授权只校验 realm,菜单只校验 realm;组角色在菜单/应用可见性中丢失 | 跨应用误授权;用户有权限却看不到菜单/应用 |

---

## 2. 决策摘要

| 编号 | 决策 |
|---|---|
| D1 | 新增 `authorization_mode`,取值为 `IAM_MANAGED` / `MAPPED` / `APP_MANAGED` |
| D2 | 权限唯一性改为“应用内唯一”,平台权限用 `app_service_id IS NULL` 单独约束 |
| D3 | 角色增加 `app_service_id`,新增 `APP` 容器类型;`CLIENT` 作为兼容类型保留 |
| D4 | Token 按当前 client/app 裁剪;新增 `token_profile=SCOPED/LEGACY_GLOBAL` 做灰度 |
| D5 | 新增应用管理员关系,管理接口按 `app_service_id` 做作用域校验 |
| D6 | `APP_MANAGED` 不导入老系统细粒度权限;`MAPPED` 用映射表输出 `app_roles` |
| D7 | 统一“有效角色解析”,直授角色 + 组继承角色在所有路径一致 |
| D8 | 菜单派生权限补启用状态过滤;长期将“展示授权”和“API 授权”解耦 |

**不在本次范围:** ABAC/策略引擎、SCIM、SAML/CAS 适配、per-app realm、应用自注册、L3 业务岗位角色建模、权限变更实时推送。

---

## 3. D1:应用授权模式

新增字段:

```sql
ALTER TABLE iam_application_service
    ADD COLUMN authorization_mode VARCHAR(20) NOT NULL DEFAULT 'IAM_MANAGED';
```

| 模式 | IAM 负责 | 应用负责 | 适用 |
|---|---|---|---|
| `IAM_MANAGED` | 身份、应用准入、应用角色、权限目录、菜单 | 消费 `permissions` / `app_roles` | 新应用、标准接入 |
| `MAPPED` | 身份、应用准入、粗粒度角色/组 → 应用角色映射 | 应用角色后的细粒度权限、本地菜单 | 老系统渐进迁移 |
| `APP_MANAGED` | 身份、应用准入 | 全部角色、权限、菜单 | 老系统、强自定义 RBAC |

约束:

- `APP_MANAGED` 下 IAM **不要求**导入应用的细粒度权限;
- `MAPPED` 下 IAM 只输出映射后的 `app_roles`,不要求导入应用权限;
- `IAM_MANAGED` 才由 IAM 托管权限目录和菜单;
- `menu_mode` 继续独立存在。推荐组合:`IAM_MANAGED → IAM/HYBRID/APP`,`MAPPED/APP_MANAGED → APP`,但不做硬限制。
- `APP_MANAGED` 的应用可见性使用 `PUBLIC` 或授权名单(USER/ROLE/GROUP),不使用 `PERMISSION`;`MAPPED` 仅在导入权限目录后使用 `PERMISSION`。
- 存量应用迁移期统一保留 `IAM_MANAGED + LEGACY_GLOBAL`,不改变现有行为;有老系统自管诉求的应用在接入时显式改为 `APP_MANAGED`。

---

## 4. D2:权限命名空间

### 4.1 唯一约束

把原来的域内全局唯一索引替换为两个部分唯一索引:

```sql
DROP INDEX IF EXISTS uk_iam_permission_code;

-- 平台权限:app_service_id 为空
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_permission_platform_code
    ON iam_permission(realm_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;

-- 应用权限:同一应用内唯一,不同应用可重复
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_permission_app_code
    ON iam_permission(realm_id, app_service_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NOT NULL;

-- 平台菜单同样需要独立唯一约束:PostgreSQL 中 NULL 不参与唯一比较,
-- 现有 (realm_id, app_service_id, menu_code) 无法约束 app_service_id 为空的菜单。
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_menu_platform_code
    ON iam_menu(realm_id, menu_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;
```

语义:

- `app_service_id IS NULL` = 平台权限,只服务于 IAM 控制台/平台能力;
- `app_service_id = X` = 应用 X 的权限,只能在应用 X 的角色、菜单、授权中使用;
- 业务应用的 Token **不输出平台权限**;需要跨应用共享能力时,用域角色或组表达,不把平台权限下发给业务应用;
- 应用内权限码建议带应用前缀(`oa:expense:submit`),但不再强制由 IAM 统一生成。

### 4.2 权限管理接口

第一批在现有全局接口上通过 `appServiceId` 表达命名空间,不新增嵌套路由:

```text
GET    /api/v1/admin/permissions?appServiceId={appId}
POST   /api/v1/admin/permissions          (body.appServiceId 为空表示平台权限)
PUT    /api/v1/admin/permissions/{id}
DELETE /api/v1/admin/permissions/{id}
```

要求:

- `PermissionResponse` 增加 `appServiceId`;
- 列表支持 `permissionCode` / `permissionName` / `resource` / `appServiceId` / 分页;
- 权限码、资源、动作与所属应用创建后不可变,更新只允许名称与说明;
- 删除权限前检查角色、菜单、用户特批引用,有引用时拒绝;
- 应用管理员使用的嵌套接口(`/api/v1/admin/apps/{appId}/permissions` 与 `import`)留到应用管理员批次,复用同一服务层校验;
- 平台权限(`appServiceId` 为空)与应用权限互不冲突;不传 `appServiceId` 的列表返回本域全部权限,供全域管理员治理。

### 4.3 存量权限处理

V1.0.6 之前创建的权限都是平台权限(`app_service_id IS NULL`)。迁移时不强制全量回填,按以下顺序处理:

1. 用菜单引用关系做“无歧义回填”:一个权限只被一个应用的菜单引用时,回填该应用;
2. 被多个应用引用、或只被角色直接引用且无菜单的权限,输出报告,由管理员决定是保留为平台权限还是复制到各应用;
3. 业务应用切换到 `SCOPED` Token 前,必须确认它依赖的权限都已归到该应用名下。

审计 SQL:

```sql
-- 权限被哪些应用使用
SELECT p.id, p.permission_code,
       COUNT(DISTINCT m.app_service_id) AS app_count,
       STRING_AGG(DISTINCT a.app_code, ',') AS apps
FROM iam_permission p
LEFT JOIN iam_menu m ON m.permission_id = p.id AND m.deleted = FALSE
LEFT JOIN iam_application_service a ON a.id = m.app_service_id AND a.deleted = FALSE
WHERE p.deleted = FALSE
GROUP BY p.id, p.permission_code
ORDER BY app_count DESC, p.permission_code;

-- 无歧义回填
WITH app_usage AS (
    SELECT m.permission_id,
           MIN(m.app_service_id) AS app_id,
           COUNT(DISTINCT m.app_service_id) AS app_count
    FROM iam_menu m
    WHERE m.deleted = FALSE
      AND m.permission_id IS NOT NULL
      AND m.app_service_id IS NOT NULL
    GROUP BY m.permission_id
)
UPDATE iam_permission p
SET app_service_id = u.app_id
FROM app_usage u
WHERE p.id = u.permission_id
  AND u.app_count = 1
  AND p.app_service_id IS NULL
  AND p.deleted = FALSE;
```

---

## 5. D3:应用角色

### 5.1 模型

```sql
ALTER TABLE iam_role ADD COLUMN app_service_id BIGINT;

DROP INDEX IF EXISTS uk_iam_role_scope;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_role_scope
    ON iam_role(
        realm_id,
        container_type,
        COALESCE(app_service_id, 0),
        COALESCE(client_id, 0),
        role_code
    )
    WHERE deleted = FALSE;

CREATE INDEX IF NOT EXISTS idx_iam_role_app
    ON iam_role(realm_id, app_service_id) WHERE deleted = FALSE;
```

角色类型:

| `container_type` | 用途 | 作用域 |
|---|---|---|
| `REALM` | 组织角色,跨应用 | `app_service_id` 必须为空;可被授予多个应用的权限/菜单,作为跨应用治理入口 |
| `APP` | 应用角色 | `app_service_id` 必填;只能授予同应用的权限/菜单 |
| `CLIENT` | 兼容存量客户端角色 | 保留;有 `app_service_id` 时按应用角色处理,新角色不再创建此类型 |

规则:

- `APP` 角色只能绑定本应用的权限、菜单、用户/组;
- `REALM` 角色允许持有多个应用的权限,这是**有意的跨应用角色**,但必须审计;
- 角色编码在同一应用内唯一,不同应用可以都叫 `admin`;
- 角色响应 DTO 增加 `appServiceId`;
- 新建 `APP` 角色时,如果同一应用下已存在同编码的 `CLIENT` 角色,拒绝创建,避免双份角色。

应用角色使用嵌套接口,避免把 `appId` 混进全局角色查询:

```text
GET    /api/v1/admin/apps/{appId}/roles
POST   /api/v1/admin/apps/{appId}/roles
PUT    /api/v1/admin/apps/{appId}/roles/{roleId}
DELETE /api/v1/admin/apps/{appId}/roles/{roleId}
```

域角色继续走 `/api/v1/admin/roles?containerType=REALM`;角色列表默认返回全部容器类型,
应用角色与用户授权列表都按所属应用分组展示,可按 `containerType` 或 `appServiceId` 收窄
(2026-10-30 修订:原"默认不返回应用角色"会让管理员在授权时看不到已生效的应用角色)。

### 5.2 存量客户端角色回填

```sql
UPDATE iam_role r
SET app_service_id = x.app_id
FROM (
    SELECT sso_client_pk, MIN(id) AS app_id
    FROM iam_application_service
    WHERE deleted = FALSE AND sso_client_pk IS NOT NULL
    GROUP BY sso_client_pk
    HAVING COUNT(*) = 1
) x
WHERE r.deleted = FALSE
  AND r.container_type = 'CLIENT'
  AND r.app_service_id IS NULL
  AND r.client_id = x.sso_client_pk;
```

迁移前检查同一 `sso_client_pk` 是否被多个应用绑定。若存在,先人工拆分,不能自动回填。

### 5.3 应用/客户端绑定

当前 `iam_application_service.sso_client_pk` 是单值。Token 裁剪需要从 client 反查应用,因此 P0 先约束“一个 client 只能绑定一个应用”:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_service_client
    ON iam_application_service(realm_id, sso_client_pk)
    WHERE deleted = FALSE AND sso_client_pk IS NOT NULL;
```

如果企业确实存在“一个应用多个 client(Web/移动/API)”,P1 再引入 `iam_app_client` 关联表;本阶段不提前抽象。

---

## 6. D4:Token 契约

### 6.1 兼容开关

列首建于 V1.0.9:

```sql
ALTER TABLE iam_application_service
    ADD COLUMN token_profile VARCHAR(20) NOT NULL DEFAULT 'LEGACY_GLOBAL';
```

第三批实现裁剪后,由 V1.1.0 切换默认值:

```sql
ALTER TABLE iam_application_service
    ALTER COLUMN token_profile SET DEFAULT 'SCOPED';
```

最终语义:

- **新应用默认 `SCOPED`**:创建接口未传 `tokenProfile` 时写入 `SCOPED`;
- **存量应用保持 `LEGACY_GLOBAL`**:升级不改变已签发令牌的结构,按应用逐个切换;
- 管理端创建/更新请求均可指定 `tokenProfile`(`SCOPED` / `LEGACY_GLOBAL`),切换对下一次签发立即生效;
- 回滚单应用:把 `token_profile` 改回 `LEGACY_GLOBAL`,无需回滚结构;
- `LEGACY_GLOBAL` 只作为迁移期兼容档,计划在 V2 移除。

### 6.2 声明裁剪

授权声明由 `token_profile` 与 `authorization_mode` 共同决定(实现见 `IamJwtTokenCustomizer`):

| Claim | `SCOPED` | `LEGACY_GLOBAL` |
|---|---|---|
| `realm_roles` | 保留全部域角色 | 保留全部域角色 |
| `client_roles` | 仅当前客户端绑定的 `CLIENT` 角色 | 全部 `APP` / `CLIENT` 角色(迁移兼容) |
| `app_roles` | `MAPPED` → 映射结果 ∪ 直授的本应用 `APP` 角色;`IAM_MANAGED` → 本应用 `APP` 角色;`APP_MANAGED` → 不输出 | `MAPPED` → 映射结果;其余不输出 |
| `permissions` | `IAM_MANAGED` / `MAPPED` → 仅当前应用命名空间;`APP_MANAGED` → 不输出 | 登录时计算的全部权限(含 `APP_MANAGED`,迁移兼容) |
| `groups` | 保留 | 保留 |
| `uid` / `sub` / `preferred_username` | 保留 | 保留 |
| `sid` | 仅 ID Token | 仅 ID Token |

实现要求:

- `IamJwtTokenCustomizer` 不再直接使用 `user.getAuthorities()` 作为 SCOPED 权限来源,而是按当前 client 解析应用后计算;
- 从 `RegisteredClient.clientId` → `ClientModel` → `ApplicationService(sso_client_pk)` 得到 `appId`;
- 找不到应用时:**不回落旧行为**,只输出身份声明并记录 WARN;
- 平台权限(`app_service_id IS NULL`)不进入 SCOPED 业务应用 Token;
- `client_roles` 只保留当前 client 绑定角色,解决跨应用角色撞名;
- `app_roles` 是本应用角色,不是全局角色列表;
- 裁剪只影响新签发令牌;已签发令牌在过期/刷新前保持原声明。

### 6.3 应用级权限计算

`IamPermissionManager` 新增:

```java
Set<String> computeAppPermissionCodes(Long realmId, Long userId, Long appServiceId);
```

计算规则:

```text
应用权限 =
  (有效角色[直授 + 组继承] 的 role_permission
   ∪ 有效角色 role_menu 派生的权限,仅限启用菜单且属于该应用
   ∪ 用户 GRANT)
  − 用户 REVOKE
  再按 permission.app_service_id = 当前应用过滤
```

注意:

- 组角色必须参与;
- 菜单必须 `enabled = TRUE`,所属应用必须 `enabled = TRUE`;
- `role_menu` 派生只接受 `menu.app_service_id = appId` 的菜单;
- 平台权限不参与业务应用权限集。

### 6.4 令牌体积

- 单应用权限建议不超过 200 条;
- 超过阈值记录指标 `token_size_bytes`,并评估改为按需权限接口;
- 不在 JWT 中放菜单树;菜单继续走 `/menus` 接口。

---

## 7. D5:应用管理员

### 7.1 模型

```sql
CREATE TABLE IF NOT EXISTS iam_app_admin (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    subject_type VARCHAR(10) NOT NULL,
    subject_id BIGINT NOT NULL,
    granted_by BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_app_admin IS '应用管理员;subject_type:USER/GROUP';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_admin
    ON iam_app_admin(app_service_id, subject_type, subject_id)
    WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_app_admin_subject
    ON iam_app_admin(subject_type, subject_id) WHERE deleted = FALSE;
```

### 7.2 权限边界

应用管理员可以管理本应用的:

- 应用角色;
- 权限目录;
- 菜单;
- 用户/角色/组的应用授权名单;
- 应用管理员名单(可选,默认仅全局管理员可改)。

应用管理员**不能**:

- 管理域角色、平台权限;
- 管理其他应用;
- 修改 OIDC 客户端密钥、删除应用;
- 查看全局审计。

### 7.3 接口鉴权

新增鉴权 Bean:

```java
@PreAuthorize("@appAuthz.canManage(#appId)")
```

判定顺序:

1. 是否拥有平台应用管理权限(全局管理员);
2. 是否是 `iam_app_admin` 中该应用的管理员;
3. 用户所属组是否是应用管理员组。

所有应用作用域接口必须显式接收 `appId`,不能从请求体里“顺便”取;服务层再次校验资源与 `appId` 的归属关系,防止越权 ID 替换。

应用管理员名单接口:

```text
GET    /api/v1/admin/apps/{appId}/admins
POST   /api/v1/admin/apps/{appId}/admins
DELETE /api/v1/admin/apps/{appId}/admins/{adminId}
GET    /api/v1/admin/apps/managed
```

`GET /apps/managed` 用于控制台启动时确定当前用户可管理的应用范围,应用管理员不再依赖全局 `app-service:view` 权限。

### 7.4 审计

复用 `iam_audit_log`:

- `target_type` 取操作类型首段(`MENU_CREATE` → `MENU`);
- `target_id` 记录**被改动的资源主键**(新建取响应体主键,修改/删除取路径变量),
  不再记录作用域里的应用主键——否则事后无法定位"谁改了哪个菜单";
- action 新增:`APP_PERMISSION_CREATE/UPDATE/DELETE/IMPORT`、`APP_ROLE_*`、`APP_MENU_*`、`APP_ADMIN_*`。

---

## 8. D6:老系统接入契约

### 8.1 `APP_MANAGED`

适用:老系统已有完整 RBAC,不希望迁移权限目录。

IAM 只保证:

- OIDC 登录与稳定主体标识(`sub` / `uid` / `preferred_username`);
- 应用准入(门户可见性 + 应用授权名单);
- 可选输出 `groups`;
- 不输出 `permissions`,`app_roles` 为空。

应用侧:

- 继续使用本地角色、菜单、权限表;
- 用 `uid`(推荐)或 `sub` 关联本地用户;
- 应用自己的管理员界面保持不变。

### 8.2 `MAPPED`

适用:希望由 IAM 统一开通应用角色,但细粒度权限仍留在应用。

新增映射表:

```sql
CREATE TABLE IF NOT EXISTS iam_app_role_mapping (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_id BIGINT NOT NULL,
    target_role_code VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE iam_app_role_mapping IS 'IAM 组/域角色 → 应用角色映射;source_type:GROUP/REALM_ROLE';

CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_role_mapping
    ON iam_app_role_mapping(app_service_id, source_type, source_id, target_role_code)
    WHERE deleted = FALSE;
```

Token 输出:

- `app_roles` = 当前应用下命中的 `target_role_code` 集合;
- 不输出 IAM 的角色码本身,避免应用依赖 IAM 内部命名;
- 映射变更下次认证生效,需要立即生效时强制下线。

映射维护接口:

```text
GET    /api/v1/admin/apps/{appId}/role-mappings
POST   /api/v1/admin/apps/{appId}/role-mappings
DELETE /api/v1/admin/apps/{appId}/role-mappings/{mappingId}
```

### 8.3 权限同步

- `IAM_MANAGED` 应用可以通过 `permissions/import` 幂等导入目录;
- `MAPPED` / `APP_MANAGED` 不要求导入;
- 不提供“IAM 自动发现应用权限”的反向扫描,避免把 IAM 变成应用运行时依赖;
- 若应用选择保留自己的管理界面,后续可通过应用管理员服务账号调用应用作用域 API(P1)。

### 8.4 菜单

- `IAM_MANAGED` + `IAM/HYBRID`:菜单由 IAM 托管并下发;
- `MAPPED`:推荐 `menu_mode=APP`,应用用 `app_roles` + 本地菜单渲染;
- `APP_MANAGED`:`menu_mode=APP`,应用完全自管菜单,Token 只提供身份与准入。

---

## 9. D7:一致性修复

### 9.1 统一有效角色解析

新增 `IamEffectiveRoleResolver`:

```text
有效角色 = 用户直授角色 ∪ 用户所属组的角色
         过滤:同 realm、角色启用、未删除
```

当前组模型是直属成员关系;嵌套组继承不在本 ADR 范围,若需要应单独定义,并同样收敛到该解析器。

所有路径统一使用:

- 权限计算(已包含组角色);
- 启动台菜单下发(当前漏组角色);
- 应用可见性(当前漏组角色);
- Token 的 `app_roles` / `client_roles`;
- 角色菜单、角色权限读取。

### 9.2 作用域校验

| 操作 | 校验 |
|---|---|
| 应用角色授应用权限 | 角色 `app_service_id` = 权限 `app_service_id` |
| 应用角色授应用菜单 | 角色 `app_service_id` = 菜单 `app_service_id` |
| 域角色授权限/菜单 | 允许跨应用,但记录审计 |
| 菜单绑定权限 | 菜单应用 = 权限应用;平台菜单只能绑平台权限 |
| 平台菜单编码 | `(realm_id, menu_code)` 在 `app_service_id IS NULL` 范围内唯一 |
| 应用可见性 `requiredPermissionCode` | 必须是该应用下的权限,或平台权限(仅平台应用) |
| 应用授权 ROLE 主体 | 角色必须属于当前 realm;应用角色必须属于当前应用 |
| 应用授权 GROUP 主体 | 组必须属于当前 realm |
| 应用授权名单 | `(app_service_id, subject_type, subject_id)` 唯一,避免重复授权行 |
| 用户特批 GRANT/REVOKE | 权限必须属于目标应用或作为平台权限显式处理 |

### 9.3 菜单派生权限

P0 保持现有“授予菜单即派生权限”的兼容行为,但补:

- `menu.enabled = TRUE`;
- `app.enabled = TRUE`;
- 菜单与权限同应用。

同时明确契约:**停用菜单会使其派生权限失效**。

P1 目标:将“菜单可见性”和“API 权限”解耦——

- `menu.permission_id` 只控制入口是否展示;
- API 鉴权只认 `role_permission`;
- 迁移期为兼容可继续派生,但新应用按解耦模型接入。

### 9.4 应用可见性

- 支持 `USER` / `ROLE` / `GROUP` 三种授权主体;
- `ROLE` 授权使用有效角色(含组继承);
- `PERMISSION` 可见性按应用作用域解析权限码,优先匹配应用权限;
- 应用停用立即生效;角色/组成员变更在下次认证或强制下线后生效。

---

## 10. 迁移方案(V1.0.9)

### 10.1 迁移脚本

```sql
-- 1. 应用授权模式与令牌兼容档
ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS authorization_mode VARCHAR(20) NOT NULL DEFAULT 'IAM_MANAGED';
ALTER TABLE iam_application_service
    ADD COLUMN IF NOT EXISTS token_profile VARCHAR(20) NOT NULL DEFAULT 'LEGACY_GLOBAL';
-- Token 裁剪实现并验证后再执行:
-- ALTER TABLE iam_application_service ALTER COLUMN token_profile SET DEFAULT 'SCOPED';
COMMENT ON COLUMN iam_application_service.authorization_mode IS
    'IAM_MANAGED / MAPPED / APP_MANAGED';
COMMENT ON COLUMN iam_application_service.token_profile IS
    'SCOPED 按应用裁剪 / LEGACY_GLOBAL 迁移期兼容';

-- 2. 权限命名空间
DROP INDEX IF EXISTS uk_iam_permission_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_permission_platform_code
    ON iam_permission(realm_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_permission_app_code
    ON iam_permission(realm_id, app_service_id, permission_code)
    WHERE deleted = FALSE AND app_service_id IS NOT NULL;

-- 平台菜单独立唯一约束(见 §4.1)
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_menu_platform_code
    ON iam_menu(realm_id, menu_code)
    WHERE deleted = FALSE AND app_service_id IS NULL;

-- 3. 角色应用归属
ALTER TABLE iam_role ADD COLUMN IF NOT EXISTS app_service_id BIGINT;
DROP INDEX IF EXISTS uk_iam_role_scope;
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_role_scope
    ON iam_role(realm_id, container_type, COALESCE(app_service_id, 0),
                COALESCE(client_id, 0), role_code)
    WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_role_app
    ON iam_role(realm_id, app_service_id) WHERE deleted = FALSE;

-- 4. 一个 client 只绑定一个应用
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_service_client
    ON iam_application_service(realm_id, sso_client_pk)
    WHERE deleted = FALSE AND sso_client_pk IS NOT NULL;

-- 5. 应用管理员
CREATE TABLE IF NOT EXISTS iam_app_admin (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    subject_type VARCHAR(10) NOT NULL,
    subject_id BIGINT NOT NULL,
    granted_by BIGINT,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_admin
    ON iam_app_admin(app_service_id, subject_type, subject_id) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_iam_app_admin_subject
    ON iam_app_admin(subject_type, subject_id) WHERE deleted = FALSE;

-- 6. 角色映射
CREATE TABLE IF NOT EXISTS iam_app_role_mapping (
    id BIGSERIAL PRIMARY KEY,
    realm_id BIGINT NOT NULL,
    app_service_id BIGINT NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    source_id BIGINT NOT NULL,
    target_role_code VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_role_mapping
    ON iam_app_role_mapping(app_service_id, source_type, source_id, target_role_code)
    WHERE deleted = FALSE;

-- 7. 存量 CLIENT 角色回填应用归属(需先通过 10.2 的唯一绑定检查)
UPDATE iam_role r
SET app_service_id = x.app_id
FROM (
    SELECT sso_client_pk, MIN(id) AS app_id
    FROM iam_application_service
    WHERE deleted = FALSE AND sso_client_pk IS NOT NULL
    GROUP BY sso_client_pk
    HAVING COUNT(*) = 1
) x
WHERE r.deleted = FALSE
  AND r.container_type = 'CLIENT'
  AND r.app_service_id IS NULL
  AND r.client_id = x.sso_client_pk;

-- 8. 应用授权名单去重约束(需先通过 10.2 的重复检查)
CREATE UNIQUE INDEX IF NOT EXISTS uk_iam_app_service_grant
    ON iam_app_service_grant(app_service_id, subject_type, subject_id)
    WHERE deleted = FALSE;
```

> 权限的 `app_service_id` 回填不在 Flyway 中自动执行,按 §4.3 的审计结果人工确认后再跑;避免迁移脚本误改平台权限。

### 10.2 发布前检查

```sql
-- 同一个 client 是否被多个应用绑定
SELECT sso_client_pk, COUNT(*) AS app_count
FROM iam_application_service
WHERE deleted = FALSE AND sso_client_pk IS NOT NULL
GROUP BY sso_client_pk
HAVING COUNT(*) > 1;

-- 存量客户端角色分布
SELECT client_id, role_code, COUNT(*)
FROM iam_role
WHERE deleted = FALSE AND container_type = 'CLIENT'
GROUP BY client_id, role_code
ORDER BY client_id;

-- 权限被多个应用引用
SELECT p.permission_code, COUNT(DISTINCT m.app_service_id) AS app_count
FROM iam_permission p
JOIN iam_menu m ON m.permission_id = p.id AND m.deleted = FALSE
WHERE p.deleted = FALSE AND m.app_service_id IS NOT NULL
GROUP BY p.permission_code
HAVING COUNT(DISTINCT m.app_service_id) > 1;

-- 应用授权名单是否已有重复行
SELECT app_service_id, subject_type, subject_id, COUNT(*) AS row_count
FROM iam_app_service_grant
WHERE deleted = FALSE
GROUP BY app_service_id, subject_type, subject_id
HAVING COUNT(*) > 1;

-- 平台菜单是否存在重复编码
SELECT realm_id, menu_code, COUNT(*) AS row_count
FROM iam_menu
WHERE deleted = FALSE AND app_service_id IS NULL
GROUP BY realm_id, menu_code
HAVING COUNT(*) > 1;
```

### 10.3 发布顺序

1. 执行检查,处理重复 client 绑定和跨应用权限;
2. 应用 V1.0.9(存量应用仍为 `LEGACY_GLOBAL`,行为不变);
3. 上线新接口与统一角色解析,先不切 Token 档;
4. 选一个试点应用:回填/创建应用权限 → 迁移角色与菜单 → 对比旧/新 Token → 切 `SCOPED`;
5. 试点应用管理员,验证越权拦截和审计;
6. Token 裁剪上线并完成试点对比后,把新应用默认值切到 `SCOPED`,存量应用按计划逐个切换;
7. 全部切换后删除 `LEGACY_GLOBAL` 分支(V2)。

### 10.4 回滚

- 单应用回滚:把 `token_profile` 改回 `LEGACY_GLOBAL`;
- 结构变更均为增量,旧代码可继续运行;
- 新表、新列不参与旧路径;
- 角色唯一索引已替换,但旧代码只创建 `REALM/CLIENT` 角色,兼容;新 `APP` 角色旧代码不可见,不影响旧路径。

### 10.5 代码改造清单

| 模块 | 改造 |
|---|---|
| `beenest-iam-persistence` | `iam_permission` / `iam_role` / `iam_application_service` 实体与 Mapper 补字段;新增 `iam_app_admin` / `iam_app_role_mapping`;新增 V1.0.9;新增 `IamEffectiveRoleResolver` |
| `beenest-iam-admin` | 权限写接口与应用作用域接口;角色 `APP` 类型与作用域校验;菜单权限同应用校验;应用授权支持 GROUP;应用管理员与角色映射管理;DTO/Converter |
| `beenest-iam-authz` | `IamJwtTokenCustomizer` 按 app 裁剪;`IamPermissionManager.computeAppPermissionCodes`;应用管理员鉴权 Bean |
| `beenest-iam-console` | `LaunchpadServiceImpl` 使用有效角色;应用可见性支持 GROUP;菜单过滤启用状态 |
| `beenest-iam-web/frontend` | 权限/角色/菜单页按应用过滤;应用页增加授权模式、Token 档、管理员与映射入口;应用管理员导航与路由守卫 |
| 测试 | 双应用同名权限、Token 隔离、跨应用 403、组角色一致性、`APP_MANAGED` 无权限、`MAPPED` 映射、旧应用兼容 |

---

## 11. 验收标准

### 11.1 多应用隔离

- 应用 A、B 都能创建 `user:view`,互不冲突;
- A 的 Token 只含 A 的应用权限和 A 的角色;
- A 的应用管理员访问 B 的角色/权限/菜单接口返回 403;
- A 的应用角色不能授予 B 的权限或菜单;域角色跨应用授权必须留下审计记录。

### 11.2 老系统

- `APP_MANAGED` 应用只配置应用准入即可完成 OIDC 接入,Token 无 `permissions`;
- `MAPPED` 应用按组/域角色输出 `app_roles`,不输出 IAM 角色码;
- 老系统本地 RBAC 数据不需要导入 IAM。

### 11.3 一致性

- 组角色用户能看到对应菜单和应用(修复当前直授角色才生效的问题);
- 停用菜单后,派生权限不再出现;
- `PERMISSION` 可见性只命中本应用的权限码;
- 应用授权支持 GROUP 主体。

### 11.4 兼容

- 存量应用切换前 Token 行为不变;
- 切换后对照旧 Token,差异仅限预期的应用作用域;
- JWT 体积有监控,未超过阈值。

---

## 12. 风险与待决问题

| 风险 | 处理 |
|---|---|
| 存量应用依赖平台权限 | 先 `LEGACY_GLOBAL`,逐应用对比后切换 |
| 权限回填误判 | 只自动回填无歧义项,其余出报告人工确认 |
| 同一 client 多应用绑定 | 发布前检查,人工拆分或 P1 引入 `iam_app_client` |
| 应用管理员越权 | 服务层双重校验 + 专项越权测试 + 审计 |
| 组角色修复后菜单变多 | 属于预期修复,灰度观察并记录审计 |
| Token 仍可能过大 | 单应用权限上限、体积指标、按需权限接口(P1) |
| 菜单派生权限语义 | P0 明确“停用菜单即失效”,P1 解耦展示与 API 授权 |

待决:

1. `MAPPED` 是否允许同时使用 IAM 权限目录(默认允许但不要求);
2. 应用管理员是否可以修改应用可见性和授权名单(建议允许,删除应用与密钥管理除外);
3. 是否需要在 P1 引入应用级 client 多绑定表;
4. `LEGACY_GLOBAL` 的最终移除时间。

---

## 13. 文档与实现影响

实现本 ADR 后需要同步更新:

- `docs/app-access-architecture.md`:§3.3 权限码约束、§3.4 应用角色、§7 权限模型、§9 演进路线、§10 ADR 表;
- `docs/client-integration.md`:§6.1 Token 声明、§6.3 示例载荷、§8.5 应用角色用法;
- `docs/admin-console.md`:应用管理员页面、应用作用域管理;
- DTO:`PermissionResponse` / `RoleResponse` 增加 `appServiceId`;`RoleCreateRequest` / `RoleUpdateRequest` 支持 `containerType=APP` + `appServiceId`;`ApplicationServiceCreateRequest` / `ApplicationServiceUpdateRequest` / `ApplicationServiceResponse` 增加 `authorizationMode` / `tokenProfile`;
- 控制台页面:权限页增加应用筛选/列,角色页按应用过滤,菜单页权限选择器按应用过滤,应用页增加授权模式与管理员入口。

**当前状态:第一至第五批均已实施(2026-10-21),包含应用命名空间与作用域校验、应用管理员与角色映射、`SCOPED` Token 裁剪(新应用默认、存量应用逐应用切换)、用户权限 GRANT/REVOKE 治理,以及应用管理员独立工作台(嵌套接口 + 控制台入口 + 跨应用越权防护);存量应用权限回填报告与幂等导入已由第五批补齐(见 §18)。**

---

## 14. 第一批实施记录(2026-10-21)

范围:V1.0.9 迁移、权限命名空间、应用作用域校验、有效角色统一;Token 收敛仅通过 `token_profile` 预留灰度位。

### 14.1 已完成

| 模块 | 实施内容 |
|---|---|
| 迁移 V1.0.9 | `authorization_mode` / `token_profile` 列;平台与应用权限双唯一索引;平台菜单唯一索引;`iam_role.app_service_id` 与作用域唯一索引;`uk_iam_app_service_client`;`iam_app_admin` / `iam_app_role_mapping` 表;CLIENT 角色应用归属回填;应用授权名单唯一索引;补种 `permission:create/update/delete` 并授予 `SUPER_ADMIN` |
| 权限命名空间 | 权限 CRUD(全局接口 + `appServiceId` 参数);应用命名空间内权限码唯一;更新只改名称/说明;删除前检查角色、菜单、用户特批引用 |
| 应用角色 | `containerType=APP` + `appServiceId`;APP 角色必须挂本域应用;APP 与同应用存量 CLIENT 角色编码冲突拒绝;绑定客户端的 CLIENT 角色自动推导应用 |
| 作用域校验 | APP 角色与绑定应用的 CLIENT 角色只能授予同应用权限/菜单;菜单挂载权限必须与菜单同应用;应用授权支持 GROUP 主体 |
| 有效角色统一 | 新增 `IamEffectiveRoleResolver`:直授角色 ∪ 组继承角色;权限计算、启动台菜单、应用可见性、Token 角色输出统一使用;菜单派生权限增加菜单/应用启用过滤 |
| 控制台 | 权限页支持应用筛选、应用列与增删改;角色页支持 APP 类型、应用筛选、按应用收窄的权限/菜单授予;菜单页权限选择器按应用过滤;应用页展示并维护授权模式与令牌档 |

### 14.2 有意保留的限制

- `token_profile` 默认值仍为 `LEGACY_GLOBAL`,Token 声明仍是全域扁平集合;
- 因此应用间权限码可以重复,但同码权限在 `permissions` 中会合并,**Token 收敛上线前不要依赖应用级同码权限的隔离语义**;
- 应用可见性 `requiredPermissionCode` 在应用内未命中时仍回退平台权限码,保持存量应用可用;
- 应用管理员表、角色映射表已建,但对应 API、鉴权 Bean 与控制台入口在后续批次实现;
- 应用作用域的嵌套接口(`/admin/apps/{appId}/...`)尚未提供,当前由全局接口 + 服务层作用域校验兜底。

> 本节记录的是**第一批落地当时**的边界,后续批次的进展见 §14.3 备注、§15 与 §16:
> 应用管理员/角色映射 API 与 `appAuthz` 鉴权 Bean 已由第二批补齐;
> `token_profile` 默认值切到 `SCOPED`、Token 按应用裁剪已由第三批完成(存量应用仍逐应用切换);
> 应用作用域嵌套接口(`/api/v1/admin/apps/{appId}/workspace/**`)与控制台工作台入口已由第四批补齐(见 §17)。

### 14.3 下一批

1. `IamJwtTokenCustomizer` 按应用裁剪 `permissions` / `client_roles`,输出 `app_roles`;
2. 把 `token_profile` 列默认值切到 `SCOPED`,存量应用逐应用切换;
3. 应用管理员鉴权 Bean、管理接口与控制台导航;
4. `MAPPED` 角色映射 API 与 Token `app_roles` 输出;
5. 权限回填报告与幂等导入接口。

> 第 3、4 项已由第二批(§15)完成,第 1、2 项已由第三批(§16)完成;第 5 项(权限回填与幂等导入)已由第五批(§18)完成。

### 14.4 回归验证(2026-10-21)

- V1.0.9 在本地 PostgreSQL(1.0.8 → 1.0.9)迁移成功,部分唯一索引、列默认值与 `permission:*` 补种均按预期生效;
- 现场验证应用命名空间:应用 A/B 可各自创建同码权限 `smoke:export` 与同码 APP 角色,同应用内重复被拒绝(10003);
- 现场验证作用域校验:跨应用权限、平台权限授予 APP 角色均被拒绝(10001);跨应用菜单授予、菜单挂载他应用权限均被拒绝;同应用权限/菜单授予成功;
- 现场验证有效角色:仅通过用户组继承 APP 角色后重新登录,`/auth/me` 权限数由 79 增至 80 且包含该角色权限,组角色汇聚生效;
- 现场验证过滤:权限、角色按 `appServiceId` 过滤,应用按 `authorizationMode` 过滤均返回正确子集;
- 修复:角色逻辑删除后 `iam_role_permission` 残留行会永久阻止权限删除,引用计数改为仅统计未删除角色;重启后复测可正常删除;
- `./mvnw test` 38 个测试全部通过;冒烟账号、应用、角色、权限、菜单、组、会话、登录事件与审计记录已全部清理。

---

## 15. 第二批实施记录(2026-10-21)

范围:应用管理员 API、`appAuthz` 作用域鉴权、MAPPED 角色映射与令牌 `app_roles`;Token 裁剪(`SCOPED`)不在本批。

### 15.1 已完成

| 模块 | 实施内容 |
|---|---|
| 持久层 | `AppAdmin` / `AppRoleMapping` 实体与 Mapper;`IamAppAdminManager`(用户/组管理员判定、管理范围并集)与 `IamAppRoleMappingManager`(启用映射解析、去重排序);应用删除级联清理管理员与映射 |
| 作用域鉴权 | `@appAuthz`(`AppAuthorizationService`):全局 `app-service:update` 优先,否则按用户直授或用户组继承的应用管理员关系;应用必须属于当前身份域 |
| 管理员 API | `GET /api/v1/admin/apps/managed` 返回当前用户可管理应用;`GET/POST /{appId}/admins`、`DELETE /{appId}/admins/{adminId}` 维护名单。名单写操作仅全局管理员(`app-service:assign`),应用管理员可读取 |
| 角色映射 API | `GET/POST /{appId}/role-mappings`、`DELETE /{appId}/role-mappings/{mappingId}`;来源支持 `GROUP` / `REALM_ROLE`,必须属于本域;`REALM_ROLE` 只接受域角色;同来源同目标角色唯一 |
| 令牌 | MAPPED 应用且客户端已绑定应用时,按用户有效组/有效角色解析映射并输出 `app_roles`;SCOPED 下同时并入直授的本应用 `APP` 角色;目标角色码去重排序;非 MAPPED 不输出,映射可预配置但暂不生效 |
| 控制台 | 应用页新增「管理员」「角色映射」入口;管理员抽屉支持用户/用户组名单增删;映射抽屉支持组/域角色到应用角色码的增删;详情页对 MAPPED 应用展示 `app_roles` 说明 |
| 审计 | 新增 `APP_ADMIN_CREATE/DELETE`、`APP_ROLE_MAPPING_CREATE/DELETE` |

### 15.2 决策与边界

- **管理员名单由全局管理员维护**:应用管理员可以管理本应用角色映射、查看名单,但不能自行扩权;这是防止"应用管理员任命自己人"形成授权闭环的关键边界。
- **`appAuthz` 每次请求现算**:管理员新增/移除在下一次请求即生效,不要求重新登录;映射变更仍遵循令牌快照,下次认证生效,需要立即生效时强制下线。
- **映射来源只允许组与域角色**:应用角色是终态授权,直接授予用户后按角色码并入 `app_roles`(见 §19),不再作为映射来源,避免出现难以排查的映射链。
- **允许预配置**:非 MAPPED 应用也可维护映射,便于切换授权模式前准备;是否输出只由 `authorization_mode` 决定。
- **空结果不写声明**:映射未命中时不写 `app_roles`,避免给存量应用注入空声明而触发兼容分支。
- **当前入口面向全局管理员**:应用管理员独立导航与应用作用域嵌套接口仍在后续批次。

> 本条为第二批落地当时的边界,已由第四批补齐:应用管理员登录后只出现「应用工作台」入口,
> 通过 `/api/v1/admin/apps/{appId}/workspace/**` 在应用作用域内独立交付(见 §17)。

### 15.3 验证

- 单元测试 52 个全部通过,其中新增 14 个:
  - `AppAuthorizationServiceTest` 6 个:未登录、跨域、全局权限、组管理员、非管理员、应用不存在;
  - `IamAppRoleMappingManagerTest` 4 个:组/域角色命中与去重排序、跨域忽略、无来源命中、未知来源类型;
  - `IamJwtTokenCustomizerTest` 4 个:MAPPED 输出、IAM_MANAGED 不输出、未绑定应用不输出、非数字客户端主键跳过。
- 前端 `npm run typecheck` 与 `npm run build` 通过。
- 控制台浏览器验证:「管理员」「角色映射」抽屉可正常加载用户/组/域角色下拉,增删名单与映射后列表和计数即时刷新,移除/删除均走二次确认并提示结果。
- 现场 API 冒烟:
  - 全局管理员 `/apps/managed` 返回全部应用;应用管理员仅返回被授权应用,撤销管理员后立即返回空;
  - 应用管理员可读名单、维护映射;写管理员名单返回 403;无关用户读名单返回 403;
  - 重复映射返回 409,APP 角色作 `REALM_ROLE` 来源返回 400,非法目标角色码返回 400,跨应用删除返回 403;
  - 删除应用后管理员与映射级联软删;
  - 真实 OIDC 授权码流程(公共客户端 + PKCE)取得 access token,`app_roles = ["SMOKE_APP_ADMIN"]`,`realm_roles` 保持 `SUPER_ADMIN`;
  - 冒烟用户、应用、客户端、角色、管理员/映射、登录事件与审计记录已清理。

### 15.4 下一批

1. Token 裁剪 `SCOPED`:按绑定应用裁剪 `permissions` / `client_roles`,并将 `token_profile` 默认值切到 `SCOPED`;
2. 应用管理员控制台导航与应用作用域嵌套接口(`/admin/apps/{appId}/...`);
3. 权限回填报告与幂等导入接口;
4. MAPPED 映射命中诊断(按用户查看命中的来源与目标角色)。

> 第 1 项已由第三批(§16)完成,第 2 项已由第四批(§17)完成;第 3 项已由第五批(§18)完成;第 4 项仍未实施。

---

## 16. 第三批实施记录(2026-10-21)

范围:Token 裁剪 `SCOPED`、应用级权限计算、`token_profile` 管理端开放与新应用默认值切换;应用管理员独立导航、应用作用域嵌套接口与权限导入不在本批。

### 16.1 已完成

| 模块 | 实施内容 |
|---|---|
| 权限计算 | `PermissionProvider` 新增 `computeAppPermissionCodes(realmId, userId, appServiceId)`;`IamPermissionManager` 统一为 `compute` 一条路径:菜单派生只接受当前应用的启用菜单,角色直授 / 用户 GRANT / REVOKE 均按 `permission.app_service_id` 过滤 |
| 令牌裁剪 | `IamJwtTokenCustomizer` 解析客户端绑定应用后按 `token_profile` 输出:身份声明始终保留;`SCOPED` 下 `client_roles` 只含当前客户端、`app_roles` 输出 IAM 应用角色或映射结果、`permissions` 只算当前应用;`LEGACY_GLOBAL` 保持原全域扁平集合 |
| 默认值切换 | 新增 `V1.1.0__token_scoped_default.sql`,把 `token_profile` 列默认值改为 `SCOPED`;存量应用保持 `LEGACY_GLOBAL`,逐应用切换 |
| 管理端 | 创建/更新请求新增 `tokenProfile`(`SCOPED` / `LEGACY_GLOBAL`);创建缺省 `SCOPED`,更新支持切换,响应返回该字段 |
| 控制台 | 应用页新增「令牌档」列与筛选项;创建/编辑表单提供令牌档选择并提示切换前提;详情抽屉对 `SCOPED` 展示裁剪范围说明,对 `LEGACY_GLOBAL` 展示迁移期警告 |
| 测试 | `IamJwtTokenCustomizerTest` 新增 5 个 SCOPED 用例(共 9 个);新增 `IamPermissionManagerTest` 3 个;全量 60 个测试通过 |

### 16.2 决策与边界

- **裁剪只作用于新签发令牌**:已签发令牌保持原声明直到过期或刷新;令牌档切换不强制下线,需要立即收敛时执行强制下线;
- **未绑定应用不回落**:客户端未绑定应用服务时只输出身份声明,并记录 WARN;这是默认拒绝策略,避免全域权限经未登记客户端泄露;
- **`APP_MANAGED` 不输出授权声明**:SCOPED 下不输出 `permissions` / `app_roles`,由应用自行鉴权;`LEGACY_GLOBAL` 为兼容存量仍输出全部权限;
- **MAPPED 的 `app_roles` 不受收敛档影响**:映射结果是目标角色码,便于应用侧直接匹配;`permissions` 在 SCOPED 下仍按应用权限计算;
- **切换粒度是应用而非租户**:每个应用独立回滚,不需要回滚数据库结构;结构变更均为增量,旧代码可继续运行;
- **令牌体积**:SCOPED 下 `permissions` 只含单应用权限,显著小于全域集合;仍建议单应用不超过 200 条。

### 16.3 验证

- 单元测试 60 个全部通过,其中第三批新增 8 个:
  - `IamJwtTokenCustomizerTest` 5 个:SCOPED + IAM_MANAGED 裁剪、SCOPED + MAPPED、SCOPED + APP_MANAGED、未绑定应用仅身份声明、LEGACY_GLOBAL 保持旧结构;
  - `IamPermissionManagerTest` 3 个:平台/他应用权限过滤、菜单派生与 GRANT/REVOKE 的应用内计算(停用菜单排除)、停用应用不派生权限。
- 前端 `npm run typecheck` 与 `npm run build` 通过。
- 迁移验证:V1.1.0 在本地 PostgreSQL 执行成功,列默认值为 `SCOPED`;存量应用 `drone` / `oa-demo` 仍为 `LEGACY_GLOBAL`。
- 控制台浏览器验证:应用列表展示「SCOPED · 按应用收敛」;编辑抽屉可切换令牌档并保存,列表与数据库即时更新;反向切回 `SCOPED` 同样生效。
- 真实 OIDC 冒烟(公共客户端 + PKCE,同一用户、同一应用):
  - 新建应用未指定 `token_profile`,落库为 `SCOPED`;
  - `SCOPED` 令牌:`realm_roles=["SUPER_ADMIN"]`、`client_roles=["SMOKE_CLIENT_ADMIN"]`、`app_roles=["SMOKE_APP_ADMIN"]`、`permissions=["smoke:token-view"]`;另一客户端角色被裁掉;
  - 通过管理端 API / 控制台切到 `LEGACY_GLOBAL` 后重新授权:`client_roles` 含 3 个 APP/CLIENT 角色、`permissions` 为全域 80 条、`app_roles` 不输出;
  - 切回 `SCOPED` 后数据库与列表同步,冒烟用户、应用、客户端、角色、权限、会话、登录事件与审计记录已清理。

### 16.4 下一批

1. ~~应用管理员控制台独立导航与应用作用域嵌套接口(`/admin/apps/{appId}/...`)~~ → 见 §17;
2. ~~权限回填报告与幂等导入接口(存量应用切 `SCOPED` 前的必备工具)~~ → 见 §18;
3. MAPPED 映射命中诊断(按用户查看命中的来源与目标角色);
4. `LEGACY_GLOBAL` 下线计划:全部应用切换后移除兼容分支与全部客户端角色输出(V2)。

---

## 17. 应用管理员独立工作台实施记录(2026-10-21)

范围:应用作用域嵌套接口、管理控制台独立入口、跨应用越权防护;权限回填与导入不在本批。

### 17.1 已完成

| 模块 | 实施内容 |
|---|---|
| 后端 | 新增 `AppWorkspaceController`(`/api/v1/admin/apps/{appId}/workspace/**`,类级 `@PreAuthorize("@appAuthz.canManage(#appId)")`)与 `AppWorkspaceServiceImpl`:概览、菜单 CRUD、角色 CRUD、角色权限/菜单授予(整体覆盖)、应用权限 CRUD、映射来源检索;服务层二次校验资源归属 |
| 作用域收敛 | 创建时服务端强制收敛:菜单与权限写入路径上的应用主键,角色强制 `containerType=APP` 并绑定该应用;请求体中的应用主键只作提示;跨应用或跨域主键按"不存在"处理(10002) |
| 角色映射 | 复用 `AppRoleMappingController`(`app-service:update` 或 `canManage`),`GET /apps/{appId}/admins` 对应用管理员开放只读;名单写操作仍限全局管理员(`app-service:assign`) |
| 控制台 | 新增「应用工作台 → 应用管理」(`/console/workspace`):应用选择器 + 六个页签(概览/菜单/角色/权限/角色映射/管理员),应用与页签写入 `?app=&tab=`;菜单树 CRUD、角色权限与菜单授予弹窗、映射增删、管理员只读列表 |
| 导航裁剪 | `navigation.ts` 增加 `requiresManagedApp` 标记;启动时取 `/api/v1/admin/apps/managed` 决定可管理应用;`canAccessNav` 同时校验权限码与可管理应用,路由守卫、侧边栏与命令面板共用同一判定 |
| 一致性修复 | `/apps/managed` 的判定口径由 `app-service:view` 改为 `app-service:update`,与 `appAuthz.canManage` 对齐,避免"看得到入口却 403";审计目标主键改为被改动资源(见 §7.4) |

### 17.2 决策与边界

- **路径主键是唯一授权边界**:所有资源主键都做归属校验,他应用主键按"不存在"处理,
  不返回"资源属于别的应用"这类可区分信息;
- **应用管理员不能自行扩权**:管理员名单只读,接入参数、可见性、令牌档仍由全局管理员维护;
- **工作台与应用门户页分工**:门户页面向全局管理员(应用接入参数),工作台面向应用管理员(应用内自治),
  两者数据来源不同,避免一个页面里混入两套权限模型;
- **变更生效沿用既有约定**:菜单、角色、权限、映射变更在目标账号下次认证时生效,
  需要立即收敛时由全局管理员执行强制下线。

### 17.3 验证

- 单元测试 84 个全部通过,本批新增 14 个:
  - `AppWorkspaceServiceImplTest` 10 个:创建强制作用域、跨应用菜单/角色/权限拒绝、跨域应用拒绝、概览计数、映射来源过滤与非法类型;
  - `AuditLogAspectTest` 4 个:响应体主键优先、应用作用域取资源主键、平台级 `id` 变量、无路径变量兜底。
- 前端 `npm run typecheck` 与 `npm run build` 通过;工作台视图产物 39.27 kB(gzip 11.41 kB)。
- 现场冒烟(打包 jar,8080,本地 PostgreSQL/Redis):
  - 应用管理员(无任何全局角色)登录后控制台只出现「应用工作台」入口,命令面板仅 1 项;
  - `/apps/managed` 只返回被授权应用;访问他应用工作台、平台用户/角色接口、写入管理员名单均 403;
  - 工作台内完成菜单创建(挂权限)、角色创建、角色权限与菜单授予、权限与角色映射增删,列表与计数即时刷新;
  - 跨应用主键(他应用菜单、域级权限)按"不存在"处理;平台权限授予应用角色被拒绝;
  - 审计 `target_id` 记录被改动资源主键(新建取新主键,删除取被删主键);
  - 冒烟账号、凭证、管理员关系、菜单/角色/权限/映射、会话、登录事件与审计记录已清理,服务已停止。

### 17.4 下一批

1. ~~权限回填报告与幂等导入接口(存量应用切 `SCOPED` 前的必备工具)~~ → 见 §18;
2. MAPPED 映射命中诊断(按用户查看命中的来源与目标角色);
3. `LEGACY_GLOBAL` 下线计划:全部应用切换后移除兼容分支与全部客户端角色输出(V2)。

---

## 18. 第五批实施记录(2026-10-21)

范围:存量应用切 `SCOPED` 令牌档的前置工具——权限回填报告 + 幂等导入接口 + 控制台回填抽屉。

### 18.1 已完成

| 模块 | 实施内容 |
|---|---|
| 持久层 | `IamAppBackfillManager`:报告分析(`analyze`)、归属迁移(`moveToApp`)、复制到应用(`copyToApp`);`updateAppServiceIdByIds` 批量改归属;菜单同码副本查找 |
| 分析口径 | 候选 = 本应用(应用角色直授 / 菜单派生)正在依赖、但仍属平台命名空间或他应用的权限与菜单;应用自有菜单不算缺口;域角色持有、被其它应用共用、已归属他应用分别给出不同 `strategy` 与 `reasons` |
| 幂等 | `COPY` 复用应用下同码副本并改指向授权引用;`MOVE` 只更新仍处于平台命名空间的行;同一批主键重复导入不产生新行 |
| 安全边界 | 域角色持有的平台资源**禁止 `MOVE`**(迁进单个应用会让其它应用的 `SCOPED` 令牌彻底失去它),只允许 `COPY`;`COPY` 为域角色追加副本引用,保证域角色下的用户在本应用令牌里仍能拿到这些权限 |
| 管理端 API | `GET /api/v1/admin/apps/{appId}/backfill/report`(类级 `@appAuthz.canManage`)、`POST .../backfill/import`(追加 `hasAuthority('app-service:backfill')`,审计 `APP_BACKFILL_IMPORT`);跨域应用与跨域资源按"不存在"处理 |
| 迁移 | 新增 `V1.1.2__app_permission_backfill.sql`:补种 `app-service:backfill` 权限码并授予内置 `SUPER_ADMIN`;**不在迁移里自动回填数据**,必须先看报告再人工确认导入 |
| 控制台 | 新增 `AppBackfillDrawer`:报告(可直接迁移 / 只能复制 / 被其它应用共用三档计数 + 令牌档徽标)、按权限/菜单分组的多选、策略单选(选中只读候选时禁用 `MOVE`)、执行结果与冲突清单;应用门户页与应用工作台共用该抽屉 |

### 18.2 决策与边界

- **回填必须显式选择主键**:导入请求只处理被选中的资源,空选择等价于不动作;
  避免一次操作把整个平台的资源搬走。
- **域角色持有是硬约束**:`MOVE` 会改变平台资源的可见范围,只要有域角色持有该资源就拒绝,
  冲突信息直接写进响应,前端展示为冲突清单而不是失败。
- **`COPY` 保留平台原件**:平台原件继续服务其它应用,应用下的副本承接本应用的授权引用;
  域角色额外追加副本引用,这是"域角色用户也能在本应用令牌里拿到权限"的关键。
- **回填后报告必须清零**:判定条件是"应用下已有同码副本,且持有平台原件的相关角色都持有副本,
  且没有应用自有菜单仍挂在平台原件上";不满足则仍是缺口,继续出现在报告里。
- **报告只读、导入要权限**:应用管理员需要知道本应用还差什么,因此报告开放给 `canManage`;
  导入会改变平台资源归属,只允许持有 `app-service:backfill` 的全局管理员执行。
- **`MOVE` 的菜单范围只跟随本次选择**:迁移菜单时连同祖先一起迁(避免子菜单变孤儿),
  但不再把整个域的菜单卷进来。

### 18.3 验证

- 单元测试 98 个全部通过,本批新增 10 个:
  - `IamAppBackfillManagerTest` 5 个:平台级可迁、跨应用只能复制、域角色持有单列提示、迁移只改平台命名空间的行、菜单只跟随所选祖先;
  - `AppBackfillServiceImplTest` 4 个:报告按策略汇总、跨域应用按不存在处理、按所选策略分发、跨域资源主键按不存在处理;
  - `IamAppBackfillManagerTest` 新增"已回填完成不再出现"与"副本未被授予仍是缺口"两个用例。
- 前端 `npm run typecheck` 与 `npm run build` 通过(回填抽屉产物 10.05 kB)。
- 现场冒烟(打包 jar,8080,本地 PostgreSQL/Redis):
  - 报告输出 83 项候选(81 权限 + 2 菜单),全部为"只能复制"——因为 `SUPER_ADMIN` 作为域角色持有这些平台权限;
  - 执行 `COPY` 后落库 81 条应用权限 + 2 条应用菜单(菜单树保持 `expense` → `expense.list`),授权引用 81 + 2 条改指向副本,平台原件 81 条未动;
  - 重复导入同一批主键:计数不变、不产生新行(幂等);
  - 刷新报告清零,控制台展示"当前应用没有需要回填的资源,可以直接切换到 SCOPED 令牌档。";
  - `MOVE` 对域角色持有的资源被拒绝,冲突信息为"权限由域角色持有,迁移会影响其它应用,请改用复制";
  - 审计落 `APP_BACKFILL_IMPORT`(`target_type=APP`);冒烟用户、应用副本、授权引用、会话、登录事件与审计记录已清理。
- 顺带修复:回填抽屉原先默认选中 `MOVE`,与服务端建议的 `COPY` 相反,现已按报告建议自动切换,
  并在选中只读候选时禁用 `MOVE`。

### 18.4 下一批

1. MAPPED 映射命中诊断(按用户查看命中的来源与目标角色);
2. `LEGACY_GLOBAL` 下线计划:全部应用切换后移除兼容分支与全部客户端角色输出(V2);
3. 回填报告接入应用门户页的令牌档切换前置校验(切换 `SCOPED` 时若仍有缺口则拦截并给出跳转)。

---

## 19. 应用角色直授与角色可见性修订(2026-10-30)

范围:角色列表默认范围、应用角色授予用户、MAPPED 下 `app_roles` 的构成、控制台权限判定。

### 19.1 背景

投产试用反馈三件事:角色列表默认只出现域角色与客户端角色;用户授权选不到应用角色;
应用门户的角色映射只能选用户组与域角色。复核后确认根因是同一处设计缺口:
**应用角色被判为"不可见、不可直授",只能经由映射间接生效**,但 ADR §5.1 又要求
`APP` 角色"只能绑定本应用的权限、菜单、用户/组",两条边界互相矛盾。

### 19.2 决策

- **角色列表默认返回全部容器类型**:`GET /api/v1/admin/roles` 在不传 `containerType`
  时不再隐式排除 `APP`;传 `appServiceId` 时仍只返回该应用的 `APP`/`CLIENT` 角色。
  原"默认隐藏"的动机是避免应用角色污染平台视图,现改为**前端按所属应用分组展示**,
  既保留可读性又不牺牲可发现性。
- **应用角色是终态授权,可直接授予用户**:`PUT /api/v1/admin/users/{id}/roles` 接受
  `APP` 角色;服务端校验角色属于当前域且必须绑定所属应用(脏数据直接拒绝)。
  令牌侧 `SCOPED` 档下,`app_roles` 由两部分取并集:
  1. MAPPED 映射命中的目标角色码;
  2. 用户有效角色中属于本应用的 `APP` 角色码。

  因此"给用户选一个本应用的应用角色"等价于"直接给应用下发同名角色",无需再建映射。
- **映射来源仍限组与域角色**:应用角色已经能直授并原样下发,再允许它作为映射来源
  只会产生一跳以上的映射链,与 §15.2 的可排查性要求冲突。
- **`LEGACY_GLOBAL` 不并入直授应用角色**:存量应用保持原声明形态,只有 `SCOPED`
  应用获得新语义;这与"兼容档不改存量行为"的既定原则一致。
- **控制台权限判定收紧**:前端 `can()` 不再识别 `*` 通配符(服务端从不签发该值,
  保留它等于留一条隐式全量放行);没有权限码的入口仍需显式 `requiresManagedApp` 等条件。

### 19.3 关于"空权限角色仍能看到全部菜单"

现场复核结论:该现象无法在当前构建复现。用一个只挂"无任何权限/菜单"域角色的账号登录,
`/api/v1/auth/me` 返回的 `authorities` 为空,控制台侧栏不渲染任何菜单并落到 `/forbidden`。
真实成因是**被授予空角色的账号同时仍持有其他角色**(典型是用管理员本人账号追加新角色),
或观察发生在权限快照刷新之前(权限变更对已登录会话在代次刷新后生效)。

为避免同类误判,本次补两条回归:

- `IamPermissionManagerTest`:有效角色存在但未授予任何权限/菜单时,权限集合必须为空,
  不产生任何兜底放行;
- `IamJwtTokenCustomizerTest`:MAPPED 下直授应用角色的并入与 `LEGACY_GLOBAL` 的不并入。

### 19.4 验证

- 空权限账号浏览器实测:登录后 `/console` 直接落到 `/forbidden`,侧栏与命令面板均为空;
  应用门户仅显示 `PUBLIC` 可见性应用。
- `npm run typecheck`、`npm run build` 通过,控制台静态产物已同步重建。
- 单元测试覆盖上述两条回归,Maven 离线构建通过。

## 20. 应用角色即应用访问权与授权名单作用域修订(2026-10-30)

范围:应用授权弹窗候选口径、`ROLE` 主体作用域校验、角色删除后的授权行清理、
应用角色与门户可见性的关系。

### 20.1 背景

生产反馈:`应用门户 → 应用授权 → 可访问角色` 保存报
`授权主体不存在于当前身份域: ROLE#2`。本地按同一路径复现:角色被删除后,
`iam_app_service_grant` 中该角色的授权行未清理;弹窗回读名单时原样带回该 id,
整表覆盖保存的校验因此拒绝,本次编辑一并作废。

同期另一条反馈:给用户直授应用角色后,门户看不到对应应用。原因是应用可见性
只认 `iam_app_service_grant`,应用角色本身不算访问权。

### 20.2 决策

- **应用角色即该应用的访问权**:`ApplicationVisibilityService` 计算授权应用集合时,
  把用户有效角色中 `app_service_id` 非空的应用并入。持有应用角色即可在启动台看到并进入
  该应用,不必再补一条 ROLE 授权行;令牌受众回退同口径。`PUBLIC` 不变,
  `PERMISSION` 仍以权限码为准。
- **`ROLE` 主体只接受落在该应用作用域内的角色**:域角色(`app_service_id IS NULL`)
  可授权给任意应用;应用角色(含已绑定应用的 `CLIENT` 角色)只能授权给所属应用,
  否则返回 `应用角色只能授权给所属应用: ROLE#x 属于 <appCode>`。
- **删除角色时清理授权行**:`RoleServiceImpl.delete` 在同一事务内逻辑删除该角色的
  授权记录,不再产生孤儿行。
- **回读名单自愈**:`GET /api/v1/admin/application-services/{id}/grants` 过滤已删除主体
  与越界的应用角色授权行;弹窗提交时这些行随整表覆盖自然清掉,存量脏数据不会继续挡住保存。
- **弹窗候选分组**:域角色与应用角色分开展示,其他应用的应用角色不进入候选。

### 20.3 验证

- 本地复现:建域角色 → 授权给应用 → 删除角色 → 回读名单仍含该 id → 保存报错;
  修复后同一路径保存成功,回读名单不含已删角色。
- 单测:`ApplicationVisibilityServiceTest` 覆盖"应用角色放行所属应用、不影响其他应用、
  进入受众回退";`ApplicationServiceGrantScopeTest` 覆盖本应用/跨应用授权、
  域角色放行与失效授权行过滤。
