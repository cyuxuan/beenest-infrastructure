# CAS 业务系统登录代理设计

## 背景

当前 `beenest-cas-client-spring-security-starter` 已具备 CAS 认证、Bearer Token、SLO 和用户同步能力，但它更偏向于“业务系统接入 CAS 的安全适配层”，并不负责“业务系统域名下的登录请求代理”。

业务目标是：

- 业务系统的 APP / 小程序登录请求先打到业务系统自己的地址
- 业务系统通过 starter 将请求转发到 CAS
- CAS 先确认该业务系统是否合法，再执行用户认证
- 如果用户是首次通过第三方登录创建，则默认授予其当前系统权限
- 如果用户已存在，则检查其是否具备当前系统权限
- `casweb` 场景继续保持原有 CAS 登录页跳转行为

## 目标

1. 业务系统登录入口保持在业务系统自身域名下。
2. 系统合法性校验放在 CAS 侧完成，不放在 starter 侧。
3. 业务系统注册到 CAS 时，CAS 发放一个秘钥串作为系统证书。
4. 系统调用登录代理接口时，必须携带证书并按 HMAC 签名。
5. 第三方首次认证新用户时，自动授予当前系统权限。
6. 已存在用户登录时，必须先确认其具备当前系统权限。
7. 保持现有 CAS Web 登录重定向不变。

## 非目标

- 不把 starter 变成独立网关或统一认证代理中心。
- 不在 starter 中实现最终鉴权逻辑。
- 不替换现有 CAS WebFlow 登录页。
- 不改变现有 `BeenestAccessStrategy` 的用户授权语义，只扩展其使用场景。

## 总体方案

### 1. 系统证书发放

CAS 在业务系统注册成功时，为该系统生成一条秘钥串。

- 该秘钥串只在注册成功响应中返回一次
- CAS 侧保存秘钥串的哈希或加密值，不直接明文持久化
- 业务系统保存该秘钥串，后续所有登录代理请求都要携带
- 支持后续轮换和吊销

### 2. 请求签名规则

业务系统调用登录代理接口时，必须携带以下字段：

- `appId` 或 `serviceId`
- `timestamp`
- `nonce`
- `signature`

签名串采用如下规范：

```text
signingString = timestamp + "\n" + nonce + "\n" + body
signature = HMAC-SHA256(signingString, secret)
```

建议请求头命名为：

- `X-BEENEST-APP-ID`
- `X-BEENEST-TIMESTAMP`
- `X-BEENEST-NONCE`
- `X-BEENEST-SIGNATURE`

CAS 校验顺序：

1. 校验 `appId/serviceId` 是否存在且可用
2. 校验时间戳是否在允许窗口内
3. 校验 `nonce` 是否已被使用
4. 使用注册时发放的秘钥串验签
5. 通过后进入用户认证流程

### 3. 登录代理链路

#### APP / 小程序

1. 用户在业务系统域名下发起登录请求
2. starter 接住请求并将原始请求与签名头转发到 CAS
3. CAS 先验证业务系统证书
4. CAS 再执行第三方登录、短信登录或 APP 登录
5. CAS 判断用户是否为首次创建
6. 如果是首次创建，自动授予当前系统权限
7. 如果用户已存在，则校验其是否拥有当前系统权限
8. 返回 token 和用户信息给业务系统
9. 业务系统再把结果返回给 APP / 小程序

#### Web

1. 浏览器访问业务系统受保护页面
2. 如果未登录，直接跳转 CAS 登录页
3. 不经过业务系统登录代理接口

## 组件边界

### starter 侧

starter 只负责：

- 暴露业务系统登录代理入口
- 透传请求体、请求头和上下文信息到 CAS
- 处理 CAS 返回结果并转回业务系统
- 对 `casweb` 请求保持现有重定向行为

starter 不负责：

- 判断业务系统是否合法
- 决定是否授予权限
- 维护 nonce 防重放状态

### CAS 侧

CAS 负责：

