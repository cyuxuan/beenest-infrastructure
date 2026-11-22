# SCIM 2.0 人员与组织同步

> 面向 HR / 目录系统。协议依据 RFC 7643(资源模型)与 RFC 7644(协议)。
> 实现位置:`beenest-iam-admin` 的 `club.beenest.iam.admin.scim` 包。

## 1. 为什么需要它

没有 SCIM 时,HR 系统要对接本平台只能写脚本直接改库或调管理端接口:
前者绕过全部校验,后者要把 `client_credentials` 令牌、分页、错误码逐家适配一遍。
SCIM 把这些收敛成一套标准报文,北森 / Workday / SuccessFactors / 钉钉 / 飞书都支持。

## 2. 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/scim/v2/Users` | 查询用户,支持 `filter` / `startIndex` / `count` |
| `POST` | `/scim/v2/Users` | 新建用户,返回 201 |
| `GET` | `/scim/v2/Users/{id}` | 查询单个用户,含所属组 |
| `PUT` | `/scim/v2/Users/{id}` | 整体替换(只更新报文给出的属性) |
| `PATCH` | `/scim/v2/Users/{id}` | 部分更新(PatchOp) |
| `DELETE` | `/scim/v2/Users/{id}` | 删除(逻辑删除),返回 204 |
| `GET` | `/scim/v2/Groups` | 查询组 |
| `POST` | `/scim/v2/Groups` | 新建组 |
| `GET` | `/scim/v2/Groups/{id}` | 查询单个组,含成员 |
| `PUT` | `/scim/v2/Groups/{id}` | 整体替换 |
| `PATCH` | `/scim/v2/Groups/{id}` | 部分更新 |
| `DELETE` | `/scim/v2/Groups/{id}` | 删除组(子组提升到上一级) |

请求与响应均为 `application/scim+json`(也接受 `application/json`)。

## 3. 鉴权与数据范围

- 用平台的 **Bearer 令牌**调用,令牌须携带权限码 `scim:manage`;
  令牌类型必须是 `MACHINE`(凭据模式)或 `PLATFORM`,业务应用令牌被拒绝。
- 数据范围 = 令牌所属身份域,报文里没有域参数,调用方也无法跨域。
- **建议**给 HR 集成单独建一个服务账号与角色,只授予 `scim:manage`,
  这样登录事件与审计日志里"系统同步"与"人工操作"能一眼区分。

获取令牌:

```bash
curl -s -X POST http://id.example.com/realms/default/oauth2/token \
  -u hr-sync:<secret> \
  -d 'grant_type=client_credentials&scope=openid'
```

## 4. 资源映射

### 4.1 User

| SCIM 属性 | 平台字段 | 说明 |
| --- | --- | --- |
| `id` | `iam_user.id` | 只读 |
| `userName` | `username` | 必填,域内唯一,创建后不可改 |
| `externalId` | `attributes.externalId` | HR 侧主键,用于反查 |
| `displayName` / `name.formatted` | `nickname` | 二者取其一,`displayName` 优先 |
| `emails[0].value` | `email` | 只取第一条 |
| `phoneNumbers[0].value` | `phone` | 只取第一条 |
| `active` | `status` | `true` → 1,`false` → 0 |
| `groups` | 组成员关系 | 只读,由任职推导 |
| `password` | 口令凭证 | 仅 `POST` 时生效;`PATCH` 修改口令被拒绝 |

`password` 不通过 PATCH 修改:重置口令要走口令策略校验与"必须改密"待办,
直接覆盖会绕过两者。需要重置时用管理端接口。

### 4.2 Group

| SCIM 属性 | 平台字段 | 说明 |
| --- | --- | --- |
| `id` | `iam_group.id` | 只读 |
| `externalId` | `iam_group.external_id` | 只读;建组时若提供则同时用作 `groupCode` |
| `displayName` | `group_name` | 必填 |
| `members[].value` | 成员用户主键 | 增删改成员 |
| `members[].display` | 昵称或登录名 | 只读 |

`groupCode`(域内唯一编码)是平台概念,SCIM 没有对应字段:建组时优先取 `externalId`,
缺失时生成 `scim-` 前缀的随机编码。**HR 系统应始终提供 `externalId`**,
否则同名组重复创建会得到两个编码不同的组。

组上的角色授权不通过 SCIM 变更——HR 一次全量同步不应改掉权限。

## 5. 过滤

支持单条件表达式,运算符 `eq`(等于)与 `co`(包含),值必须用双引号:

```text
filter=userName eq "alice"
filter=externalId eq "HR-0001"
filter=displayName co "研发"
```

