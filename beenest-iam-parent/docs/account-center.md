# 用户中心(账户中心)

面向登录用户的统一自助页:`/account`。把「我是谁、我有哪些凭证、我授权了谁、我在哪登录」四件事收在一页,
并提供个人数据导出。管理端另有控制台(`/console`),两者权限模型不同——用户中心只认「是不是你本人」,
不使用管理端权限码。

---

## 1. 能力范围

| 区块 | 能做什么 | 不能做什么 |
|---|---|---|
| 账号资料 | 查看登录名、昵称、邮箱(含验证状态)、手机号(含验证状态)、账号类型 | 不能在此修改;改资料走 `/api/v1/console/profile`(前端控制台) |
| 登录口令 | 查看是否已设置本地口令 | 不能在此改密;改密走 `/api/v1/console/password` 或账户待办页 |
| 动态口令 | 绑定(密钥 + 确认码两步)、关闭(需一次动态口令或恢复码)、重发恢复码 | 不能查看已有密钥——密钥只在绑定那一次明文出现 |
| Passkey | 查看已绑定的若干把(名称、绑定时间、最近使用)、添加、删除 | 不能重命名;重命名走 `/api/v1/console/webauthn/credentials/{id}` |
| 我的授权 | 列出已授权应用(应用名、scope 展示名、首次授予时间),逐个撤销 | 不能修改 scope 子集;撤销后只能等应用重新征求同意 |
| 登录设备 | 列出会话(登录方式、IP、UA、登录时间、最近活跃),注销非本机会话 | 不能注销当前会话——当前会话请走退出登录 |
| 个人数据 | 下载 JSON 快照(资料、属性、角色、组、凭证元数据、授权、设备、最近 50 条登录事件) | 不导出任何密钥:口令哈希、TOTP 密钥、恢复码哈希、Passkey 公钥均不在范围内 |

---

