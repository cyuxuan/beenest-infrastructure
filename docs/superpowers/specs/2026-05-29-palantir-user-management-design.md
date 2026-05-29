# Palantir 用户管理与应用授权设计

日期：2026-05-29（v2 更新）

## 目标

在 CAS Palantir Dashboard 中实现：
1. 管理员添加用户，利用 CAS 原生 Account Management Webflow 发送开通链接
2. 查看/管理 `cas_user` 表中的现有用户（禁用、启用、解锁）
3. 管理应用访问权限：添加/移除某用户对某应用的访问，查看某应用的所有可访问用户

原则：尽可能使用 CAS 原生方案，最小化自定义代码。

## 背景

CAS 原生 Palantir（7.3.6）没有用户管理 tab。它有 15 个 tab 管理 Services、SSO Sessions、System 等，但没有查看/管理 `cas_user` 表的界面。

CAS 原生 `DefaultRegisteredServiceAccessStrategy.requiredAttributes` 内嵌属性检查机制，可以通过 Principal 属性控制服务访问。配合"每应用一个角色"的模式，可以实现精确的用户级授权管理。

当前项目中 `BeenestAccessStrategy` 和 `AppAccessService` 是空壳/未完成实现，可以被原生方案替代。

## 方案选择

方案 B：原生为主 + 最小自定义。

- Services tab、SSO Sessions tab 等原生 tab 完全保留
- 新增 Account Management tab（自定义 Actuator Endpoint + 前端模板）
- 授权访问通过原生 `DefaultRegisteredServiceAccessStrategy.requiredAttributes` + 每应用独立角色实现
- 删除 `BeenestAccessStrategy` 和 `AppAccessService`（空壳）

## 架构

```
Palantir Dashboard
  ├── Services tab (原生) -- 编辑 Service JSON 的 accessStrategy
  ├── SSO Sessions tab (原生) -- 查看/踢在线用户
  ├── 新增: Account Management tab
  │     ├── 列出用户 (分页、搜索)
  │     ├── 查看用户详情
  │     ├── 管理员添加用户 (调用原生 Account Registration Webflow 发送开通链接)
  │     ├── 禁用/启用用户
  │     ├── 解锁账号
  │     └── 重置密码 (设置 mustChangePassword)
  ├── 新增: Service Authorization tab
  │     ├── 查看所有已注册应用及其角色要求
  │     ├── 添加用户对某应用的访问权限
  │     ├── 移除用户对某应用的访问权限
  │     ├── 查看某应用的所有可访问用户
  │     └── 底层: 操作 cas_user.roles + Service JSON requiredAttributes
  └── 其他原生 tabs (不变)
```

## 组件 1: Account Management Tab

### 1.1 管理员添加用户 — 利用原生 Account Management Webflow

CAS 原生 Account Management 提供完整的注册流程：

```
管理员在 Palantir 中点击"添加用户"
  → 调用 CasUsersEndpoint.createInvitation()
  → 后端调用 AccountRegistrationService.createToken()
    → 生成 JWT 注册令牌 (包含用户名/邮箱/手机号)
    → 调用 CAS 原生 CommunicationsManager 发送邮件/SMS
  → 用户收到邮件/SMS 中的开通链接
  → 用户点击链接 → CAS 原生 Webflow 验证令牌
  → FinalizeAccountRegistrationAction → BeenestAccountRegistrationProvisioner.provision() 落库
```

**关键代码路径**（CAS 原生，无需自定义）：
- `AccountRegistrationService.createToken()` — 验证请求 + 生成 JWT
- `CommunicationsManager.notify()` — 发送邮件/SMS（CAS 原生通知模块）
- `ValidateAccountRegistrationTokenAction` — 验证令牌签名和过期
- `FinalizeAccountRegistrationAction` — 调用 Provisioner 落库

**自定义部分**（仅 `CasUsersEndpoint` 中的 1 个方法）：

```java
@WriteOperation
public Map<String, Object> createInvitation(
    @Selector String username,
    String email,
    String phone,
    String roles) {

    // 1. 构造 AccountRegistrationRequest（CAS 原生类）
    var request = new AccountRegistrationRequest();
    request.put("username", username);
    request.put("email", email);
    request.put("phone", phone);
    request.put("roles", roles); // 自定义字段，写入 cas_user.roles

    // 2. 调用 CAS 原生注册服务（生成 token + 发送邮件）
    var response = accountRegistrationService.createToken(request);

    // 3. 返回 token 信息（用于管理端显示链接）
    return Map.of(
        "tokenId", response.getTokenId(),
        "message", "开通链接已发送至 " + email
    );
}
```