- 发放和管理系统秘钥串
- 验证系统签名
- 防重放
- 判断系统是否为注册系统
- 执行用户认证
- 判断新用户 / 老用户
- 处理当前系统权限自动授予或拒绝

## 数据与状态

### 建议新增的系统证书数据

建议为 CAS registered service 补充以下信息：

- `serviceId`
- `secretHash` 或加密后的 secret
- `secretVersion`
- `enabled`
- `createdAt`
- `rotatedAt`
- `revokedAt`

### 建议新增的 nonce 状态

建议使用 Redis 保存 nonce，键名可按以下方式组织：

- `cas:service-nonce:{serviceId}:{nonce}`

TTL 建议与时间戳窗口一致，例如 5 分钟。

## 用户授权规则

### 首次创建用户

当用户通过微信、支付宝、抖音、小程序或 APP 第一次在 CAS 中完成认证并创建统一用户时：

- 默认授予当前登录所对应的 CAS 系统权限
- 这项自动授权仅作用于当前系统
- 不影响其他已注册系统

### 已存在用户

当用户已存在时：

- 先检查当前系统是否已对该用户授予权限
- 如果未授权，返回拒绝结果
- 如果已授权，继续签发 token

### 与现有能力的关系

现有 `BeenestAccessStrategy` 已能在 ST 签发前检查用户对应用的访问权。

本方案会把它继续作为“用户对系统的最终访问控制点”，而“系统是否合法”则前移到 CAS 登录代理入口的签名校验阶段。

## 错误处理

- 系统不存在或已吊销：返回 `403`
- 时间戳过期：返回 `401` 或 `403`
- nonce 重放：返回 `403`
- 签名错误：返回 `403`
- 用户无当前系统权限：返回 `403`
- CAS Web 登录正常流程失败：按现有认证失败语义返回

## 测试策略

### CAS 侧测试

- 注册系统时秘钥串发放是否只返回一次
- 签名正确时是否通过
- 签名错误时是否拒绝
- 过期 timestamp 是否拒绝
- nonce 重放是否拒绝
- 新用户首次登录是否自动授权当前系统
- 老用户无权限是否拒绝
- 老用户已有权限是否放行

### starter 侧测试

- 登录代理请求是否原样透传关键头部
- 业务系统域名登录时是否保持返回格式稳定
- `casweb` 请求是否仍直接跳转 CAS 登录页

## 迁移原则

1. 先在 CAS 侧接入系统证书与签名校验能力。
2. 再在 starter 侧接入登录代理入口。
3. 最后逐个业务系统切换到新登录方式。
4. 旧的 CAS Web 登录路径保持兼容，不做破坏性修改。

## 统一登录架构建议

### 设计目标

统一登录要同时满足四件事：

1. 业务系统零代码或极少代码接入。
2. 登录请求不直接暴露 CAS 复杂度给各业务系统。
3. 小程序和 APP 的后续请求不能每次都远程打 CAS。
4. CAS 登出、吊销、禁用后，业务系统要能尽快作废本地登录态。

### 推荐分层

#### 1. CAS 作为唯一身份源

CAS 负责：

- 统一身份认证
- 统一账号合并
- 统一权限判断
- 统一 token 签发与吊销
- 统一 SLO 通知

CAS 不下放最终信任边界。

#### 2. starter 作为业务系统接入适配层

starter 负责：

- 暴露业务系统域名下的登录入口
- 转发登录请求到 CAS
- 承接 CAS 返回结果
- 处理 CAS SLO 回调
- 管理本地会话缓存和身份上下文

starter 不负责：

- 认证谁能登录
- 授权谁能访问某个系统
- 决定是否允许访问

#### 3. 本地会话作为请求承载

业务系统后续请求不再频繁远程 CAS，而是走本地会话或本地 token 校验：

- Web 端：`SecurityContext` + `HttpSession`
- APP / 小程序：`Bearer accessToken` + `refreshToken`

后续请求的认证应尽量在本地完成，只在 token 过期或撤销时再回到 CAS。

