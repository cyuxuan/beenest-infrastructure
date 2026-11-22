# 组织架构与岗位

本文说明 beenest IAM 的组织模型(部门 / 用户组 / 岗位 / 任职)、它与权限公式的关系、
对外暴露的接口与令牌声明,以及迁移与验证方式。面向二次开发、实施与评审。

## 1. 为什么是"一棵树 + 一本岗位字典"

企业侧的组织诉求通常是三件事:

1. **人属于哪个部门**(审批路由、数据归属、花名册);
2. **人在部门里担什么职务**(职务决定他能干什么);
3. **跨部门的虚拟集合**(项目组、临时工作组、权限组)。

很多系统把它们拆成"部门树 + 用户组"两套结构,结果是**同一批人要在两处维护**,
时间一长必然漂移(部门里已离职的人还挂在组里、组里的成员与花名册对不上)。

本平台的取舍是:

| 概念 | 载体 | 说明 |
|---|---|---|
| 部门 | `iam_group`(`group_type=DEPARTMENT`) | 与用户组**共用一棵树**,不另起表 |
| 用户组 | `iam_group`(`group_type=GROUP`) | 虚拟集合,可跨部门拉人 |
| 岗位 | `iam_position` | 人事语义的职务字典(域内唯一编码) |
| 任职 | `iam_user_group` | **一条关系即一份任职**:部门 + 岗位 + 是否主部门 |

这样部门与组天然同构(可以互相挂父子、可以互相包含),而"职务"单独建模,
不挤在角色里——**岗位是人事口径,角色是权限口径**,两者靠 `iam_position_role` 连接。
同一个人可以在 A 部门任"部门经理"、在 B 部门任"兼职架构师",两行任职各自生效。

## 2. 数据模型

```
iam_group                      部门与用户组(共用树)
  group_type        DEPARTMENT | GROUP,非空默认 GROUP(CHECK 约束)
  leader_user_id    部门负责人(可空,清空需走 updateLeaderUserId)
  sort_order        排序号
  external_id       外部系统(HR/AD)对齐用

iam_user_group                 任职
  position_id       任职岗位(可空)
  is_primary        是否主部门
  UNIQUE INDEX uk_iam_user_group_primary(user_id) WHERE deleted = FALSE AND is_primary

iam_position                   岗位字典
  position_code     域内唯一
  position_level    职级
  sort_order        排序号

iam_position_role              岗位 → 角色(默认角色)
  position_id / role_id
```

**主部门唯一性由数据库兜底**:`uk_iam_user_group_primary` 是部分索引(partial unique index),
只对未软删且 `is_primary` 的行生效。它是**非延迟约束**,按语句即时校验,
因此服务层改主部门时必须**先 `clearPrimaryForUser` 再写入新行**,顺序颠倒会撞约束。

## 3. 权限链路

岗位不直接产生权限,它经"任职"把角色带进有效角色集合:

```
有效角色 =(角色-菜单派生 ∪ 角色直接 ∪ 组角色 ∪ 岗位角色 ∪ 用户 GRANT)− 用户 REVOKE
                                          ↑
                              用户任职岗位 → iam_position_role
```

- 解析入口:`IamEffectiveRoleResolver`(新增 `positionIds` 来源);
- 岗位角色与组角色**等价**,不区分优先级;
- 改岗位角色 = 批量改一批人的权限,故 `IamPositionManager.assignRoles` 会抬升
  权限缓存代次 `IamPermissionEpoch.bump(realmId)`;
- 管理端「用户 → 有效权限」里能看到岗位来源:`EffectiveRoleResponse.positions`
  列出该角色是由哪些岗位带进来的(前端显示为"组 X + 岗位 Y")。

**层级不参与授权继承**:子部门不继承父部门的角色。这是刻意的设计——
组织层级用于取数与展示,授权只认显式的组角色/岗位角色,否则"挂到顶层部门即拿全权限"
会成为权限逃逸口。

## 4. 令牌声明

`IamJwtTokenCustomizer.applyOrganizationClaims` 在 access token 中输出:

| 声明 | 取值 | 说明 |
|---|---|---|
| `dept` | 主部门编码 | 只输出主部门;兼职部门已在 `groups` 全量给出 |
| `dept_name` | 主部门名称 | 业务侧免回查 |
| `positions` | 全部任职岗位编码(排序) | 含兼职岗位 |

约束:

- **跨域一律忽略**:任职行、部门实体、岗位实体都要与主体 `realm_id` 复核一次。
  令牌里的部门编码会被业务系统当作归属依据,串域就是越权取数的入口。
- 客户端**未绑定应用服务**时按收敛策略只输出身份声明(不输出 `dept`),
  这是 `resolveApplication` 的默认拒绝设计,不是缺陷。

## 5. 管理端接口

### 岗位(`/api/v1/admin/positions`)

| 方法 | 路径 | 权限码 |
|---|---|---|
| GET | `/api/v1/admin/positions` | `position:view` |
| GET | `/{id}` | `position:view` |
| POST | `/` | `position:create` |
| PUT | `/{id}`(编码不可改) | `position:update` |
| DELETE | `/{id}` | `position:delete` |
| GET | `/{id}/roles` | `position:view` |
| PUT | `/{id}/roles`(覆盖式) | `position:assign-role` |

删除岗位只清理岗位角色关联,**保留任职记录**(避免人事历史被连带抹掉)。

### 组织(`/api/v1/admin/groups`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/tree` | 组织树,节点含 `groupType`、`leaderName` |
| GET | `/{id}/members?includeSub=true` | 含下级部门成员(递归 CTE,按人去重) |
| PUT | `/{id}/members` | 覆盖式任职:`members[{userId,positionId,primary}]` |
| GET | `/{id}/member-ids` | 覆盖式动作前必须取完整已选集合 |

### 用户组织归属

`GET /api/v1/admin/users/{id}/organizations` → `UserOrganizationResponse[]`
(`groupId / groupCode / groupName / groupType / positionId / positionCode / positionName / primary`),
主部门排序在前。

## 6. 控制台

- **组织架构**(原「用户组」):类型、负责人、排序列;表单可选部门/组与负责人;
  成员抽屉按"任职"编辑(岗位下拉 + 主部门单选 + 移出),并支持"含下级"只读视图。
- **岗位**:岗位 CRUD + 岗位角色分配(复用 `AssignmentModal`)。
- **用户**:新增「组织」行动作,抽屉里看该用户的全部部门/岗位与主部门标记;
  有效权限诊断里岗位来源显示为"组 X + 岗位 Y"。

权限码驱动菜单裁剪:未持有 `position:view` 的账号看不到岗位入口。

## 7. 迁移与回滚

- 正向:`V1.2.0__organization_position.sql`(组类型/负责人/排序/外部 ID、任职列、
  岗位与岗位角色表、主部门部分唯一索引、`position:*` 权限码补种给 SUPER_ADMIN)。
- 回滚:`U1.2.0__drop_organization_position.sql`(逆序:撤权限码 → 删岗位表 →
  撤任职列 → 撤组类型列)。

## 8. 验证清单

单元/集成:

- `IamEffectiveRoleResolverTest#includesPositionRolesWithSources`——岗位角色进入有效角色并标注来源;
- `IamJwtTokenCustomizerTest#emitsPrimaryDepartmentAndPositions`、`skipsCrossRealmOrganization`;
- `IamOrganizationIntegrationTest`——任职继承岗位角色、主部门至多一个、含下级成员不重复;
- `PositionServiceImplTest`——编码唯一、跨域岗位/角色拦截、覆盖式分配。

运行时(需登录态;管理端写请求须回传 `X-XSRF-TOKEN`):

1. 建岗位 → 建部门(DEPARTMENT)→ 建子部门;
2. 建角色挂到岗位 → 回读岗位角色;
3. 任职(岗位 + 主部门)→ `users/{id}/organizations` 显示主部门与岗位;
4. `groups/{id}/members?includeSub=true` 含下级不重复;
5. `users/{id}/effective-permissions` 的角色带 `positions` 来源;
6. 换主部门:新部门 `primary=true`、旧部门自动 `primary=false`;
7. OIDC 授权码(PKCE)取令牌,access token 含 `dept` / `dept_name` / `positions`,
   `realm_roles` 含岗位挂载的角色。