**不需要修改的部分**：
- `SubmitAccountRegistrationAction` — 原生 Webflow 中用户自助注册使用，管理员添加用户走 Endpoint 旁路
- `ValidateAccountRegistrationTokenAction` — 用户点击链接后验证 JWT，完全原生
- `FinalizeAccountRegistrationAction` — 调用已有的 `BeenestAccountRegistrationProvisioner` 落库
- 邮件/SMS 模板 — CAS 原生 `account-management` 模块已自带开通邮件模板

**开通链接格式**（CAS 原生）：
```
https://sso.beenest.club/cas/acct-mgmt/verify?token=<jwt-token>
```

用户点击后：
1. CAS 验证 JWT 签名和过期时间
2. 展示注册确认页面（用户设置密码）
3. 调用 `BeenestAccountRegistrationProvisioner.provision()` 创建用户

### 1.2 用户查看/管理 — CasUsersEndpoint (Actuator Endpoint)

用 `@Endpoint` 注解，遵循 CAS Palantir 的 Actuator 模式。Palantir 自动收集所有 Actuator 端点 URL 到前端 `actuatorEndpoints` JS 对象，安全配置也自动生效（要求 ADMIN 角色）。

**端点路径**: `/actuator/casUsers`

**操作**:

| HTTP | 路径 | 描述 |
|------|------|------|
| GET | `/actuator/casUsers` | 列出用户。`?query=xxx&status=1&page=0&size=20` |
| GET | `/actuator/casUsers/{userId}` | 获取用户详情 |
| POST | `/actuator/casUsers` | 管理员添加用户（创建邀请）。body: `{"username":"...","email":"...","phone":"...","roles":"..."}` |
| PUT | `/actuator/casUsers/{userId}` | 更新用户状态。body: `{"status":3}` 禁用, `{"status":1}` 启用, `{"action":"unlock"}` 解锁, `{"mustChangePassword":true}` 强制改密 |

### 1.3 前端: accounttab.html + beenest-account-mgmt.js

- 参考 Palantir 原生 `servicestab.html` 的 DataTable 风格
- 使用 `actuatorEndpoints.casUsers` 调用 API
- 用户列表展示：用户名、昵称、手机号、邮箱、状态、角色、最后登录时间
- 操作按钮：禁用、启用、解锁、重置密码
- "添加用户"按钮：弹出对话框，填写用户名/邮箱/手机号/角色 → 调用 POST → 显示"开通链接已发送"

## 组件 2: Service Authorization Tab

### 2.1 授权机制 — 原生 requiredAttributes + 每应用独立角色

**核心思路**：每个需要授权管理的应用对应一个独立角色。用户在 `cas_user.roles` 中存储逗号分隔的角色列表。CAS 原生 `requiredAttributes` 检查用户是否拥有该角色。

**为什么角色方案足够**：

| 需求 | 角色方案如何满足 |
|------|----------------|
| 添加某用户对某应用的访问 | 在 `cas_user.roles` 中追加该应用的角色（如 `ROLE_DRONE_SYSTEM`） |
| 移除某用户对某应用的访问 | 从 `cas_user.roles` 中删除该应用的角色 |
| 查看某应用的所有可访问用户 | 查询 `cas_user WHERE roles LIKE '%ROLE_DRONE_SYSTEM%'` |
| 用户登录时自动鉴权 | 认证 Handler 读取 `cas_user.roles` 放入 Principal `memberOf` 属性 |
| 新应用接入 | 注册新 Service JSON 时配置 `requiredAttributes.memberOf = ["ROLE_新应用"]` |

**角色与应用映射**（存储在 Service JSON 的 `requiredAttributes` 中，天然与 Service 绑定）：

| 应用 | Service ID | 角色 |
|------|-----------|------|
| 无人机管理系统 (drone-system) | 10001 | `ROLE_DRONE_SYSTEM` |
| Palantir 管理后台 | 10000 | `ROLE_ADMIN` |
| 移动端 (OIDC) | 10002 | 无（所有已认证用户可访问） |
| 支付服务 (payment) | 10003 | `ROLE_PAYMENT` |
| 未来新应用 | 自增 | `ROLE_新应用名` |

**Service JSON 示例** (drone-system-10001.json)：

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

### 2.2 后端: ServiceAuthorizationEndpoint (Actuator Endpoint)

**端点路径**: `/actuator/serviceAuthorization`

**操作**:

