# 账户自助:找回口令、邮箱验证与账户待办

本文说明平台内置的三项账户自助能力:用户如何自己找回口令、如何验证邮箱,
以及"登录后必须先完成某些事"的账户待办机制。三者共用一套票据与投递设施。

---

## 1. 能力总览

| 能力 | 入口 | 是否需要登录 | 依赖 |
|---|---|---|---|
| 找回口令 | `/forgot-password` → 邮件链接 → `/reset-password` | 否 | 邮件通道(或短信通道) |
| 邮箱验证 | 账户待办页 / 邮件链接 `/verify-email` | 发起需登录,落地不需要 | 邮件通道 |
| 账户待办 | `/required-action` | 是 | 无 |

三者的共同点是"一次性的、不可推导的票据":票据随机生成、限时有效、消费即失效,
URL 里只带票据,不带用户标识。

---

## 2. 账户待办(Required Action)

### 2.1 语义

待办事项表示**账号已能登录、但还有些事必须先办完**。它与登录门禁是两回事:

- 登录门禁(MFA 门禁)拦在**认证阶段**——这次登录过不去;
- 待办事项拦在**会话可用范围**上——登录过了,但只放行办理待办所需的那几个入口。

待办事项存在 `iam_user.required_actions`(逗号分隔),权威值在用户行上;
登录时会话快照里再放一份副本,用于逐请求判定。

### 2.2 支持的动作

| 代码 | 含义 | 触发时机 |
|---|---|---|
| `UPDATE_PASSWORD` | 必须修改口令 | 管理员建号设了初始口令、管理员重置口令、管理员手工挂 |
| `VERIFY_EMAIL` | 必须验证邮箱 | 管理员手工挂;邮箱未验证且域策略要求时 |
| `CONFIGURE_TOTP` | 必须绑定二步验证 | 管理员手工挂(域策略强制 MFA 时建议同时挂) |

未知代码在解析时被丢弃:更高版本写入的动作在降级部署后不该让用户卡在一个
本版本没有实现页面的待办上。

### 2.3 拦截规则

`IamRequiredActionFilter` 在会话守卫之后执行,按**前缀白名单**放行:

```
/required-action                      待办页
/api/v1/auth/required-actions         查看待办、发起邮箱验证
/api/v1/auth/password                 改密
/api/v1/auth/mfa                      二步验证绑定
/api/v1/auth/email-verification       邮箱验证
/api/v1/auth/logout  /logout          注销
/api/v1/auth/me                       当前用户
/api/v1/console/password              账户中心改密
/api/v1/console/mfa                   账户中心 MFA
/api/v1/console/profile               账户中心资料
/api/v1/console/my-permissions        前端外壳渲染权限码
/verify-email                         邮箱验证落地页
/theme-static/** 等静态资源
```

其余路径:页面请求 302 到 `/required-action`,接口请求返回 403 +
业务码 `20018`(`REQUIRED_ACTION_PENDING`)。

该过滤器在**两条过滤链**上都注册:业务链之外,授权服务器链也注册了一份——
否则用户绕过待办页直接访问 `/oauth2/authorize` 就能换到令牌,"必须改密"形同虚设。

### 2.4 完成后的清除

| 动作 | 清除位置 |
|---|---|
| 自助改密 | `PasswordChangeService.changePassword` → 清 `UPDATE_PASSWORD` |
| 管理员重置口令 | `PasswordChangeService.resetPassword` → **追加** `UPDATE_PASSWORD` |
| 邮箱验证落地 | `EmailVerificationService.confirm` → 清 `VERIFY_EMAIL` |
| MFA 绑定确认 | `MfaController.confirm` → 清 `CONFIGURE_TOTP` |

自助改密与管理员重置对待办的方向相反:口令是用户自己定的,改完即满足;
口令是管理员定的,用户必须再改一次。两个入口因此分开,不共用同一个方法。

### 2.5 管理端接口

| 方法 | 路径 | 权限码 |
|---|---|---|
| `GET` | `/api/v1/admin/users/{id}/required-actions` | `user:view` |
| `PUT` | `/api/v1/admin/users/{id}/required-actions` | `user:update` |

`PUT` 为覆盖式:传 `{"actions": []}` 即清空待办。未知代码直接拒绝,
不静默丢弃——静默丢弃会让管理员以为设置成功了。

---

## 3. 找回口令

### 3.1 流程

