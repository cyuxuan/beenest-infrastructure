# Passkey(WebAuthn)登录与凭证管理

Passkey 用设备上的密钥对替代口令:私钥留在手机/电脑/安全密钥里,平台只保存公钥。
用户以指纹、人脸或设备 PIN 完成本地验证,平台侧没有任何可被拖库拿走的共享密钥,
也不存在"口令被钓鱼站点骗走"这条路径。

本文对应差距清单中的 **P0-4**([`keycloak-gap-analysis.md`](keycloak-gap-analysis.md) 第三节)。

---

## 一、能力范围

| 能力 | 状态 | 说明 |
|---|---|---|
| 绑定 Passkey | ✅ | 账户待办页、账户中心接口;单用户可绑多把 |
| Passkey 登录(需先填账号) | ✅ | 登录页"免密登录"入口 |
| Passkey 登录(无用户名) | ✅ | 账号留空,由认证器选择密钥(`allow-usernameless`) |
| 凭证列表 / 重命名 / 删除 | ✅ | `/api/v1/console/webauthn/**` |
| 签名计数器克隆检测 | ✅ | 计数器回退即拒绝,详见第四节 |
| 强制绑定(域策略) | ✅ | `RequiredAction.CONFIGURE_PASSKEY` |
| 设备型号白名单(attestation 信任锚) | ✗ | 当前用非严格模式,不校验证书链 |
| Passkey 作为第二因素(口令后追加) | ✗ | 当前是一级凭据;二步验证仍走 TOTP |

---

## 二、配置

```yaml
iam:
  webauthn:
    enabled: true                                  # IAM_WEBAUTHN_ENABLED
    rp-id: id.example.com                          # IAM_WEBAUTHN_RP_ID
    rp-name: 统一身份认证                            # IAM_WEBAUTHN_RP_NAME
    origin: https://id.example.com                 # IAM_WEBAUTHN_ORIGIN
    timeout-millis: 60000                          # IAM_WEBAUTHN_TIMEOUT_MILLIS
    user-verification-required: true               # IAM_WEBAUTHN_UV_REQUIRED
    allow-usernameless: true                       # IAM_WEBAUTHN_ALLOW_USERNAMELESS
    challenge-ttl-seconds: 300                     # IAM_WEBAUTHN_CHALLENGE_TTL_SECONDS
    max-credentials-per-user: 10                   # IAM_WEBAUTHN_MAX_CREDENTIALS
```

三项必填:`enabled`、`rp-id`、`origin`。缺任一项时:

- 登录页不渲染"免密登录"入口;
- 账户待办页的绑定按钮置灰并提示"Passkey 未启用";
- 接口返回 `20023 WEBAUTHN_NOT_CONFIGURED`(HTTP 503)。

这样处理是因为配置错误在浏览器侧的表现是"参数无效",平台日志里什么都看不到。

### rp-id 与 origin 的关系

`rp-id` 必须是 `origin` 的注册域或它的父域:

| origin | 合法 rp-id | 非法 rp-id |
|---|---|---|
| `https://id.example.com` | `id.example.com`、`example.com` | `app.example.com`、`localhost` |
| `http://localhost:8080` | `localhost` | `127.0.0.1` |

配错时浏览器直接拒绝创建凭证。`rp-id` 一旦上线就不能改——改了等于换绑定范围,
所有已绑定的 Passkey 立即失效。

本地开发档(`application-dev.yml`)已默认开启并配好 `localhost`;浏览器把
`http://localhost` 视为安全上下文,因此本地可以完整走通注册与登录。

---

## 三、数据模型

`iam_webauthn_credential`(迁移 `V1.2.4`,回滚 `U1.2.4`):