| HTTP | 路径 | 描述 |
|------|------|------|
| GET | `/actuator/serviceAuthorization` | 列出所有应用及其角色要求（从 ServicesManager 读取） |
| GET | `/actuator/serviceAuthorization/{serviceId}` | 列出有权限访问该服务的用户（查 `cas_user WHERE roles LIKE '%ROLE_XXX%'`） |
| POST | `/actuator/serviceAuthorization/{serviceId}` | 授权用户访问服务。body: `{"userId":"U1234"}` |
| DELETE | `/actuator/serviceAuthorization/{serviceId}?userId=U1234` | 撤销用户服务访问 |

**POST 底层操作**（添加用户访问某应用）：
1. `ServicesManager.findServiceBy(serviceId)` → 获取 Service → 提取 `requiredAttributes.memberOf` 中的角色名
2. 更新 `cas_user` 表：在 `roles` 字段中追加该角色
3. 返回更新后的用户信息

**DELETE 底层操作**（移除用户访问某应用）：
1. 获取 Service → 提取角色名
2. 更新 `cas_user` 表：从 `roles` 字段中移除该角色
3. 返回更新后的用户信息

**GET 底层操作**（查看某应用的所有可访问用户）：
1. 获取 Service → 提取角色名
2. 查询 `cas_user WHERE roles LIKE '%ROLE_XXX%' AND status != 4`
3. 返回用户列表

### 2.3 前端: authorizationtab.html + beenest-service-auth.js

- 左侧：已注册应用列表（从 `actuatorEndpoints.registeredservices` 获取），每个应用显示角色要求
- 右侧：选中应用后，显示已有访问权限的用户列表 + 操作按钮
- "添加用户"按钮：弹出搜索框，搜索用户后选择，点击授权
- "移除用户"按钮：确认后撤销访问权限
- 底层数据存储：`cas_user.roles` 字段（不在 Service JSON 中存储用户列表，反向查询更灵活）

### 2.4 登录时自动赋权

在所有认证 Handler 中，认证成功后从 `cas_user` 表读取 `roles` 字段，合并 `user_type` 对应的基础角色，放入 Principal attributes 的 `memberOf`。

基础角色映射：
- `user_type = CUSTOMER` → `ROLE_USER`
- `user_type = PILOT` → `ROLE_PILOT`
- `user_type = ADMIN` → `ROLE_ADMIN`

合并逻辑：`memberOf = [基础角色] + roles.split(",")`

自动赋权（注册时）：
- 新注册用户自动赋予 `auto-grant-service-ids` 对应的角色
- 例如 `10001` (drone-system) → 自动赋予 `ROLE_DRONE_SYSTEM`

## 组件 3: 数据库变更

### cas_user 表新增字段

```sql
ALTER TABLE cas_user ADD COLUMN IF NOT EXISTS roles VARCHAR(500) DEFAULT NULL;
COMMENT ON COLUMN cas_user.roles IS '用户应用角色（逗号分隔），用于 Service accessStrategy requiredAttributes 匹配';
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

<select id="selectByRole" resultType="org.apereo.cas.beenest.entity.UnifiedUserDO">
    SELECT <include refid="columns"/> FROM cas_user
    WHERE status != 4 AND roles LIKE '%' || #{role} || '%'
    ORDER BY created_time DESC
</select>

<update id="addRole">
    UPDATE cas_user SET
        roles = CASE WHEN roles IS NULL OR roles = '' THEN #{role}
                     ELSE roles || ',' || #{role} END,
        updated_time = CURRENT_TIMESTAMP
    WHERE user_id = #{userId} AND (roles IS NULL OR roles NOT LIKE '%' || #{role} || '%')
</update>

<update id="removeRole">
    UPDATE cas_user SET
        roles = TRIM(BOTH ',' FROM
            REPLACE(REPLACE(',' || roles || ',', ',' || #{role} || ',', ','), ',,', ',')),
        updated_time = CURRENT_TIMESTAMP
    WHERE user_id = #{userId}
</update>
```

### UnifiedUserDO 新增字段

```java
private String roles;
```

## 组件 4: 删除空壳代码

| 文件 | 操作 | 原因 |
|------|------|------|
| `BeenestAccessStrategy.java` | 删除 | 改用原生 `DefaultRegisteredServiceAccessStrategy` |
| `AppAccessService.java` | 删除 | 空壳，逻辑由原生 requiredAttributes + cas_user.roles 替代 |

## 组件 5: Service JSON 变更

每个需要显式授权的 Service 的 `accessStrategy` 从 `BeenestAccessStrategy` 改为 `DefaultRegisteredServiceAccessStrategy` + `requiredAttributes`。

### drone-system-10001.json