```
用户 → /forgot-password 输入邮箱或手机号
     → POST /api/v1/auth/password-reset/request
     → 命中账号:签发一次性票据(Redis,默认 30 分钟) + 投递
     → 用户点邮件里的链接:/reset-password?realm=&ticket=
     → POST /api/v1/auth/password-reset/confirm
     → 票据 GETDEL 消费 → 走统一改密 → 令牌版本递增(全端下线)
```

### 3.2 不可枚举

**无论账号是否存在、是否有可用找回方式、邮件是否发出去**,申请接口的响应完全一致。
差异只进投递记录与日志。

限流分两层:

- **IP 维度**:10 分钟内最多 5 次,超出返回 `TOO_MANY_REQUESTS`;
- **账号维度**:60 秒内重复申请、或一小时内超 5 次,静默跳过不投递。

账号维度不抛错是有意的:一旦对存在的账号返回"太频繁",这个响应本身就成了
账号存在性信号。

### 3.3 票据

| 项 | 值 |
|---|---|
| 长度 | 32 字节随机数,Base64URL 编码(约 43 字符) |
| 存储 | `iam:{realmKey}:pwreset:{ticket}` → `userId` |
| 有效期 | `iam.account.reset-ttl-minutes`,默认 30 分钟 |
| 消费 | Redis `GETDEL` 原子取走,并发提交只有一个能成功 |
| 内容 | 只有用户主键,不含任何可推导凭据 |

### 3.4 双通道

- **邮箱**:需要邮件通道可用(`iam.mail.channel=smtp`),发送 HTML 模板邮件;
- **手机号**:复用短信验证码通道(`SmsCodeService`),用户凭验证码在重置页设置新口令。

账号参数含 `@` 按邮箱处理,否则按手机号处理。

---

## 4. 邮箱验证

- 发起:`POST /api/v1/auth/required-actions/email-verification`(需登录),给**当前账号邮箱**发链接;
- 落地:`GET /verify-email?realm=&ticket=`(无需登录,用户可能从另一台设备的邮件客户端点开);
- 票据:`iam:{realmKey}:emailverify:{ticket}` → `userId`,默认 60 分钟;
- 成功:`email_verified = TRUE` + 摘除 `VERIFY_EMAIL` 待办。

票据无效时渲染同一个结果页,只是换成失败文案——不区分"用过"与"不存在"。

---

## 5. 邮件通道配置

邮件通道是 SPI(`core.spi.mail.MailChannel`),内置 SMTP 实现。
换邮件服务商(或改用 HTTP API 型服务)只需实现该接口并改 `iam.mail.channel`。

```yaml
iam:
  mail:
    channel: smtp                 # 留空 = 未启用
    host: smtp.example.com
    port: 587
    username: iam@example.com
    password: ${IAM_MAIL_PASSWORD}   # 只从环境变量读,不落配置文件
    from: iam@example.com
    from-name: 统一身份认证
    starttls: true                # 587 端口通常为 true
    ssl: false                    # 465 端口直连 TLS 时置 true
    timeout-millis: 10000
```

**未配置时**:`MailChannelRouter.resolve()` 返回空,邮件相关入口在页面上不展示
(登录页的"忘记口令"仍保留,但走短信路径),接口明确报
`MAIL_CHANNEL_NOT_CONFIGURED`,而不是等用户点了按钮才发现发不出去。

### 5.0 域级配置(优先于全局)

上面的 `iam.mail.*` 是**部署级兜底**。控制台「发信配置」可为每个身份域单独启用一套 SMTP
(地址、端口、账号口令、发件人、STARTTLS / SSL、超时),优先级高于全局:
域级启用且必填项完整时用域级,否则自动回落全局。

- SMTP 口令经主密钥信封加密后落库(`iam_realm_mail_config.password`),管理接口只返回
  `passwordConfigured` 布尔值,永远不回显密文或明文;
- 每次发信实时读库(每域一行、主键索引),改完保存即生效,不需要重启;
- 换主密钥时域级 SMTP 口令必须一并重加密,否则该域发信会因解不开密文而回落或失败
  (与 LDAP bind 口令同一口径,见 [`docs/deployment.md`](deployment.md) 第 2 节)。

### 5.1 模板

内置模板位于 `beenest-iam-authz/src/main/resources/mail-templates/{code}.html`,
用 `{{变量}}` 占位,由 `MailTemplateRenderer` 做替换与 HTML 转义:

| 模板 | 变量 |
|---|---|
| `password-reset` | `username` `resetUrl` `ttlMinutes` `appName` `year` |
| `email-verification` | `username` `verifyUrl` `ttlMinutes` `appName` `year` |
| `security-alert` | `username` `alertTitle` `alertSummary` `occurredAt` `appName` `year` |
| `account-opened` | `username` `appName` `year` |

控制台「邮件模板」可按域覆盖任一模板的正文并随时「恢复默认」:覆盖内容存
`iam_mail_template`,渲染时**先取域级覆盖、取不到再用内置模板**,覆盖读取失败也回落内置,
不会因为模板表异常导致邮件发不出去。找回口令与邮箱验证模板强制保留 `{{resetUrl}}` /
`{{verifyUrl}}`,保存接口会拒绝缺失跳转占位的版本——改坏链接等于让所有用户无法自助。

不引入模板引擎:邮件正文只需要变量替换,为此把一整套引擎拖进认证内核不划算,
而占位替换的行为完全可预测。变量值一律 HTML 转义——昵称等字段是用户可控内容。

### 5.2 投递记录

每次投递写一条 `iam_notification_log`(渠道、收件人、模板、主题、结果、失败原因、时间)。
只追加不更新:投递结果在发送那一刻就定了,事后改写会让"用户当时到底收没收到"
变成无法回答的问题。

控制台「通知投递」页(`notification:view`)可按渠道、结果、收件地址、模板编码与时间筛选,
不用连库。排障时先看这里,能分清"没发出去"(通道故障/未配置)与
"发出去了但用户没收到"(被退信)。

记录默认保留 90 天,`iam.notification.retention-days` 可调,`0` 表示永久保留;
清理任务每 6 小时分批物理删除,配置项见 [`docs/deployment.md`](deployment.md) 第 3.2 节。

### 5.3 提醒触发点

| 触发 | 模板 | 说明 |
|---|---|---|
| 口令修改/管理员重置 | `security-alert` | 由 `PasswordChangeService` 触发 |
| 动态口令绑定/解绑、恢复码重生成 | `security-alert` | 由 `MfaService` 触发 |
| Passkey 增删 | `security-alert` | 由 `WebAuthnCredentialService` 触发 |
| **新 IP 登录** | `security-alert` | 与上次成功登录比较来源网段:IPv4 比 /24、IPv6 比 /64,同网段换地址不提醒;首次登录无基线不提醒;换 IP 后基线立即更新,同一地址只提醒一次;服务端换码(PROVIDER)登录不计入 |
| 管理员开通账号 | `account-opened` | 邮件不含初始口令,引导用户自助设置 |

"新 IP 登录"是凭证变更之外的第二条独立线索:账号被接管时攻击者往往不改口令,
只靠凭证变更提醒会一声不响。基线存 Redis(`iam:{realm}:loginip:{userId}`),
丢失只影响一次判断,不会误报或阻断登录。

---

## 6. 数据库变更

`V1.2.3__account_self_service.sql`:

- `iam_user.required_actions VARCHAR(200) NOT NULL DEFAULT ''`
- 新建 `iam_notification_log`(两个索引:按域+时间、按域+收件人)

回滚脚本:`U1.2.3__drop_account_self_service.sql`。

后续与通知相关的迁移:

| 脚本 | 内容 |
|---|---|
| `V1.3.2__notification_log_permission.sql` | 按域补种 `notification:view` 并授予 `SUPER_ADMIN` |
| `V1.3.4__realm_mail_config.sql` | `iam_realm_mail_config`(域级 SMTP,口令密文)、`iam_mail_template`(模板覆盖)、`mail-config:view/update` 权限码 |

同样配有 `U1.3.2` / `U1.3.4` 回滚脚本。

---

## 7. 与 Keycloak 的对照

| Keycloak | 本项目 | 差异 |
|---|---|---|
| Required Actions | 同语义,3 个内置动作 | Keycloak 支持自定义动作插件;本项目动作集合在枚举内,新增需改代码 |
| Forgot Password | 同语义,票据一次性 | Keycloak 票据存在数据库;本项目存 Redis(自动过期,无需清理任务) |
| Verify Email | 同语义 | 一致 |
| Email Sender SPI | `MailChannel` SPI | 一致 |
| Email Templates | 文件模板 + 占位替换,支持域级覆盖 | Keycloak 用 FreeMarker,支持主题化;本项目用占位替换 + `iam_mail_template` 按域覆盖,不支持条件/循环 |