| 列 | 用途 |
|---|---|
| `credential_id` | 凭证标识(Base64URL),**全局唯一**,一把密钥只能绑一个账号 |
| `credential_data` | attested credential data 的 CBOR 原文(AAGUID + 凭证标识 + COSE 公钥) |
| `user_handle` | 无用户名登录用的用户句柄,同一账号的多把密钥共用 |
| `sign_count` | 签名计数器,只增不减 |
| `aaguid` | 认证器型号,仅展示 |
| `transports` | 传输方式,用于让浏览器优先唤起正确的认证器 |
| `display_name` | 用户自定义名称 |
| `backup_eligible` / `backup_state` | 是否为同步型 Passkey、当前是否已备份 |
| `last_used_time` | 最近一次断言成功时间 |

不复用 `iam_user_credential`:那张表按 `credential_type` 一用户一类只留一条
(TOTP、恢复码),而 Passkey 天然多把并存。

---

## 四、安全设计

### 挑战一次性

挑战由服务端生成(32 字节随机),存 Redis,`GETDEL` 原子消费。

- 注册挑战按用户索引:`iam:{realm}:webauthn:reg:{userId}`,重复发起覆盖旧的;
- 登录挑战按挑战值索引:`iam:{realm}:webauthn:auth:{challenge}`,
  值为绑定的用户主键(空表示无用户名登录)。

登录挑战必须按挑战值索引,因为登录时平台还不知道是谁;注册时主体已确定,按用户索引更直接。

### 跨账号拒绝

断言时三处交叉校验:

1. 挑战绑定的账号 == 凭证所属账号(挑战发给 A、断言来自 B 的密钥 → 拒绝);
2. 断言里的 `userHandle` == 凭证记录里的 `user_handle`;
3. 凭证必须存在且未删除。

任一处不符都返回同一条"Passkey 验证失败",真实原因只进日志。

### 签名计数器

`sign_count` 只增不减。断言通过后比较新旧值:

```java
if (newCounter != 0 && oldCounter != 0 && newCounter <= oldCounter) {
    // 计数器回退:同一把密钥的私钥在两处存在,按克隆处理
    throw new BusinessException(ErrorCode.WEBAUTHN_VERIFICATION_FAILED);
}
```

两端都为 0 时放行:部分认证器(尤其平台内置的)不实现计数器,恒为 0。
把"不支持"当成"克隆"会让这些设备永远登不进去。

### 不可枚举

登录选项接口对已绑定凭证的账号返回 `allowCredentials`,对未绑定或不存在的账号
返回空数组。响应结构一致,仅数组长度不同——这是 Passkey 登录必须付出的信息代价:
不告诉浏览器用哪把钥匙,无用户名登录之外就无法工作。账号存在性本身仍由口令登录
保持不可区分(账号不存在与口令错误同文案)。

### 删除策略

允许删到一把不剩。强制"至少留一把"会让换设备的人卡在删不掉旧密钥的死角里;
用户随时可以用口令 + 动态口令登录回来。

---

## 五、接口

### 登录(未登录可达,已豁免 CSRF)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/webauthn/options` | 取挑战与请求选项;`{"account": "..."}`,`account` 可空 |
| POST | `/api/v1/auth/webauthn/authenticate` | 提交断言,成功即建立会话 |

### 凭证管理(需登录,只操作本人)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/console/webauthn/status` | `{available, count}` |
| GET | `/api/v1/console/webauthn/credentials` | 凭证列表 |
| POST | `/api/v1/console/webauthn/register/options` | 取创建选项 |
| POST | `/api/v1/console/webauthn/register` | 提交证明对象,绑定完成 |
| PUT | `/api/v1/console/webauthn/credentials/{id}` | 重命名 |
| DELETE | `/api/v1/console/webauthn/credentials/{id}` | 删除 |

自助端只校验"是否属于本人",不使用管理端权限码。

---

## 六、流程

### 绑定

```
浏览器                         平台
  │  POST /register/options      │
  │ ───────────────────────────> │ 生成挑战 → Redis
  │ <─────────────────────────── │ {challenge, rp, user, excludeCredentials}
  │
  │  navigator.credentials.create()
  │   └─ 设备本地生成密钥对,用户按指纹确认
  │
  │  POST /register              │
  │ ───────────────────────────> │ 消费挑战 → 校验证明对象
  │                              │ → 落库(公钥 + 计数器)
  │ <─────────────────────────── │ 凭证视图
```

