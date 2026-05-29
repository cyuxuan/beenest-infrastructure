# Palantir 用户管理与应用授权设计

日期：2026-05-29

## 目标

在 CAS Palantir Dashboard 中实现：
1. 查看/管理 `cas_user` 表中的现有用户（禁用、启用、解锁）
2. 授权用户访问特定已注册服务（应用）

原则：尽可能使用 CAS 原生方案，最小化自定义代码。

## 背景

CAS 原生 Palantir（7.3.6）没有用户管理 tab。它有 15 个 tab 管理 Services、SSO Sessions、System 等，但没有查看/管理 `cas_user` 表的界面。

CAS 原生 `DefaultRegisteredServiceAccessStrategy` 内嵌 `requiredAttributes` 字段，可以通过 Principal 属性检查控制服务访问。这是"授权用户访问应用"的原生数据结构，不需要自定义表。

当前项目中 `BeenestAccessStrategy` 和 `AppAccessService` 是空壳/未完成实现，可以被原生方案替代。

## 方案选择

方案 B：原生为主 + 最小自定义。

- Services tab、SSO Sessions tab 等原生 tab 完全保留
- 新增 Account Management tab（自定义 Actuator Endpoint + 前端模板）
- 授权访问通过原生 `DefaultRegisteredServiceAccessStrategy.requiredAttributes` 实现
- 删除 `BeenestAccessStrategy` 和 `AppAccessService`（空壳）

## 架构

```
Palantir Dashboard
  ├── Services tab (原生) -- 编辑 Service JSON 的 accessStrategy
  ├── SSO Sessions tab (原生) -- 查看/踢在线用户
  ├── 新增: Account Management tab
  │     ├── 列出用户 (分页、搜索)
  │     ├── 查看用户详情
  │     ├── 禁用/启用用户
  │     ├── 解锁账号
  │     └── 重置密码 (跳转 CAS 原生 PM Webflow)
  ├── 新增: Service Authorization tab
  │     ├── 选择已注册服务
  │     ├── 添加/移除用户对该服务的访问权限
  │     └── 底层: PUT /actuator/registeredServices 更新 Service JSON
  │          + 更新 cas_user 表中的角色属性
  └── 其他原生 tabs (不变)
```

## 组件 1: Account Management Tab

### 后端: CasUsersEndpoint (Actuator Endpoint)

用 `@Endpoint` 注解，遵循 CAS Palantir 的 Actuator 模式。Palantir 自动收集所有 Actuator 端点 URL 到前端 `actuatorEndpoints` JS 对象，安全配置也自动生效（要求 ADMIN 角色）。

**端点路径**: `/actuator/casUsers`

**操作**:

| HTTP | 路径 | 描述 | cas_user SQL |
|------|------|------|-------------|
| GET | `/actuator/casUsers` | 列出用户 | `SELECT ... WHERE status != 4` 支持 `?query=xxx&status=1&page=0&size=20` |
| GET | `/actuator/casUsers/{userId}` | 获取用户详情 | `SELECT ... WHERE user_id = ? AND status != 4` |
| PUT | `/actuator/casUsers/{userId}` | 更新用户状态 | body: `{"status": 3}` 禁用, `{"status": 1}` 启用, `{"action": "unlock"}` 解锁 |

**重置密码**: 不走自定义端点。在 PUT 操作中支持 `{"mustChangePassword": true}`，管理员点击"重置密码"后：
1. 调用 `PUT /actuator/casUsers/{userId}` 设置 `mustChangePassword=true`
2. 前端弹出提示：下次该用户登录时，CAS 原生 Interrupt Webflow (`interrupt.groovy`) 会强制密码修改

### 前端: accounttab.html + beenest-account-mgmt.js

- 参考 Palantir 原生 `servicestab.html` 的 DataTable 风格
- 使用 `actuatorEndpoints.casUsers` 调用 API
- 用户列表展示：用户名、昵称、手机号、邮箱、状态、角色、最后登录时间
- 操作按钮：禁用、启用、解锁、重置密码（触发 mustChangePassword）

### UnifiedUserMapper 新增查询

- `selectAllPaged(query, status, offset, limit)` -- 分页查询，支持模糊搜索
- `countByQuery(query, status)` -- 分页总数

## 组件 2: Service Authorization Tab

### 授权机制: 原生 requiredAttributes

删除 `BeenestAccessStrategy`，改用 CAS 原生 `DefaultRegisteredServiceAccessStrategy`。

**Service JSON 示例** (`drone-system-10001.json`):

```json
{
  "accessStrategy": {
    "@class": "org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy",
    "enabled": true,
    "ssoEnabled": true,
    "unauthorizedRedirectUrl": "https://sso.beenest.club/cas/login",
    "requiredAttributes": {
      "memberOf": ["ROLE_DRONE_SYSTEM"]
    }
  }
}
```