```json
{
  "@class": "org.apereo.cas.services.CasRegisteredService",
  "serviceId": "https://drone.beenest.com.*",
  "name": "drone-system",
  "id": 10001,
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

### beenest-palantir-10000.json

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

### beenest-mobile-app-oidc-10002.json

不变。移动端 OIDC 服务不设置 `requiredAttributes`，所有已认证用户可访问。

### beenest-payment-10003.json

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

在所有认证 Handler 中，认证成功后从 `cas_user` 表读取 `roles` 字段，合并 `user_type` 对应的基础角色，放入 Principal attributes 的 `memberOf`。

统一提取到工具方法 `BeenestPrincipalAttributesBuilder.buildAttributes(UnifiedUserDO user)`：

```java
public class BeenestPrincipalAttributesBuilder {
    public static Map<String, List<Object>> buildAttributes(UnifiedUserDO user) {
        var memberOf = new ArrayList<String>();
        // 基础角色
        switch (user.getUserType()) {
            case "ADMIN"   -> memberOf.add("ROLE_ADMIN");
            case "PILOT"   -> memberOf.add("ROLE_PILOT");
            case "CUSTOMER" -> memberOf.add("ROLE_USER");
        }
        // 应用角色
        if (user.getRoles() != null && !user.getRoles().isBlank()) {
            for (String role : user.getRoles().split(",")) {
                String trimmed = role.trim();
                if (!trimmed.isEmpty() && !memberOf.contains(trimmed)) {
                    memberOf.add(trimmed);
                }
            }
        }
        return Map.of("memberOf", List.copyOf(memberOf));
    }
}
```

## 组件 7: 添加用户时发送开通链接

### 后端: CasUsersEndpoint.createInvitation()

调用 CAS 原生 `AccountRegistrationService.createToken()`，传入用户信息生成 JWT 注册令牌，CAS 原生 `CommunicationsManager` 自动发送开通邮件。

```java
@WriteOperation
public Map<String, Object> createInvitation(
    @Selector String username,
    String email,
    String phone,
    String roles) {

    // 1. 构造 CAS 原生 AccountRegistrationRequest
    var request = new AccountRegistrationRequest();
    request.put("username", username);
    request.put("email", email);
    request.put("phone", phone);
    request.put("roles", roles);

    // 2. 调用原生注册服务（生成 JWT + 发送邮件/SMS）
    var response = accountRegistrationService.createToken(request);

    // 3. 返回 token 信息
    return Map.of(
        "tokenId", response.getTokenId(),
        "message", "开通链接已发送至 " + email
    );
}
```

### 前端: 添加用户对话框

1. 管理员填写用户名、邮箱、手机号、角色选择
2. 点击"发送开通链接"
3. 后端调用 `AccountRegistrationService.createToken()` + 发送邮件
4. 前端显示"开通链接已发送"
5. 用户收到邮件，点击链接设置密码完成注册
6. `BeenestAccountRegistrationProvisioner.provision()` 自动创建用户并赋予角色

## 配置变更

### 邮件发送配置（QQ 邮箱 SMTP）

CAS 原生 `CommunicationsManager` 通过 Spring Boot `spring.mail` 配置发送邮件。

**application.yml**（敏感信息通过环境变量注入）：

```yaml
spring:
  mail:
    host: smtp.qq.com
    port: 465
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    protocol: smtp
    properties:
      mail:
        smtp:
          auth: true
          ssl:
            enable: true
          socketFactory:
            class: javax.net.ssl.SSLSocketFactory
            port: 465

cas:
  notifications:
    core:
      email:
        from: ${MAIL_USERNAME:}
        subject: "[Beenest] 账号开通通知"
      sms:
        enabled: false  # 暂不启用 SMS 通知，使用阿里云短信单独配置
```

**.env**（本地开发，不提交到 git）：

```
MAIL_USERNAME=你的QQ邮箱@qq.com
MAIL_PASSWORD=hzfrvhrcbotdbgcd
```

### application.yml

```yaml
management:
  endpoints:
    web:
      exposure:
        include: ...casUsers,serviceAuthorization  # 在现有列表末尾追加

cas:
  acct-mgmt:
    registration:
      core:
        account-registration-properties-location: classpath:/account-registration-properties/registration-properties.json

beenest:
  user:
    auto-grant-service-ids: ${BEENEST_AUTO_GRANT_SERVICE_IDS:10001}
    auto-grant-roles:
      10001: ROLE_DRONE_SYSTEM
      10003: ROLE_PAYMENT