`excludeCredentials` 里带上已绑定的凭证,认证器据此拒绝"同一把钥匙绑两次",
用户看到的是"这个设备已经绑定过了"而不是一条平台侧的冲突错误。

### 登录

```
浏览器                         平台
  │  POST /webauthn/options      │
  │ ───────────────────────────> │ 生成挑战(绑定账号)
  │ <─────────────────────────── │ {challenge, rpId, allowCredentials}
  │
  │  navigator.credentials.get()
  │   └─ 设备用私钥签名(挑战 + 来源)
  │
  │  POST /webauthn/authenticate │
  │ ───────────────────────────> │ 消费挑战 → 校验签名 → 校验计数器
  │                              │ → 建立会话
  │ <─────────────────────────── │ 当前用户与权限码
```

---

## 七、代码结构

| 位置 | 职责 |
|---|---|
| `authz/webauthn/WebAuthnProperties` | 配置绑定与"是否可用"判定 |
| `authz/webauthn/WebAuthnConfiguration` | `WebAuthnManager`、CBOR 编解码器 |
| `authz/webauthn/WebAuthnChallengeService` | 挑战签发与一次性消费 |
| `authz/webauthn/WebAuthnOptionsFactory` | 组装浏览器选项(规范字段名) |
| `authz/webauthn/WebAuthnCredentialService` | 注册/断言校验、凭证 CRUD |
| `authz/webauthn/WebAuthnCodec` | Base64URL 编解码、随机挑战 |
| `authz/rest/PasskeyController` | 登录端点 |
| `console/controller/WebAuthnController` | 凭证管理端点 |
| `persistence/.../WebAuthnCredential*` | 实体、Mapper、存储 |

密码学校验全部由 [WebAuthn4J](https://github.com/webauthn4j/webauthn4j) 承担
(`webauthn4j-core` 0.28.4)。CBOR 解析、COSE 公钥还原、attestation/assertion
签名校验都不自己实现——这类代码写错不会报错,只会在某类设备上悄悄放行。

---

## 八、已知边界

- **不做 attestation 信任链校验**:非严格模式,任何厂商的认证器都能绑定。
  需要"只允许公司发的安全密钥"时,换成严格模式并配信任锚。
- **不作为第二因素**:当前 Passkey 是一级凭据,不能"口令之后再验一次 Passkey"。
  二步验证仍走 TOTP。
- **不设域级开关**:启用与否是平台级配置(`iam.webauthn.enabled`),不是按域。
  多域部署若需按域区分,应把 `rp-id` / `origin` 提到域配置里。
- **`rp-id` 不可变更**:变更等于换绑定范围,已绑定的 Passkey 全部失效。

---

## 九、运维

### 回滚

```sql
-- 见 db/rollback/U1.2.4__drop_webauthn_credential.sql
DROP TABLE iam_webauthn_credential;
```

回滚后已绑定的密钥记录全部丢失,用户需重新绑定;账号与口令不受影响,仍可正常登录。
把 `iam.webauthn.enabled` 置 false 是更温和的做法:入口消失,数据保留。

### 排障

| 现象 | 排查方向 |
|---|---|
| 登录页没有"免密登录"入口 | `iam.webauthn.enabled`、`rp-id`、`origin` 是否都配了;浏览器是否支持 |
| 点了按钮浏览器报"参数无效" | `rp-id` 与 `origin` 是否匹配(见第二节的表) |
| `20025 本次验证已超时` | 挑战过期(默认 300 秒)或已被消费;重新发起即可 |
| `20026 Passkey 验证失败` | 看服务端日志的 `Passkey 断言校验失败` 行,含异常类型 |
| 某台设备突然登不进去 | 检查是否触发了计数器回退(日志含"疑似密钥被克隆") |

日志里的关键行:

```
Passkey 绑定成功: realm=..., userId=..., credentialId=...
Passkey 登录校验通过: realm=..., userId=..., credentialId=...
Passkey 签名计数器回退,疑似密钥被克隆: realm=..., old=..., new=...
```