**认证流程中赋予角色属性**:

在认证 Handler（`UsernamePasswordAuthenticationHandler` 等）中，从 `cas_user` 表读取用户的角色列表（基于 `user_type` + 被授权的服务），放入 Principal attributes 的 `memberOf` 字段。

角色映射规则:
- `user_type = CUSTOMER` + 未授权特定服务 -> `memberOf = ["ROLE_USER"]`
- `user_type = CUSTOMER` + 授权访问 drone-system -> `memberOf = ["ROLE_USER", "ROLE_DRONE_SYSTEM"]`
- `user_type = PILOT` + 授权访问 drone-system -> `memberOf = ["ROLE_PILOT", "ROLE_DRONE_SYSTEM"]`
- 管理员（Spring Security ADMIN） -> `memberOf = ["ROLE_ADMIN"]`

角色的存储：不使用独立表。在 `cas_user` 表的 `user_type` 字段基础上，结合 Service JSON 的 `requiredAttributes` 反推。具体做法：

1. 用户被授权访问某服务 -> 在 `cas_user` 表的某个字段（新增 `roles` VARCHAR 字段，或复用现有 `identity` 字段）记录额外角色
2. 认证时读取该字段 + `user_type`，合并为 `memberOf` 属性

**简化方案**：复用 `cas_user` 表现有的 `user_type` 字段 + 新增 `roles` 字段存储逗号分隔的额外角色。例如 `roles = "ROLE_DRONE_SYSTEM,ROLE_PAYMENT"`。

### 后端: ServiceAuthorizationEndpoint (Actuator Endpoint)

**端点路径**: `/actuator/serviceAuthorization`

**操作**:

| HTTP | 路径 | 描述 |
|------|------|------|
| GET | `/actuator/serviceAuthorization?serviceId=10001` | 列出有权限访问该服务的用户 |
| POST | `/actuator/serviceAuthorization` | 授权用户访问服务。body: `{"serviceId": 10001, "userId": "U1234", "role": "ROLE_DRONE_SYSTEM"}` |
| DELETE | `/actuator/serviceAuthorization?serviceId=10001&userId=U1234&role=ROLE_DRONE_SYSTEM` | 撤销用户服务访问 |

**POST 底层操作**:
1. `ServicesManager.findServiceBy(serviceId)` 获取 Service
2. 修改 `accessStrategy.requiredAttributes.memberOf` 添加 role
3. `ServicesManager.save(service)` 持久化到 JPA
4. 更新 `cas_user` 表的 `roles` 字段，追加 role
5. 返回更新后的 Service JSON

**DELETE 底层操作**:
1. 获取 Service
2. 从 `requiredAttributes.memberOf` 移除 role
3. `ServicesManager.save(service)`
4. 更新 `cas_user` 表的 `roles` 字段，移除 role
5. 返回更新后的 Service JSON

**GET 底层操作**:
1. 获取 Service 的 `requiredAttributes.memberOf` 角色列表
2. 从 `cas_user` 表查询所有 `roles` 包含这些角色的用户
3. 返回用户列表

### 前端: accessstrategytab-beenest.html + beenest-service-auth.js

- 简化的"授权用户访问应用"UI
- 左侧：已注册服务列表（从 `actuatorEndpoints.registeredservices` 获取）
- 右侧：选中服务后，显示已有权限的用户列表 + "添加用户"按钮
- "添加用户"：弹出搜索框，搜索用户后选择，点击授权
- "移除用户"：点击移除按钮，撤销访问权限

### 登录时自动赋权

新增 `beenest.auto-grant-service-ids` 配置项（已在 application.yml 中存在：`10001`）。

注册/首次登录时，`BeenestAccountRegistrationProvisioner` 和 `UserIdentityService` 自动为用户赋予 `auto-grant-service-ids` 对应的角色。例如 `10001` (drone-system) -> 自动赋予 `ROLE_DRONE_SYSTEM`。

移动端 OIDC 服务 (`10002`) 的 `accessStrategy` 不设置 `requiredAttributes`，所有已认证用户都可访问（保持现有行为）。

## 组件 3: 数据库变更

### cas_user 表新增字段

```sql
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS roles VARCHAR(255) DEFAULT NULL;
COMMENT ON COLUMN cas_user.roles IS '用户额外角色（逗号分隔），用于 Service accessStrategy requiredAttributes 匹配';
```

### UnifiedUserMapper 新增查询