## 2. 页面与端点

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/account` | 渲染用户中心;`?done=` / `?failed=` 为上一次操作的结果标记 |
| POST | `/account/consents/revoke` | 表单提交,参数 `clientKey`;成功后 302 回 `/account?done=consentRevoked` |
| POST | `/account/sessions/revoke` | 表单提交,参数 `sid`;成功后 302 回 `/account?done=sessionRevoked` |
| GET | `/account/export` | 下载 `iam-personal-data-{username}-{date}.json`,`Cache-Control: no-store` |

动态口令与 Passkey 的操作复用既有接口(`/api/v1/console/mfa/*`、`/api/v1/console/webauthn/*`),
页面内联脚本调用后整页刷新——这两个流程本身需要浏览器参与(WebAuthn 断言、一次性恢复码展示),
不适合做成服务端表单。

### 为什么写操作用表单 POST 而不是 REST + fetch

撤销授权、注销设备都是"一次点击、一个结果"的动作,没有局部刷新的价值。
表单 POST + 302 回跳(PRG)不依赖会话闪存,也不需要在前端维护一份乐观更新后的列表;
结果通过查询参数回传,刷新页面不会重复提交。

代价是失败时拿不到具体原因(页面只显示"操作未完成,该记录可能已被移除"),
明细写进日志。这是有意的:撤销失败最常见的原因是记录已不在,
而"该客户端是否属于当前域"这类信息不该出现在页面上。

---

## 3. 数据来源

| 区块 | 来源 |
|---|---|
| 账号资料 | `iam_user`(`IamUserManager`) |
| 口令是否设置 | `UserProvider#hasLocalPassword`(本地口令与联邦用户区分) |
| 动态口令 | `MfaService#status` → `iam_user_credential` 中 `TOTP` / `RECOVERY_CODE` 两类凭证 |
| Passkey | `WebAuthnCredentialService#list` → `iam_webauthn_credential` |
| 我的授权 | Redis `iam:consent:*`(`RedisOAuth2AuthorizationConsentService`) |
| 授予时间 | Redis `iam:consent-granted:*` |
| 登录设备 | `iam_user_session`(`IamUserSessionService#listSessions`) |
| 待办 | `iam_user.required_actions`(`RequiredActionService#pending`) |

### 授予时间为什么单独一个键

同意记录的值是 `Set<String>`(已批准的 scope 集合),这是 Spring Authorization Server
的读取契约,混入时间戳会破坏它。所以授予时间另存一个键:

```
iam:consent:{registeredClientId}:{principalName}         → Set<String>  scope 集合
iam:consent-granted:{registeredClientId}:{principalName} → epoch 毫秒   首次授予时间
```

两条键在 `save()` 里一起写、在 `remove()` 里一起删,有效期同为 30 天。
授予时间用 `SET NX` 写入——再次同意只刷新 scope,不改变"从何时起授权"这一事实。

键名用 `consent-granted` 而不是 `consent:granted`:后者会被按 `iam:consent:*` 的扫描
误当成一条同意记录(解析出 clientId = `granted`)。

授予时间缺失时页面显示 `—`,不影响撤销——老数据(本功能上线前写入的同意记录)没有这个键。

---

## 4. 越权与边界处理

用户中心的每一条数据都以 `CurrentUserHolder.require()` 的主体为边界,不做"传个 id 就查"的设计。

| 场景 | 处理 |
|---|---|
| 授权记录里的客户端属于别的域 | 直接从列表剔除,不展示、不可撤销 |
| 客户端已被删除 | 从列表剔除(不展示无法撤销的僵尸记录) |
| 撤销时客户端不存在或跨域 | `NOT_FOUND`;日志留 clientKey,页面只显示通用失败提示 |
| 撤销时本人未授权过该客户端 | `NOT_FOUND` |
| 注销他人会话 | `NOT_FOUND`(不区分"不存在"与"不属于你") |
| 注销当前会话 | `STATE_CONFLICT`,提示改用退出登录 |
| 用户记录已被删除 | `NOT_FOUND` |

### 与账户待办拦截的配合

`IamRequiredActionFilter` 的放行清单包含 `/account`。原因:待办里的"绑定动态口令"、
"绑定 Passkey"就在用户中心办理,把用户中心拦住会让待办页的入口指向一个 404。
用户中心顶部在有未完成待办时显示提示条,并给出 `/required-action` 的入口。

---

## 5. 个人数据导出

导出是只读快照,结构与字段名固定,便于外部系统解析:

```json
{
  "exportedAt": "2026-10-27T15:00:00",
  "realm": "default",
  "account": { "userId": 7, "username": "alice" },
  "profile": { "username": "...", "nickname": "...", "email": "...", "emailVerified": true,
               "phone": "...", "phoneVerified": false, "userType": "INTERNAL",
               "createdTime": "...", "attributes": { "department": "研发" } },
  "roles": [ { "code": "iam-admin", "name": "身份管理员" } ],
  "groups": [ { "code": "rd", "name": "研发部" } ],
  "credentials": {
    "passwordSet": true, "totpEnabled": true, "recoveryCodesRemaining": 6,
    "passkeys": [ { "displayName": "MacBook", "transports": ["internal"],
                    "backupEligible": true, "createdTime": "...", "lastUsedTime": null } ]
  },
  "authorizedApplications": [ { "clientKey": "demo", "clientName": "示例应用",
                                "scopes": ["openid", "profile"], "grantedTime": "..." } ],
  "sessions": [ { "loginType": "PASSWORD", "ip": "127.0.0.1", "userAgent": "...",
                  "createdTime": "...", "lastActiveTime": "...", "current": true } ],
  "recentLoginEvents": [ { "eventType": "LOGIN_SUCCESS", "loginType": "PASSWORD",
                           "ip": "127.0.0.1", "userAgent": "...",
                           "failReason": null, "occurredTime": "..." } ]
}
```

**不导出的字段**:`iam_user.password`、`iam_user_credential.secret_data`、
`iam_webauthn_credential.credential_data`(含公钥)、`user_handle`、`credential_id`。
`PersonalDataExportServiceTest` 用断言把这几项钉住——导出是合规交付物,
"多带了一个字段"不会有人报错,只会有人投诉。

scope 用原始标识(`openid`)而不是展示名(`身份标识`):导出结果要能被机器处理,
展示名随界面语言变化。

---

## 6. 代码结构

| 位置 | 职责 |
|---|---|
| `authz/account/AccountOverview.java` | 页面数据模型(嵌套 record:Profile / Credentials / ConsentItem / ScopeItem / SessionItem) |
| `authz/account/AccountOverviewService.java` | 装配页面数据;撤销授权、注销设备 |
| `authz/account/PersonalDataExportService.java` | 组装导出快照 |
| `web/controller/AccountCenterController.java` | `/account` 页面与三个写/下载端点 |
| `web/config/IamTemplateFormatter.java` | 模板用的时间格式化与截断 |
| `themes/nestid/account/account.html` | 页面模板 |
| `themes/nestid/static/css/iam.css` | `.acc-*` 样式(与登录页共用设计令牌) |

页面数据一次装配完再渲染,不做前端二次请求:登录页已经证明"服务端渲染 + 内联脚本处理需要浏览器参与的动作"
这条路在本项目里成本最低——不用为账户中心再引一套前端构建。

---

## 7. 已知边界

- **资料与改密仍在前端控制台**:`/account` 只读展示资料,不提供修改入口。要补的话,
  改密表单可以直接复用账户待办页的实现。
- **Passkey 不能重命名**:接口已有(`PUT /api/v1/console/webauthn/credentials/{id}`),页面未接入。
- **会话列表没有分页**:`listSessions` 返回本人全部会话。个人会话数通常在个位数;
  若出现异常膨胀(脚本反复登录),应先查登录事件而不是给页面加分页。
- **导出没有频率限制**:导出是一次全量读取,大账号(几千条登录事件被截到 50 条)成本可控,
  但连续调用仍会打库。需要限流时在 `/account/export` 上加 IP 维度计数即可。
- **导出不含"操作日志"**:只有登录事件。管理操作审计在 `iam_audit_log`,面向管理员,不并入个人导出。

---

## 8. 排障

| 现象 | 排查方向 |
|---|---|
| 我的授权为空,但应用确实走过同意页 | 客户端是否属于当前域(`iam_client.realm_id`);同意记录 30 天过期;Redis `iam:consent:*` 是否存在 |
| 授权时间显示 `—` | 该同意记录写入时本功能尚未上线,没有 `iam:consent-granted:*` 键;重新授权一次即可补上 |
| 撤销后刷新又出现 | 撤销删除的是 `{clientId}:{principalName}`;检查页面传入的 `clientKey` 与 `iam_client.client_key` 是否一致 |
| 设备列表里没有当前设备 | 纯 Bearer 令牌访问没有会话 Cookie,`IamSessionCookie.read` 返回 null,所有会话都标为非本机 |
| 点"绑定动态口令"无反应 | 浏览器控制台看 `/api/v1/console/mfa/enroll`;`IamRequiredActionFilter` 是否放行了该前缀 |
| 导出文件是空的 | `PersonalDataExportService` 依赖 `CurrentUserHolder`,纯令牌访问需带有效 JWT |