### 小程序 TGT 的推荐语义

小程序端不建议把 `TGT` 当成“每次都去 CAS 远程查一次”的长效票据，也不建议把它当成“纯本地随机串”。

推荐做法是：

1. CAS 登录成功后签发短期 `accessToken`
2. 同时签发长一点的 `refreshToken`
3. `accessToken` 设计成可本地验签的自包含 token
4. `refreshToken` 只允许在 CAS 端轮换

这样做的好处是：

- 业务系统后续请求不需要每次都打 CAS
- token 可以本地验证，性能稳定
- token 过期后可以无感刷新
- CAS 仍然保留最终吊销权

### 本地认证如何保证安全

如果 token 要本地认证，必须至少满足这四个条件：

1. **可验签**
   - token 必须带签名，业务系统能独立验证真伪。
2. **短有效期**
   - accessToken TTL 要短，建议 5 到 15 分钟。
3. **可撤销**
   - token 需要有 `jti` 或 `sid`，CAS 能推送撤销信息。
4. **可轮换**
   - refreshToken 必须支持一次一换，避免长时间复用。

### CAS 登出如何让业务系统立即失效

只靠本地缓存不够，必须有“服务端推送 + 本地兜底”双层失效机制。

#### 第一层：CAS Back-channel Logout

CAS 在用户登出或票据销毁时，向注册系统的 `logoutUrl` 发起后台回调。

业务系统接到回调后应：

- 销毁本地 `HttpSession`
- 清理 `SecurityContext`
- 删除本地 `accessToken/refreshToken`
- 标记相关 `jti/sid` 为 revoked

#### 第二层：撤销列表

如果业务系统是多实例，单机内存失效不够，必须把撤销状态放到共享存储：

- Redis `revoked:jti:{jti}`
- Redis `revoked:sid:{sid}`
- Redis `session:user:{userId}`

这样任意节点都能判断 token 是否已经失效。

#### 第三层：TTL 兜底

即使回调丢失，短 TTL 也能保证失效窗口有限。

### 业务系统后续身份如何认证

#### Web 场景

- 初次访问受保护资源时，starter 将请求引导到 CAS
- 成功后写入 `HttpSession`
- 后续请求通过 `SecurityContextHolder` 读取身份
- SLO 到来时销毁本地 session 并清空上下文

#### APP / 小程序场景

- 首次登录远程 CAS
- 后续请求本地验签 accessToken
- accessToken 过期后用 refreshToken 再换
- SLO 到来时清理本地 token 缓存和会话缓存

### 零代码接入建议

为了让业务系统“零代码”或“近零代码”接入，starter 最好提供三层自动装配：

1. **认证自动装配**
   - 默认注册 CAS authentication provider
   - 默认注册 Bearer token filter
2. **SLO 自动装配**
   - 默认注册回调 filter
   - 默认注册 session registry
3. **同步自动装配**
   - 默认注册用户同步 webhook/pull
   - 默认把变更刷新到 session 和本地缓存

业务系统只需要配置：

- CAS Server 地址
- 当前 serviceId
- service secret
- 回调地址
- 是否启用 bearer/token/sync

### 现有 starter 的评价

当前 starter 已经具备接入基础，但还建议补齐这些能力：

- 登录成功后统一注册 `sid -> sessionId` 和 `userId -> sessionId`
- SLO 回调时同时清理 `BearerTokenCache`
- 把内存会话注册表改成可共享的 Redis/分布式实现，至少支持多实例
- 小程序 accessToken 改成短期可验签 token，而不是单纯依赖远程 TGT 校验

### starter 模块拆分建议

为了把 starter 维持成“接入层”而不是“网关层”，建议拆成五个逻辑模块：

1. **Proxy**
   - 暴露业务系统域名下的登录代理入口
   - 负责把请求体、请求头和签名上下文转发到 CAS
   - 负责把 CAS 响应原样返回给调用方

