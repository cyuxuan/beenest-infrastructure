# SAML 2.0 身份提供方（IAM 作为 IdP）

本文说明如何让**存量应用以 SAML 2.0 单点登录接入蜂巢身份**。平台在协议中扮演 **IdP（Identity Provider）**：登录在本平台完成，平台签发签名断言交给应用。

> 反方向（平台去连企业 IdP，即平台当 SP）见 [`saml-integration.md`](saml-integration.md)。两套能力互不影响：IdP 模式在核心 `authz` 模块里，SP 模式在 `provider-saml` 扩展包里。

---

## 1. 端点

以域 `nestid`、站点 `https://iam.example.com` 为例：

| 用途 | 方法与路径 | 说明 |
| --- | --- | --- |
| IdP 元数据 | `GET /realms/nestid/saml/metadata` | `application/samlmetadata+xml`，对端可"导入元数据"一键登记 |
| 单点登录（SSO） | `GET\|POST /realms/nestid/saml/sso` | 接收 AuthnRequest，签发断言并以表单 POST 投递给应用 |
| 单点登出（SLO） | `GET\|POST /realms/nestid/saml/slo` | 接收 LogoutRequest，结束 IdP 会话并回送 LogoutResponse |

- **实体 ID 就是元数据地址**：`https://iam.example.com/realms/nestid/saml/metadata`。对端登记时填的 EntityID 必须是它，否则断言的 `Issuer` 对不上。
- 端点全部匿名可达：SP 需要匿名拉元数据，SSO/SLO 由 SP 侧发起时用户可能还没有本平台会话。能不能签发断言由端点自己判定，不靠网关拦截。
- 控制台 → **应用与协议 → SAML 应用** 右上角的「对接信息」会把上面几个地址直接列出来，省得手工拼路径。

### 1.1 端点地址为什么不能随请求头漂移

实体 ID 与端点地址按当前请求的 scheme/host/port 推导。反向代理后如果对外域名与后端不一致，必须在代理层把 `Host` / `X-Forwarded-*` 透传正确，否则同一份登记会推导出不同地址，断言的 `Destination` 与 `Audience` 就会对不上。

---

## 2. 登记一个应用

控制台 → **应用与协议 → SAML 应用** → 登记应用。

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| 别名 | 是 | 仅平台内识别与日志用，不出现在 SAML 报文里；创建后不可改 |
| 展示名 | 否 | 跳转提示页上的应用名 |
| SP 实体 ID | 是 | 对端元数据里的 `entityID`，写入断言的 `AudienceRestriction` |
| 断言消费地址（ACS） | 是 | 断言的 `Destination` 与 `Recipient`，必须与对端配置**逐字符一致** |
| 单点登出地址（SLO） | 否 | 留空表示该应用不支持 SLO |
| NameID 格式 | 否 | 默认 `unspecified`；对端在 AuthnRequest 里指定格式时**以对端为准** |
| 对断言签名 | 是 | 默认开启。关闭后对端无法验证断言来源 |
| 强制请求验签 | 是 | 开启后对端必须用登记的证书给 AuthnRequest 签名，否则拒绝 |
| 对端签名证书 | 视情况 | 强制请求验签时必填；**只放证书，不放私钥** |
| 属性映射 | 否 | 断言属性名 → 取值表达式，见下节 |
| 启用 | 是 | 停用后该应用的登录与登出请求一律拒绝 |

对应的管理接口：

```
GET    /api/v1/admin/saml-sp                 # 列表
GET    /api/v1/admin/saml-sp/{id}            # 详情
POST   /api/v1/admin/saml-sp                 # 新增
PUT    /api/v1/admin/saml-sp/{id}            # 修改
DELETE /api/v1/admin/saml-sp/{id}            # 删除（逻辑删除）
GET    /api/v1/admin/saml-sp/endpoints       # 本域 IdP 端点地址
```

权限码：`saml-sp:view` / `saml-sp:update`（迁移脚本已授予 `SUPER_ADMIN`）。

---

## 3. 属性映射

键是断言里的属性名（对端怎么读就写什么），值是取值表达式：

| 表达式 | 取值 |
| --- | --- |
| `username` | 登录名 |
| `nickname` | 昵称 |
| `email` | 邮箱 |
| `phone` | 手机号 |
| `userId` | 用户主键 |
| `realm` | 身份域标识 |
| `roles` | 用户角色码，**多值**（每个角色一个 `AttributeValue`） |
| `attr.扩展属性名` | 用户扩展属性，如 `attr.dept` |

**留空时只下发三项**：`username`、`email`、`displayName`。断言里的每一个属性都会被对端留存，默认多给一项就是多泄露一项。