```xml
<select id="selectAllPaged" resultType="org.apereo.cas.beenest.entity.UnifiedUserDO">
    SELECT <include refid="columns"/> FROM cas_user
    WHERE status != 4
    <if test="query != null and query != ''">
        AND (username LIKE '%' || #{query} || '%'
             OR nickname LIKE '%' || #{query} || '%'
             OR phone LIKE '%' || #{query} || '%'
             OR email LIKE '%' || #{query} || '%'
             OR user_id LIKE '%' || #{query} || '%')
    </if>
    <if test="status != null">
        AND status = #{status}
    </if>
    ORDER BY created_time DESC
    LIMIT #{limit} OFFSET #{offset}
</select>

<select id="countByQuery" resultType="long">
    SELECT COUNT(*) FROM cas_user
    WHERE status != 4
    <if test="query != null and query != ''">
        AND (username LIKE '%' || #{query} || '%'
             OR nickname LIKE '%' || #{query} || '%'
             OR phone LIKE '%' || #{query} || '%'
             OR email LIKE '%' || #{query} || '%'
             OR user_id LIKE '%' || #{query} || '%')
    </if>
    <if test="status != null">
        AND status = #{status}
    </if>
</select>

<select id="selectByRolesContaining" resultType="org.apereo.cas.beenest.entity.UnifiedUserDO">
    SELECT <include refid="columns"/> FROM cas_user
    WHERE status != 4 AND roles LIKE '%' || #{role} || '%'
</select>

<update id="updateRoles">
    UPDATE cas_user SET roles = #{roles}, updated_time = CURRENT_TIMESTAMP
    WHERE user_id = #{userId}
</update>
```

### UnifiedUserDO 新增字段

```java
private String roles;
```

## 组件 4: 删除空壳代码

| 文件 | 操作 |
|------|------|
| `BeenestAccessStrategy.java` | 删除，改用原生 `DefaultRegisteredServiceAccessStrategy` |
| `AppAccessService.java` | 删除（空壳已停用） |

## 组件 5: Service JSON 变更

每个需要显式授权的 Service 的 `accessStrategy` 从 `BeenestAccessStrategy` 改为 `DefaultRegisteredServiceAccessStrategy` + `requiredAttributes`。

### drone-system-10001.json (变更)

```json
{
  "@class": "org.apereo.cas.services.CasRegisteredService",
  "serviceId": "https://drone.beenest.com.*",
  "name": "drone-system",
  "id": 10001,
  "description": "无人机管理系统",
  "accessStrategy": {
    "@class": "org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy",
    "enabled": true,
    "ssoEnabled": true,
    "unauthorizedRedirectUrl": "https://sso.beenest.club/cas/login",
    "requiredAttributes": {
      "memberOf": ["ROLE_DRONE_SYSTEM"]
    }
  }
}
```

### beenest-palantir-10000.json (变更)

```json
{
  "accessStrategy": {
    "@class": "org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy",
    "enabled": true,
    "ssoEnabled": true,
    "requiredAttributes": {
      "memberOf": ["ROLE_ADMIN"]
    }
  }
}
```

### beenest-mobile-app-oidc-10002.json (不变)

移动端 OIDC 服务不设置 `requiredAttributes`，所有已认证用户可访问。保持现有 `DefaultRegisteredServiceAccessStrategy` (enabled + ssoEnabled only)。

### beenest-payment-10003.json (变更)

```json
{
  "accessStrategy": {
    "@class": "org.apereo.cas.services.DefaultRegisteredServiceAccessStrategy",
    "enabled": true,
    "ssoEnabled": true,
    "requiredAttributes": {
      "memberOf": ["ROLE_PAYMENT"]
    }
  }
}
```

## 组件 6: 认证 Handler 变更

在所有认证 Handler（UsernamePassword、WechatMini、DouyinMini、AlipayMini、SmsOtp）中，认证成功后从 `cas_user` 表读取 `roles` 字段，合并 `user_type` 对应的基础角色，放入 Principal attributes 的 `memberOf`。

基础角色映射:
- `user_type = CUSTOMER` -> `ROLE_USER`
- `user_type = PILOT` -> `ROLE_PILOT`
- `user_type = ADMIN` -> `ROLE_ADMIN`

合并逻辑: `memberOf = [基础角色] + roles.split(",")`

## 配置变更

### application.yml

```yaml
management:
  endpoints:
    web:
      exposure:
        include: ...casUsers,serviceAuthorization  # 在现有列表末尾追加
```

### beenest.auto-grant 配置

```yaml
beenest:
  user:
    auto-grant-service-ids: ${BEENEST_AUTO_GRANT_SERVICE_IDS:10001}
    # 新增: 自动赋予的角色映射
    auto-grant-roles:
      10001: ROLE_DRONE_SYSTEM
      10003: ROLE_PAYMENT
```

## 新增/修改文件清单