2. **Token**
   - 负责 access token 和 refresh token 的验签、过期判断和刷新
   - 负责把通过校验的身份写入 `SecurityContext`
   - 负责把 token 校验结果做短 TTL 缓存

3. **SLO**
   - 负责接收 CAS back-channel logout
   - 负责销毁本地 session
   - 负责清理 token 缓存和撤销状态

4. **Sync**
   - 负责用户同步 webhook / pull
   - 负责把 CAS 的用户变更刷新到本地会话
   - 负责用户信息变更后的扩散

5. **Core**
   - 负责自动装配、配置项、条件开关和默认安全链
   - 负责把以上模块默认接到 Spring Security 生命周期中

### Token 协议建议

如果要做到“本地认证 + 可撤销 + 零代码”，access token 最好具备自描述能力。

建议字段：

- `iss`: CAS Server 标识
- `sub`: userId
- `aud`: serviceId
- `sid`: CAS 会话 ID
- `jti`: token 唯一 ID
- `iat`: 签发时间
- `exp`: 过期时间
- `loginType`: WECHAT / APP / SMS / DOUYIN / ALIPAY
- `firstLogin`: 是否首次登录
- `authorities`: 权限列表

业务系统本地校验时只需要：

1. 验签
2. 验 `exp`
3. 验 `aud`
4. 查 `jti/sid` 是否已撤销

### 多实例失效模型

如果 starter 部署成多实例，不能只靠本地内存维护状态，需要把这些状态共享出去：

- `cas:revoked:jti:{jti}`
- `cas:revoked:sid:{sid}`
- `cas:session:user:{userId}`
- `cas:session:sid:{sid}`
- `cas:service-nonce:{serviceId}:{nonce}`

建议全部放 Redis，理由是：

- SLO 需要所有节点同时失效
- token 验证缓存需要节点间一致性
- nonce 防重放不能只在单机记忆
- 用户同步刷新需要跨节点传播

### 失效策略优先级

建议采用三层失效优先级：

1. **主动撤销**
   - CAS 登出或吊销时立即写入撤销列表
2. **回调销毁**
   - starter 收到 back-channel logout 后销毁本地 session 和缓存
3. **TTL 兜底**
   - 即使失效通知丢失，短 TTL 也能限制风险窗口

### 推荐的请求流

#### Web

1. 用户访问业务系统受保护页面
2. starter 重定向到 CAS
3. CAS 完成认证
4. CAS 签发票据
5. starter 建立本地 `HttpSession`
6. 后续请求直接走 `SecurityContext`
7. CAS 登出时通过 back-channel 通知业务系统

#### 小程序 / APP

1. 用户在业务系统域名下发起登录
2. starter 转发到 CAS
3. CAS 完成第三方登录和权限判断
4. CAS 签发短期 access token + refresh token
5. starter 把 token 返回给业务系统
6. 后续请求本地验签
7. 过期后走 refresh
8. CAS 登出后触发撤销列表和缓存失效

### 推荐实现边界

starter 应该默认做到这些事情：

- 零代码注册登录代理 Controller
- 零代码注册 SLO 回调 Filter
- 零代码注册 Bearer Token Filter
- 零代码注册 Session Listener
- 零代码注册用户同步机制

业务系统只需要在必要时覆盖：

- `CasUserDetailsService`
- `CasUserRegistrationService`
- 自定义业务权限映射

### 实现顺序建议

如果要继续落地，我建议按这个顺序做：

1. 标准化 token 语义，让小程序 access token 支持本地验签。
2. 把 SLO 的 session / token / cache 失效闭环补齐。
3. 把 `ActiveSessionRegistry` 和 `BearerTokenCache` 迁到 Redis 或等价共享状态。
4. 再考虑降低远程 CAS 依赖，把远程调用收敛到登录和 refresh。

### 最终推荐结论

最合理的设计是：

- **登录时远程 CAS**
- **业务请求时本地认证**
- **登出时 CAS 主动回调业务系统**
- **业务系统通过撤销列表和短 TTL 保证失效**

这是兼顾性能、安全和零代码接入的平衡点。
