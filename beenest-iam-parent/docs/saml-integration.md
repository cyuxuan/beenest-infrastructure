# SAML 2.0 接入指南（IAM 作为服务方 SP）

本文说明如何把企业身份源（AD FS、Azure AD / Entra ID、Shibboleth、Keycloak 等）通过 **SAML 2.0** 接入蜂巢身份。
平台在协议中扮演 **SP（Service Provider）**：登录在 IdP 侧完成，平台只接收并校验签名断言。

能力范围：`beenest-iam-provider-saml` 扩展包，默认随服务端发布；移除该依赖即整体下线，平台核心不含 SAML 相关知识。

---

## 1. 端点

以域 `nestid`、提供方别名 `corp-adfs`（接入时自取）为例：

| 用途 | 方法与路径 | 说明 |
| --- | --- | --- |
| 发起登录 | `GET /broker/nestid/corp-adfs/login?target=/portal` | 生成 AuthnRequest，302 到 IdP 的 SSO 地址 |
| 断言消费（ACS） | `POST /broker/nestid/corp-adfs/callback` | IdP 把 `SAMLResponse` 表单 POST 到这里 |
| SP 元数据 | `GET /broker/nestid/corp-adfs/metadata` | 给 IdP 侧"导入元数据"用，内容为 `application/samlmetadata+xml` |

- 登录页会自动出现该提供方的按钮（`REDIRECT` 形态），也可以直接把上面的"发起登录"地址给业务系统做单点入口。
- `target` 只接受站内相对路径（`/` 开头且非 `//`），其余一律回落到 `/portal`，避免成为开放重定向跳板。
- **不支持 IdP 发起的 SSO**：那种流程里平台没有签发过 `RelayState`，无法把断言绑回某次登录发起，放行等于给重放开后门。

### 1.1 元数据里的实体 ID

SP 实体 ID 默认按回调地址推导（去掉 `/callback`），即 `https://<host>/broker/nestid/corp-adfs`。
IdP 侧登记的"信赖方标识 / 标识符"必须与它一致，否则断言里的 `Audience` 校验不过；也可以在配置项 `spEntityId` 里显式改成 IdP 要求的字符串。

---

## 2. 平台侧配置

控制台 → **身份提供方** → 新建，类型选 **SAML 2.0**。

| 配置项 | 必填 | 说明 |
| --- | --- | --- |
| `idpEntityId` | 是 | IdP 的 EntityID，必须与断言 `Issuer` 完全一致 |
| `ssoUrl` | 是 | IdP 的 SSO 端点（HTTP-Redirect 绑定），平台把 AuthnRequest 发到这里 |
| `idpCertificate` | 是 | IdP 的 **X.509 签名证书**（PEM，含或不含 `BEGIN/END` 头均可），用于校验断言签名 |
| `spEntityId` | 否 | 本平台实体 ID；留空按回调地址推导 |
| `nameIdFormat` | 否 | 默认 `urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified` |
| `usernameAttribute` | 否 | 取用户名的断言属性名，可逗号分隔多个候选；留空自动匹配 `username/uid/name/…` |
| `emailAttribute` | 否 | 取邮箱的属性名，留空自动匹配 `email/mail/…` |
| `nicknameAttribute` | 否 | 取昵称的属性名，留空自动匹配 `nickname/displayName/cn/…` |

> 证书是公开信息，不是密钥：表单里按普通多行文本录入，不做脱敏，方便直接比对指纹。
> 平台不做 XML 加密（`EncryptedAssertion`）以外的密钥协商，也不落任何 IdP 私钥。

---

## 3. IdP 侧配置

### 3.1 AD FS

1. **添加信赖方信任** → 选择"导入有关信赖方的数据"，直接填平台元数据地址
   `https://<iam-host>/broker/nestid/corp-adfs/metadata`（或另存为 `.xml` 后导入）。
2. 不要求多因素时勾掉"启用对 MFA 的支持"（按企业策略定）。
3. **编辑声明颁发规则** → 至少下发：
   - `Name ID`：`Windows 账户名` 或 `电子邮件地址`（作为外部唯一标识 `externalId`，用户改名不应导致账号重绑，建议用不可变的员工号/邮箱）
   - `Windows 账户名` → 作为 `username`
   - `电子邮件地址` → 作为 `email`
4. 在平台的提供方配置里填 `idpEntityId`（如 `http://adfs.corp.local/adfs/services/trust`）、`ssoUrl`（`https://adfs.corp.local/adfs/ls/`），以及 AD FS 的令牌签名证书（PEM）。

### 3.2 Azure AD / Entra ID

1. 企业应用 → **新建应用程序** → **创建你自己的应用程序** → 选"集成库中找不到的其他任何应用程序"（SAML 单点登录）。
2. **基本 SAML 配置** → 上载元数据文件：下载 `https://<iam-host>/broker/nestid/corp-adfs/metadata` 后上传。
3. **属性和声明**：确认 `nameidentifier`（Name ID）与 `emailaddress`、`name` 已勾选；可追加 `groups` 供角色映射使用。
4. **SAML 签名证书** → 下载"证书（Base64）"，内容粘贴到平台的 `idpCertificate`；`idpEntityId` 填 Azure 应用里显示的"Azure AD 标识符"。