### 新增

| 文件路径 | 描述 |
|----------|------|
| `src/main/java/org/apereo/cas/beenest/endpoint/CasUsersEndpoint.java` | Actuator 端点，查看/管理 cas_user |
| `src/main/java/org/apereo/cas/beenest/endpoint/ServiceAuthorizationEndpoint.java` | Actuator 端点，授权/撤销服务访问 |
| `src/main/resources/templates/fragments/palantir/accounttab.html` | Palantir 用户管理 tab 模板 |
| `src/main/resources/templates/fragments/palantir/accessauthorizationtab.html` | Palantir 服务授权 tab 模板 |
| `src/main/resources/static/js/beenest-account-mgmt.js` | 用户管理 tab 前端交互 |
| `src/main/resources/static/js/beenest-service-auth.js` | 服务授权 tab 前端交互 |

### 修改

| 文件路径 | 修改内容 |
|----------|---------|
| `BeenestAccessStrategy.java` | **删除** |
| `AppAccessService.java` | **删除** |
| `BeenestServiceConfiguration.java` | 移除空壳 Bean，注册新 Endpoint |
| `BeenestNativeEndpointSecurityConfig.java` | 添加 `casUsers` 和 `serviceAuthorization` 端点安全配置 |
| `application.yml` | `exposure.include` 追加 `casUsers,serviceAuthorization`；添加 `auto-grant-roles` |
| `casPalantirDashboardView.html` | 添加 Account tab 和授权 tab fragment |
| `navigationsidebar.html` | 添加 Account 和授权导航项 |
| `dashboardtabs.html` | 添加 Account 和授权 tab 按钮 |
| `drone-system-10001.json` | accessStrategy 改为 `DefaultRegisteredServiceAccessStrategy` + `requiredAttributes` |
| `beenest-palantir-10000.json` | accessStrategy 同上，`requiredAttributes.memberOf = ["ROLE_ADMIN"]` |
| `beenest-payment-10003.json` | accessStrategy 同上，`requiredAttributes.memberOf = ["ROLE_PAYMENT"]` |
| `BeenestAccountRegistrationProvisioner.java` | 注册时赋予默认角色 + auto-grant 角色 |
| `UserIdentityService.java` | 小程序注册时赋予 auto-grant 角色 |
| `UsernamePasswordAuthenticationHandler.java` | 认证时从 cas_user 读取 roles + user_type，放入 memberOf |
| `WechatMiniAuthenticationHandler.java` | 同上 |
| `DouyinMiniAuthenticationHandler.java` | 同上 |
| `AlipayMiniAuthenticationHandler.java` | 同上 |
| `SmsOtpAuthenticationHandler.java` | 同上 |
| `UnifiedUserDO.java` | 新增 `roles` 字段 |
| `UnifiedUserMapper.java` | 新增 `selectAllPaged`, `countByQuery`, `selectByRolesContaining`, `updateRoles` |
| `UnifiedUserMapper.xml` | 同上 |
| `init-cas-schema.sql` | 新增 `roles` 列 |
| `CasOverlayOverrideConfiguration.java` | 注册新 Endpoint Bean |

### 删除

| 文件路径 | 原因 |
|----------|------|
| `BeenestAccessStrategy.java` | 改用原生 `DefaultRegisteredServiceAccessStrategy` |
| `AppAccessService.java` | 空壳，逻辑由原生 Service JSON + 新 Endpoint 替代 |

## 不变部分

- Palantir Services tab (原生 Ace Editor 编辑 Service JSON)
- Palantir SSO Sessions tab (原生查看/踢用户)
- Palantir 其他 13 个原生 tabs
- CAS Account Registration Webflow (注册流程)
- CAS Password Management Webflow (密码重置)
- CAS Interrupt Webflow (mustChangePassword 检查)

## 验证计划

1. **用户管理**: 管理员登录 Palantir -> Account tab -> 搜索用户 -> 禁用/启用/解锁 -> 验证 cas_user 表状态变更
2. **授权访问**: 管理员登录 Palantir -> Service Authorization tab -> 选择 drone-system -> 添加用户 -> 验证 Service JSON requiredAttributes 更新 + cas_user.roles 更新
3. **访问拒绝**: 未授权用户尝试访问 drone.beenest.com -> 验证 CAS 返回拒绝页面或跳转 unauthorizedRedirectUrl
4. **访问允许**: 授权用户访问 drone.beenest.com -> 验证 CAS 正常颁发 ST
5. **自动赋权**: 新注册用户 -> 验证 cas_user.roles 包含 auto-grant 角色
6. **重置密码**: 管理员设置 mustChangePassword -> 用户下次登录 -> 验证 Interrupt Webflow 强制密码修改