取不到值的表达式会被**整条跳过**，不会下发空字符串——空值属性会让部分 SP 建出空字段，比缺字段更难排查。

NameID 固定取**登录名**：它在域内唯一且稳定，改昵称或换邮箱都不影响对端已建立的账号关联。

---

## 4. 签名与证书

### 4.1 证书从域密钥派生

平台不额外存一套 SAML 证书，而是**从域的签名密钥现造一张自签证书**：

- 序列号 = 公钥 SHA-256 摘要前 8 字节；
- 主题 = `CN={realmKey} SAML, O=Beenest IAM`；
- 有效期 = 密钥生效时间起算 10 年；
- 用同一把私钥做 `SHA256withRSA` 自签。

RSA PKCS#1 v1.5 签名是确定性的，构造参数又全部来自密钥本身，所以**同一把密钥每次都得到完全相同的证书 DER**。这一点很重要：SP 侧登记的是证书指纹，证书一旦随重启漂移，所有已登记的对端都会验签失败。

域密钥轮换（`RealmKeyService.rotateKey`）后自然得到新证书，旧证书随之失效，需要在 SP 侧重新导入元数据。

### 4.2 签名范围

- **断言签名**（默认开）：`Assertion` 内嵌 `ds:Signature`，算法 `rsa-sha256`，规范化 `exc-c14n`。
- **响应不签名**：签名断言已足以证明来源；响应级签名会让部分 SP 重复验签。
- **元数据不签名**：元数据只含公钥材料，不签不影响可信性；需要时由对端自行校验传输通道。

### 4.3 校验对端请求

| 场景 | 行为 |
| --- | --- |
| 未开「强制请求验签」 | 不校验 AuthnRequest 签名，仅校验 `Issuer`、`Destination`、ACS 是否与登记一致 |
| 开了「强制请求验签」 | HTTP-Redirect 绑定校验查询串签名（`SAMLRequest&RelayState&SigAlg` 原始串），HTTP-POST 绑定校验报文内 XML 签名；未签名、缺证书、验签失败一律拒绝 |
| SLO（登记了证书） | 必须验签。登出会让目标用户全部会话失效，未验签的登出请求等于一个人人可用的踢下线接口 |

---

## 5. 单点登录流程

```
应用 ──① 302 /realms/nestid/saml/sso?SAMLRequest=...&RelayState=...
              │
              ├─ 未登录 ──② 302 /login?return_to=<原地址>
              │               用户登录成功 ──③ 302 回原地址
              │
              └─ 已登录 ──④ 签发断言 ──⑤ 自动提交表单 POST 到 ACS
```

**为什么是两段式而不是暂存请求**：AuthnRequest 本身是自足的（签名、ID、ACS 都在里面），把它原样编进 `return_to` 就能跨过登录页，不需要在服务端存一份待处理请求。代价是 URL 会长一点（典型 500–1500 字符），收益是没有状态、没有过期清理、多实例天然一致。

**防重放**：请求 ID 在真正签发断言的那一次写入 Redis（`iam:{realmKey}:saml:req:{requestId}`，TTL 取断言有效期 5 分钟），同一个 ID 第二次到达直接拒绝。预登录的那一次不登记——它还没签发任何东西。

**强制重认证（ForceAuthn）**：对端标记 `ForceAuthn` 时，要求当前会话建立在本次请求发起**之后**；旧会话不满足就跳一次登录页，重新登录后会话时间自然更新，不会形成循环。

**断言内容**：

| 元素 | 取值 |
| --- | --- |
| `Issuer` | IdP 实体 ID |
| `Subject/NameID` | 登录名，格式取对端指定值，未指定时用登记值 |
| `SubjectConfirmationData` | `Recipient` = ACS，`InResponseTo` = 请求 ID，`NotOnOrAfter` = +5 分钟 |
| `Conditions` | `NotBefore` = -60 秒（容忍时钟偏差），`NotOnOrAfter` = +5 分钟 |
| `AudienceRestriction` | SP 实体 ID |
| `AuthnStatement` | `SessionIndex` = 当前会话标识，认证方式记为 `PasswordProtectedTransport` |
| `AttributeStatement` | 按属性映射生成 |

---

## 6. 单点登出

```
应用 ──① 302 /realms/nestid/saml/slo?SAMLRequest=<LogoutRequest>
              │
              ├─ ② 验签（登记了证书时）
              ├─ ③ 按 NameID 反查本地用户，结束其**全部**会话
              └─ ④ 302 回 SP 的 SLO 地址，附签名过的 LogoutResponse
```

- 结束的是该主体的**全部会话**：SAML 的单点登出语义是"退出 IdP 会话"，而不是只退发起登出的那一个应用。
- 同时清掉本浏览器的会话 Cookie。
- 应用未登记 SLO 地址时返回 `204`，本地会话照样结束。
- **不做**向其它已登录应用广播登出：那需要给每个应用单独发 back-channel 请求，属另一个话题。