### 3.3 Keycloak（做 IdP）

1. 新建 Realm 客户端，`Client ID` 填平台的 SP 实体 ID，`Client type` 选 `SAML`。
2. 在 `Settings` 里把 `Valid redirect URIs` 设为
   `https://<iam-host>/broker/nestid/corp-adfs/callback`，`Name ID format` 与平台保持一致。
3. `Keys` 页签里拿到 IdP 签名证书（`Certificate`），`idpEntityId` 填 `https://<keycloak>/realms/<realm>`，`ssoUrl` 填
   `https://<keycloak>/realms/<realm>/protocol/saml`。
4. `Client scopes` → 确保 `role_list` / `basic` 里带了 username、email、displayName 属性。

---

## 4. 平台做了什么校验

一次回调要过以下全部检查，任一不过都是登录失败（HTTP 401，账号或密码登录仍然可用）：

| 检查 | 拒绝原因字样 | 说明 |
| --- | --- | --- |
| 报文可解析 | `SAML 响应不是合法的 XML 报文` | Base64 解不开或不是 XML |
| **签名** | `unsigned` / `Invalid signature` | 响应或断言必须由登记的证书签名；公钥不匹配同样拒绝 |
| **Destination** | `Invalid destination` | 必须是本平台该提供方的 ACS 地址 |
| **Audience** | `audiences` | 断言受众必须包含 SP 实体 ID |
| **Issuer** | `Invalid issuer` | 必须与 `idpEntityId` 一致 |
| **有效期** | `NotOnOrAfter` / `NotBefore` | `Conditions` 与 `SubjectConfirmationData` 各查一遍，允许 5 分钟时钟偏差 |
| **InResponseTo** | `InResponseTo` | 必须对应本次发起的 AuthnRequest |
| **RelayState** | `SAML 回调缺少 RelayState` | 必须对应本平台签发、且未被消费过的 `state` |

`InResponseTo` 的绑定不需要额外存储：AuthnRequest 的 `ID` 是 `RelayState`（即平台签发的 state）的 SHA-256 摘要，
回调时按同一个值重算即可比对。state 本身在 Redis 里一次性消费（GETDEL），过期 5 分钟。

---

## 5. 身份映射与建号

| 平台字段 | 取值 |
| --- | --- |
| 绑定键（`iam_user_federated_identity.external_id`） | 断言 `NameID`——用户改名、换邮箱都不应导致重绑 |
| 绑定行 `external_username` | `usernameAttribute` → 常见候选 → `NameID` |
| 本地登录名 | 首次建号时按 `{提供方别名}-{外部标识}` 清洗生成（域内唯一、可追溯来源） |
| 邮箱 / 昵称 | `emailAttribute` / `nicknameAttribute`，建号时写入 |
| 其他属性 | 断言里所有 `Attribute` 原样带回（多值以逗号拼接），供字段映射器使用 |

首次登录自动创建本地账号并绑定外部身份（JIT），之后每次登录复用同一条绑定；
外部属性能否覆盖已有档案由字段映射器决定，不由协议层决定。

一次实测（域 `default`、别名 `corp-adfs`、NameID 为邮箱）落地结果：

```
iam_user                       username = corp-adfs-zhangsan_corp.example.com-f3911a6c
                               nickname = 张三,  email = zhangsan@corp.example.com
iam_user_federated_identity    external_id = zhangsan@corp.example.com
                               external_username = zhangsan
```

---

## 6. 常见故障对照

| 现象 | 排查方向 |
| --- | --- |
| 跳回平台后 401，日志含 `Invalid destination` | IdP 侧登记的 ACS 与平台回调地址不一致（协议/端口/域名任一不同都不行；反向代理后注意 `X-Forwarded-*`） |
| `Invalid issuer` | `idpEntityId` 与 IdP 实际 EntityID 不一致（常见于补了/漏了末尾斜杠） |
| `audiences` | IdP 侧"信赖方标识"与平台 `spEntityId` 不一致 |
| `Invalid signature` | 证书填成了旧证书，或只填了证书正文的一部分 |
| `NotOnOrAfter` | IdP 与平台时钟偏差超过 5 分钟；对时后再试 |
| `InResponseTo` 不匹配 | 用户在多个标签页并发发起登录，或回调被重放 |
| 登录页没有该提供方按钮 | 提供方被停用，或域上下文不对（`/broker/{realm}/…` 的 realm 要与提供方所属域一致） |

---

## 7. 与 OIDC 的取舍

| 场景 | 建议 |
| --- | --- |
| 企业内部 AD / 员工账号体系 | SAML（AD FS / Entra ID 原生支持，无需改造 IdP） |
| 互联网应用、移动端、需要 refresh token | OIDC（平台自身也是 OIDC 提供方，见 `client-integration.md`） |
| 需要 IdP 发起（IdP-initiated）的门户集成 | 目前不支持；可在门户侧改为链接到平台的发起地址 |