```

## 新增/修改文件清单

### 新增

| 文件路径 | 描述 |
|----------|------|
| `src/main/java/org/apereo/cas/beenest/endpoint/CasUsersEndpoint.java` | Actuator 端点，查看/管理 cas_user + 创建邀请 |
| `src/main/java/org/apereo/cas/beenest/endpoint/ServiceAuthorizationEndpoint.java` | Actuator 端点，授权/撤销/查看服务访问 |
| `src/main/java/org/apereo/cas/beenest/service/BeenestPrincipalAttributesBuilder.java` | 认证属性构建工具类 |
| `src/main/resources/templates/fragments/palantir/accounttab.html` | Palantir 用户管理 tab 模板 |
| `src/main/resources/templates/fragments/palantir/authorizationtab.html` | Palantir 服务授权 tab 模板 |
| `src/main/resources/static/js/beenest-account-mgmt.js` | 用户管理 tab 前端交互 |
| `src/main/resources/static/js/beenest-service-auth.js` | 服务授权 tab 前端交互 |

### 修改

| 文件路径 | 修改内容 |
|----------|---------|
| `BeenestAccessStrategy.java` | **删除** |
| `AppAccessService.java` | **删除** |
| `BeenestServiceConfiguration.java` 或 `CasOverlayOverrideConfiguration.java` | 移除空壳 Bean，注册新 Endpoint Bean |
| `application.yml` | `exposure.include` 追加 `casUsers,serviceAuthorization`；添加 `auto-grant-roles`；添加 `spring.mail` + `cas.notifications` 邮件配置 |
| `.env` | 追加 `MAIL_USERNAME` 和 `MAIL_PASSWORD` 环境变量 |
| `casPalantirDashboardView.html` | 添加 Account tab 和 Authorization tab fragment |
| `navigationsidebar.html` | 添加 Account 和 Authorization 导航项 |
| `dashboardtabs.html` | 添加 Account 和 Authorization tab 按钮 |
| `drone-system-10001.json` | accessStrategy 改为 `DefaultRegisteredServiceAccessStrategy` + `requiredAttributes` |
| `beenest-palantir-10000.json` | accessStrategy 同上 |
| `beenest-payment-10003.json` | accessStrategy 同上 |
| `BeenestAccountRegistrationProvisioner.java` | 注册时赋予 auto-grant 角色 |
| `UserIdentityService.java` | 小程序注册时赋予 auto-grant 角色 |
| 认证 Handlers (5个) | 认证时调用 `BeenestPrincipalAttributesBuilder.buildAttributes()` |
| `UnifiedUserDO.java` | 新增 `roles` 字段 |
| `UnifiedUserMapper.java` | 新增 `selectAllPaged`, `countByQuery`, `selectByRole`, `addRole`, `removeRole` |
| `UnifiedUserMapper.xml` | 同上 |
| `init-cas-schema.sql` | 新增 `roles` 列 |

### 删除

| 文件路径 | 原因 |
|----------|------|
| `BeenestAccessStrategy.java` | 改用原生 `DefaultRegisteredServiceAccessStrategy` |
| `AppAccessService.java` | 空壳，逻辑由原生 requiredAttributes + cas_user.roles 替代 |

## 不变部分

- Palantir Services tab (原生 Ace Editor 编辑 Service JSON)
- Palantir SSO Sessions tab (原生查看/踢用户)
- Palantir 其他 13 个原生 tabs
- CAS Account Registration Webflow (用户自助注册 + 邮件验证)
- CAS Password Management Webflow (密码重置)
- CAS Interrupt Webflow (mustChangePassword 检查)
- CAS CommunicationsManager (邮件/SMS 发送)

## 验证计划

1. **管理员添加用户**: Palantir → Account tab → 点击"添加用户" → 填写信息 → 发送开通链接 → 用户收到邮件 → 点击链接设置密码 → 登录成功
2. **用户管理**: Account tab → 搜索用户 → 禁用/启用/解锁 → 验证 cas_user 表状态变更
3. **授权访问**: Authorization tab → 选择 drone-system → 添加用户 → 验证 cas_user.roles 包含 ROLE_DRONE_SYSTEM
4. **查看授权用户**: Authorization tab → 选择 drone-system → 查看所有可访问用户列表
5. **撤销访问**: Authorization tab → 选择 drone-system → 移除用户 → 验证 cas_user.roles 不再包含 ROLE_DRONE_SYSTEM
6. **访问拒绝**: 未授权用户尝试访问 drone.beenest.com → 验证 CAS 返回拒绝页面或跳转 unauthorizedRedirectUrl
7. **访问允许**: 授权用户访问 drone.beenest.com → 验证 CAS 正常颁发 ST
8. **自动赋权**: 新注册用户 → 验证 cas_user.roles 包含 auto-grant 角色
9. **重置密码**: 管理员设置 mustChangePassword → 用户下次登录 → 验证 Interrupt Webflow 强制密码修改