---

## 7. 代码结构

```
beenest-iam-authz/src/main/java/club/beenest/iam/authz/saml/
├── SamlIdpProtocol.java          报文编解码：解析 AuthnRequest/LogoutRequest、签发断言、生成元数据、验签
├── SamlIdpAuthnRequest.java      解析后的 AuthnRequest
├── SamlIdpLogoutRequest.java     解析后的 LogoutRequest
├── SamlIdpAssertionSpec.java     待签发断言的内容（协议层不判断该不该签发）
├── SamlSigningMaterial.java      私钥 + 自签证书
├── SamlIdpEndpoints.java         三个端点地址的推导
├── SamlIdpSigningService.java    从域密钥派生签名材料（确定性证书，按密钥主键缓存）
├── SamlIdpAttributeMapper.java   属性映射求值
├── SamlIdpService.java           编排：登记校验、断言装配、防重放、登出定位
└── SamlPostBindingPage.java      自动提交页（转义 + 最小 CSP）

beenest-iam-authz/src/main/java/club/beenest/iam/authz/web/SamlIdpController.java
beenest-iam-persistence/.../entity/SamlServiceProvider.java
beenest-iam-persistence/.../mapper/SamlServiceProviderMapper.java
beenest-iam-persistence/.../manager/IamSamlServiceProviderStore.java
beenest-iam-core/.../model/SamlServiceProviderModel.java
beenest-iam-core/.../spi/provider/SamlServiceProviderStore.java
beenest-iam-admin/.../controller/SamlServiceProviderController.java
beenest-iam-web/frontend/src/views/SamlServiceProvidersView.vue
```

协议层放在 `authz` 而不是 `provider-saml`，理由和 Passkey 一样：**IdP 模式是核心能力**（与授权服务器同级），不能因为扩展包被移除而消失；`provider-saml` 保持"对接外部 IdP 的扩展包"这一单一职责。

---

## 8. 数据表

`iam_saml_service_provider`（迁移 `V1.2.5__saml_idp.sql`，回滚 `U1.2.5__drop_saml_idp.sql`）：

| 列 | 说明 |
| --- | --- |
| `sp_key` | 站内别名，`(realm_id, sp_key)` 唯一 |
| `entity_id` | SP 实体 ID，`(realm_id, entity_id)` 唯一 |
| `acs_url` | 断言消费地址 |
| `slo_url` | 单点登出地址，空串表示不支持 |
| `name_id_format` | NameID 格式 |
| `sign_assertion` | 是否对断言签名 |
| `require_signed_authn_request` | 是否强制请求验签 |
| `sp_certificate` | SP 签名证书（PEM，公开材料） |
| `attribute_mapping` | 属性映射，JSONB |
| `enabled` | 是否启用 |

---

## 9. 已知边界

| 边界 | 说明 |
| --- | --- |
| 不支持 IdP 发起的 SSO | 没有 AuthnRequest 就没有 `InResponseTo`，无法把断言绑回某次发起；要支持需另开一个带 `sp` 参数的入口 |
| 不支持断言加密 | 断言以明文 XML 交给 SP，依赖 TLS 保护传输；金融类 SP 可能要求加密 |
| 不做 back-channel 登出 | 只在本地结束会话，不通知其它已登录应用 |
| 不做 metadata 签名 | 元数据未签名，需要时由对端校验传输通道 |
| 账号有待办事项时 SSO 会被拦下 | 待办拦截过滤器会先跳转到待办页；完成后需从应用重新发起 |

---

## 10. 排障

| 现象 | 排查方向 |
| --- | --- |
| SP 报 `Invalid issuer` / `Audience mismatch` | 对端登记的 EntityID 与「SP 实体 ID」不一致 |
| SP 报 `Invalid signature` | 对端用的是旧证书：域密钥轮换过，需要重新导入元数据 |
| SP 报 `Destination mismatch` | ACS 地址不一致，或反向代理透传的 `Host` 不对导致端点地址推导错 |
| SP 报 `Response expired` | 两侧时钟偏差超过 5 分钟；平台已容忍 -60 秒的偏差，超出需校时 |
| 反复跳登录页 | 对端开了 `ForceAuthn`，且浏览器没接受会话 Cookie（检查 `IAM_SESSION` 的 `SameSite` / `Secure`） |
| 提示「该登录请求已被使用」 | 同一个 AuthnRequest 被重放；从应用重新发起即可 |
| 元数据 404 | 域标识不对，或域已停用 |
| 登出后仍能进应用 | 应用没有登记 SLO 地址，或应用侧未实现 SLO |