可过滤属性:

| 资源 | 属性 | 下推方式 |
| --- | --- | --- |
| User | `userName` | 数据库模糊查 + 精确过滤 |
| User | `externalId` | 扫描最多 1000 条匹配(无独立索引) |
| User | `id` | 主键直查 |
| Group | `displayName` | 数据库模糊查 + 精确过滤 |
| Group | `externalId` | 扫描最多 1000 条匹配 |
| Group | `id` | 主键直查 |

多条件(`and` / `or`)与其它运算符返回 400 `invalidFilter`,不会静默忽略——
静默忽略会让"按条件更新"变成"更新了错误的记录"。

## 6. PATCH 支持范围

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
  "Operations": [
    { "op": "replace", "path": "active", "value": false },
    { "op": "replace", "path": "emails[type eq \"work\"].value", "value": "a@b.com" },
    { "op": "add", "path": "members", "value": [{ "value": "12" }] },
    { "op": "remove", "path": "members", "value": [{ "value": "13" }] }
  ]
}
```

| 资源 | 可 PATCH 路径 |
| --- | --- |
| User | `active`、`displayName`、`name.formatted`、`emails[...].value`、`phoneNumbers[...].value`、`externalId` |
| Group | `displayName`、`members`(`add` / `replace` / `remove`) |

`remove` 把字符串属性置空(与平台"空串即清除"口径一致)。
成员增删在**现有任职**基础上进行,已有成员的岗位与主部门标记原样保留。

## 7. 错误

错误体遵循 RFC 7644:

```json
{
  "schemas": ["urn:ietf:params:scim:api:messages:2.0:Error"],
  "status": "409",
  "scimType": "uniqueness",
  "detail": "登录名已存在: alice"
}
```

| HTTP | `scimType` | 场景 |
| --- | --- | --- |
| 400 | `invalidSyntax` | 报文非法、必填项缺失、`Operations` 为空 |
| 400 | `invalidFilter` | 过滤表达式或多条件不支持 |
| 400 | `invalidPath` | PATCH 路径不支持 |
| 400 | `invalidValue` | 取值非法(如成员主键非数字) |
| 401 | — | 未携带令牌或令牌无效 |
| 403 | — | 令牌缺少 `scim:manage`,或为业务应用令牌 |
| 404 | — | 资源不存在(含跨域访问,一律按不存在处理) |
| 409 | `uniqueness` | 登录名或组编码重复 |

## 8. 与平台口径的差异

| 项 | SCIM 规范 | 本平台 |
| --- | --- | --- |
| `PUT` 语义 | 整体替换,未给出的属性清空 | 只更新给出的属性,不清空(避免 HR 少传一个字段就清掉邮箱) |
| `DELETE` 语义 | 物理删除 | 逻辑删除,数据可追溯 |
| 组编码 | 无此概念 | 由 `externalId` 或随机值生成,创建后不可改 |
| 时间戳 | RFC 3339 UTC | 平台以本地时间存储,输出时按服务器时区换算为 UTC |

## 9. 已知边界

| 边界 | 说明 |
| --- | --- |
| `externalId` 无索引 | 按 `externalId` 查询最多扫描 1000 条;大规模场景建议先用 `userName` 定位 |
| 单组规模上限 | 成员读写单页上限 10000;超过该规模的组请用管理端维护 |
| 不支持 `bulk` | 批量操作请逐条调用,或由 HR 侧控制并发 |
| 不支持 `/Me`、`/ServiceProviderConfig`、`/Schemas` | 只提供 `Users` 与 `Groups` 两个资源 |
| 不支持组嵌套 | 平台组的层级只表达归属,SCIM 的 `members` 只含用户 |

## 10. 排障

| 现象 | 排查方向 |
| --- | --- |
| 401 | 令牌过期;或用了业务应用令牌(只接受 `MACHINE` / `PLATFORM`) |
| 403 | 服务账号的角色没有 `scim:manage` 权限码 |
| 400 `invalidFilter` | 用了 `and` / `or` 或 `sw` / `pr` 等未支持运算符 |
| 400 `invalidPath` | PATCH 路径大小写或写法不匹配,见第 6 节 |
| 409 `uniqueness` | `userName` 已存在;或建组时 `externalId` 与既有 `groupCode` 冲突 |
| 建的组编码很怪 | 未提供 `externalId`,平台生成了 `scim-xxxx` 随机编码 |
| 成员 PATCH 后岗位丢了 | 不应发生;若出现请检查是否走了 `PUT`(整体替换成员会重建任职) |
