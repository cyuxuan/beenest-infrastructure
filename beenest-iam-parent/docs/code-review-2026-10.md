# 代码 Review 与企业级可交付性评估

> 审查对象:蜂巢身份(beenest IAM)全量代码
> 审查日期:2026-10-26
> 代码基线:`5ff8df2`(含工作区未提交改动)
> 规模:13 模块 / 996 个 Java 文件 / 8.4 万行 / 73 个 Mapper XML / 23 个 Flyway 迁移

## 一、结论

**功能面:达到企业级可用。工程面:达到可交付。安全面:有 3 项需在对外交付前收口。**

| 维度 | 判定 | 依据 |
|---|---|---|
| 协议与功能完备度 | **达标** | OIDC/OAuth2 全端点、SAML 2.0 SP、多租户 Realm、MFA、单点登出(前/后通道)、应用级授权、LDAP 联合、短信登录、客户端准入策略均已落地并有集成测试 |
| 架构分层 | **基本达标** | 13 个模块依赖图无环、无反向依赖(实测);DTO 纪律严格(24 个 service 接口零 entity 泄漏);但 `core` 模块级被 `common` 污染,`persistence` 承载了 820 行业务算法 |
| 代码质量 | **良好** | 全仓库零 TODO/FIXME、零 `System.out`、零空 catch、零 `${}` 拼接 SQL;注释密度高且解释"为什么"而非"做了什么" |
| 测试 | **达标** | 274 个测试全绿(本轮修复后),含 Testcontainers 真实 PostgreSQL + Redis 的协议栈集成测试 |
| 运维就绪 | **达标** | 多阶段非 root 镜像、actuator 独立管理端口、Prometheus 指标、traceId 贯穿、Flyway 含回滚脚本、CI 含镜像自检 |
| **安全** | **未达标,需收口** | 3 项交付阻塞项 + 5 项本迭代内应处理项,详见第四节 |

**一句话**:这是一个工程质量明显高于行业平均水平的自研 IAM,主要短板不在"写得怎么样",而在**几处安全校验只做在创建路径、没做在修改路径**——这类缺口靠读代码很难发现,但恰好是最容易被利用的。

---

## 二、审查方法

四路并行,结论均要求 `文件:行号` 证据,再逐条人工复核后才动手:

1. **持久层与事务** — 111 个 Java 文件、38 个 Mapper XML、23 个迁移脚本
2. **认证与授权链路** — 按 OWASP ASVS 逐项对照 authz / admin 两模块
3. **架构与模块依赖** — 实测依赖矩阵、分层违规、扩展点自洽性
4. **人工复核与修复** — 每条高危结论独立复读源码确认后才改

修复后用三种手段验证:全量单测(274 项)、真实 PostgreSQL 顺序执行全部迁移 + 回滚、增量编译。

---

## 三、本轮已修复(14 项)

### P0 安全(3 项)

**1. 客户端更新接口可提权到任意用户**
`ClientServiceImpl.update()` 只做了登出地址与准入白名单校验,**没有复用 create 的 `validateClientCredentials`**;而 `ClientDtoConverter.applyUpdate()` 允许修改 `grantTypes` 与 `serviceAccountUserId`。
利用路径:持有 `client:update` 的域管理员把服务账号改成域内超管 → 把授权类型改成 `client_credentials` → 换取带超管权限全集的令牌。
修复:更新路径按"生效值"重新校验(请求未给则取库中值,否则只改一项就能绕过),并把服务账号的域归属校验从"仅凭据模式"提升为"只要声明就校验"。新增 3 个回归测试。

**2. LDAP 测试连接端点的 SSRF 与凭据外传**
`/api/v1/admin/ldap-config/test` 原要求 `ldap-config:view`(只读),却接受**任意 `serverUrl`**,并在表单口令留空时**回落使用已存的解密 bind 口令**。组合起来 = 只读权限即可让服务端连内网任意主机,并把本域目录凭据发出去。
修复三层:
- 权限提到 `ldap-config:update`(测试连接本就是保存前的动作)
- `LdapSettings` 紧凑构造器加协议白名单(仅 `ldap://` / `ldaps://`),挡住 JNDI 的其它协议入口;认证链路捕获后按"配置不完整"处理,不暴露成 500
- 存量口令只允许用于**已保存的那个地址**;改地址必须重新输口令

**3. 令牌与同意记录的跨域越权**
`TokenAdminServiceImpl.requireAuthorizationInRealm()` 与 `ConsentAdminServiceImpl` 用 `principalName` 反查"本域是否有同名用户"来判定归属。两域各有一个 `alice` 时,A 域管理员可吊销 B 域 alice 的令牌/同意。
修复:改用**注册客户端所属域**判定(客户端主键全局唯一且带域),并对不存在的客户端不再原样透传主键。

### P1 正确性(4 项)

**4. 组角色变更不失效权限缓存**(最值得修的一条)
`IamGroupManager.assignRoles()` 全链路没有 `permissionEpoch.bump()`,而同构的 `IamPositionManager.assignRoles()` 结尾有。后果:给组加/撤角色后,该域权限缓存最长 5 分钟不失效——**"撤掉一个组的角色"在窗口期内仍然生效**,与 readme 宣称的"权限撤销即时生效"直接矛盾。
修复:补上自增,并补齐说明为什么组是必须的一环。

**5. 权限代次自增时机错误**
多处 `permissionEpoch.bump()` 写在写库语句**之前**(`IamPermissionManager.create/update/remove`、`IamUserPermissionManager.upsert`、`IamMenuManager.removeWithChildren`)。并发请求会拿"新代次 + 旧数据"回填缓存,撤销反而被推迟一整个 TTL。
修复:把时机下沉到 `IamPermissionEpoch` 内部——事务内一律注册 `afterCommit` 回调,事务回滚则完全不自增。**一处改动同时修正全部调用点**,也顺带修掉了"回滚了却抬升了代次"的无意义自增。

**6. 多步写缺事务边界**(5 处)
- `IamMenuManager.removeWithChildren` 删角色-菜单关联 + 删菜单,两表无事务
- `IamMenuManager.replaceRoleMenus` / `IamPermissionManager.replaceRolePermissions` 先删后插,无事务(此前只靠 `RoleServiceImpl` 恰好在外层开了事务兜底)
- `GroupServiceImpl.update` 写三处(主体/上级/负责人)
- `UserServiceImpl.create` 建用户与写口令历史分属两个事务,后者失败则"不得重复使用"对该账号失效
- `RealmServiceImpl.create` 建域与建两条策略无事务,失败会留下"没有登录保护的域"

**7. 关系表缺反向索引导致全表扫描**
`iam_user_group(group_id)`、`iam_role_permission(permission_id)`、`iam_role_menu(menu_id)`、`iam_menu(permission_id)`、`iam_user_permission(permission_id)` 全部只有正向复合索引,而管理端大量按"被授予侧"查询(组成员列表、权限删除前的引用校验、菜单删除的关联清理)。
新增 `V1.2.2__relation_reverse_index.sql`(部分索引 `WHERE deleted = FALSE`)+ 配套 `U1.2.2` 回滚脚本。已在真实 PostgreSQL 17 上顺序执行全部 23 个迁移并验证索引建立,回滚脚本执行后归零。

### P2 坏味道(4 项)

**8. N+1 查询**(5 处)
组成员指派逐条查用户/岗位、岗位列表逐行查角色(一页 20 条放大成 40 次查询)、应用管理员列表逐行回查主体、批量改状态逐条查用户、有效角色诊断逐组查库。
全部改为批量取回 + 内存拼装;岗位角色数新增 `IamPositionManager.countRoles()` 复用已有的 `selectByPositionIds`。

**9. 死代码与误导性注释**
`GroupServiceImpl.evictMemberPermissionCache()` 与 `UserPermissionServiceImpl.evictPermissionCache()` 是**空方法**,注释写着"组变更由持久层统一提升权限代次"——而持久层当时并没有(见第 4 条)。空方法 + 错误注释比没有更糟:它让下一个人相信缓存已经处理过了。
已删除两个方法与全部 4 处调用。

**10. 访问令牌明文写日志**
`RedisOAuth2AuthorizationService` 在 debug 级打印完整 `token={}`。debug 日志常在排障时被临时打开,而访问令牌在有效期内可直接重放。改为只记原因。

**11. 会话 Cookie 未设 SameSite**
`IamSessionCookie` 的 javadoc 声称 `SameSite=Lax`,但代码从未设置——`application.yml` 里的 `same-site: lax` 只作用于容器的 `JSESSIONID`,不会自动套到这个自签 Cookie 上。已显式设置。
`clear()` 刻意不带 `Secure` 并补了说明:按 RFC 6265bis,带 Secure 的 Set-Cookie 经 HTTP 下发会被整条忽略(连删除一起丢掉),开发档会出现"点了退出还是登录态"。

**12. JWT 解码器缓存无上界**
`IamJwtDecoder` 以**未验签的 iss** 为键 `computeIfAbsent`,构造 `.../realms/<随机串>` 即可无限撑大 Map。已改为:域不存在直接拒绝、缓存设 64 上界。

### P3 交付物一致性(2 项)

**13. Dockerfile 漏拷贝 SAML 模块的 pom**
父 pom 的 `<modules>` 列了 13 个模块,Dockerfile 的依赖预拉取阶段只拷了 11 个子模块 pom,**漏了 `beenest-iam-provider-saml`**。`dependency:go-offline` 会因"子模块不存在"直接失败——CI 的镜像构建作业会红。已补。

**14. readme 与代码不一致**
模块清单标"12 模块"且漏列 `provider-saml`;测试数量写"127 个"(实际 274)。已更正。

---

## 四、交付前必须处理(未修复,需决策或更大改动)

以下 8 项我**没有**动。原因分两类:需要产品/安全策略决策,或改动会变更对外授权语义。列在这里供排期。

### 4.1 交付阻塞(建议本迭代内解决)

**A. 令牌未校验 audience**
`IamSecurityConfiguration.jwtDecoder()` 的校验链是 `createDefault()`(仅时间戳)+ 版本 + 黑名单,**没有 audience 校验器**;`IamJwtDecoder` 只用 iss 选密钥,不做信任绑定。
现状的实际约束来自权限命名空间:`SCOPED` 令牌只带绑定应用的权限,平台权限(`app_service_id IS NULL`)进不了应用令牌。但 **`LEGACY_GLOBAL` 令牌带用户权限全集**,一旦存在这类客户端,其签发的用户令牌即可直接调用 `/api/v1/admin/**`。凭据模式令牌更是**设计上就带平台权限全集**(`applyMachineClaims`)。
建议:管理接口加 audience/`azp` 白名单校验,只接受第一方客户端(控制台)与凭据模式令牌。这是策略决策,故未擅自改。

**B. ID Token 与 Access Token 未区分**
`IamJwtTokenCustomizer.customize()` 不判断 `context.getTokenType()`,`applyAuthorizationClaims` 对两者一律执行,`permissions` 声明同时写进 ID Token;`IamJwtAuthenticationConverter` 直接读该声明生成 authority,资源服务器也不校验 `typ`/`token_type`。
后果:交给 RP 的 ID Token 可当 Bearer 用。建议在定制器与转换器两侧按令牌类型分流。

**C. Realm 资源本身无租户隔离**
`RealmServiceImpl.requireRealm()` 只判存在不判归属,`RealmController` 仅有 `realm:view/update/delete` 权限码。一旦 `realm:*` 权限码被下放到租户域(种子脚本按域复制权限),持权者可读改删**任意**域含 `default`。
建议:明确 `realm:*` 为平台级权限(不下放到租户域),或在服务层加"非默认域只能管自己"的约束。

### 4.2 本迭代内应处理

**D. CSRF 豁免判据过宽**
`BearerTokenRequestMatcher` 只看请求**是否存在** `Authorization: Bearer` 前缀,不看令牌是否有效。带一个伪造 Bearer 头即可跳过 CSRF,认证仍回落到 Cookie。建议改为"该请求最终由 Bearer 认证成功才豁免"。

**E. 登录类接口整体豁免 CSRF**
`/api/v1/auth/login`、`login-sms`、`login-mfa`、`provider-code`、`/broker/**` 全部豁免。存在登录 CSRF(把受害者登录成攻击者账号)。建议至少对表单类登录加 `Origin`/`Referer` 校验。

**F. 客户端删除不清理下游**
`ClientServiceImpl.delete()` 只软删客户端,`iam_consent`、`iam_client_session`、`iam_client_scope_mapping`、`iam_role.client_id`、`iam_application_service.sso_client_pk` 全部悬空。应用服务删除同理(不处理 `iam_app_service_grant`、应用下菜单与权限)。
建议:要么级联清理,要么删除前做引用检查并拒绝——两者都是可辩护的策略,当前是"都不做"。

**G. 口令哈希算法**
`PasswordEncoderFactories.createDelegatingPasswordEncoder()`,默认 bcrypt cost=10,且接受 `{noop}` 前缀。建议升级到 Argon2id 或 bcrypt cost≥12,并用 `setDefaultPasswordEncoderForMatches` 收敛旧前缀。

**H. 交付元数据不完整**
`pom.xml` 的 `<license/>`、`<developer/>`、`<scm/>` 全是空元素;版本仍是 `0.0.1-SNAPSHOT`。对外交付前应补许可证与版本号,否则无法界定使用范围,也无法在制品库里区分"哪个是发布版"。

---

## 五、架构评估

### 5.1 依赖关系(实测,非声明)

`common → core → {persistence, federation} → provider-* → authz → {web, admin, console} → server`

**无环、无反向边**,父 pom 只有 `dependencyManagement`、不藏依赖。这一点做得比多数同类项目干净。

两处声明与事实不符:

| 问题 | 证据 |
|---|---|
| `core` 声称"不依赖任何 Spring",源码级成立(0 处 Spring import,11 个 model 全是无注解 record),但**模块级不成立** | `beenest-iam-core/pom.xml` 依赖 `common`,而 `common` 把 `spring-boot-starter-web` + `data-redis` + `spring-security-core` 声明为 compile,于是 `core` 的编译类路径里有 `spring-webmvc` 和 `tomcat`。且 core 源码 0 处 import common —— 这是一条**死依赖** |
| `web` 未声明依赖 `persistence`,却直接 import 了 `persistence.entity` 与 `manager` | 靠 `authz` 传递获得,模块边界是约定而非编译器强制 |

**影响**:模块边界无法靠编译器守护,新增代码越层不会被拦住。若要真正收紧,应把 `common` 拆成 `common-core`(纯 POJO)与 `common-web`(Spring 相关),并显式声明 `web → persistence`。

### 5.2 分层

- **Controller 薄且干净**:26 个控制器共约 2500 行,平均 96 行,全部返回 DTO
- **DTO 纪律严格**:24 个 admin service 接口**零** `import persistence.entity`,没有 entity 泄漏到 API 层
- **权限覆盖完整**:151 个映射端点,全部有方法级或类级 `@PreAuthorize`(仅 `AppAdminController.listMine` 用 `isAuthenticated()`,实现内按域+管理关系收窄)
- **两处破口**:`AuthController` 与 `LoginPageController` 直接注入 persistence manager 与 entity

**最大的架构偏差不在 Controller,而在 `IamAppBackfillManager`**:820 行、6 个职责(候选分析 / MOVE / COPY / 角色重连 / 祖先闭包 / 事务),住在 `persistence` 模块并自持 `@Transactional`,而对应的 admin 服务只剩 138 行转发。**业务算法住在存储层**,这是声明架构与运行架构偏差最大的一处。

### 5.3 上帝类(≥450 行)

| 类 | 行数 | 职责 | 建议 |
|---|---|---|---|
| `IamAppBackfillManager` | 820 | 6 | 拆 Analyzer / Mover / Copier / Relinker,算法搬回 admin |
| `RedisOAuth2AuthorizationService` | 650 | 6 | 拆 Codec / Blocklist / Indexer / SessionRecorder |
| `GroupServiceImpl` | 530 | 6 | 拆 CRUD / TreeAssembler / Membership / RoleBinding |
| `IamJwtTokenCustomizer` | 490 | 6 | 按声明族拆(机器/用户/组织/授权/映射器) |
| `AuthController` | 482 | 4(11 端点) | 按端点族拆 Login / Mfa / Federation / Session |
| `LaunchpadServiceImpl` | 465 | 5 | 拆 Visibility / MenuTree / LaunchUrl |
| `IamSecurityConfiguration` | 458 | 5(15 个 Bean) | 拆 FilterChain / Auth / Session / Logout |
| `SamlIdentityProvider` | 454 | 4 | 拆 RequestBuilder / AssertionParser / MetadataWriter |

这些类**当前可维护**,但都是"改一处要读全文件"的类型。建议按改动频率排序拆,不必一次全拆。

### 5.4 扩展点:热插拔只成立了一半

`IdentityProvider` / `Factory` / `Registry` / `ExternalProviderLoader` 四件套设计自洽,broker 流程零提供方分支(`IdentityBrokerService` 只调 `registry.create()` + 接口方法)——**这部分是真的可插拔**。

但"登录方式"这一维仍是封闭枚举 + 硬编码:

```java
// LoginMethodCatalog.java:93-98
return switch (providerId.trim().toLowerCase(Locale.ROOT)) {
    case "wechat-mini", "wechat-web" -> LoginMethod.WECHAT;
    case "oidc" -> LoginMethod.OIDC;
    case "saml" -> LoginMethod.SAML;
    default -> null;
};
```

新增 `alipay` 会命中 `default -> null`,后果是**入口直接消失而不是报错**。新增一个提供方实际要改 5 处:①新模块+Factory+Provider ②`LoginMethod` 加常量 ③`LoginMethodCatalog` 加 case ④`ClientCreateRequest`/`ClientUpdateRequest` 的 `@Pattern` 正则 ⑤前端渲染分支。其中 3 处是**编译期静默失效**。

另外"丢 jar 即生效"只对微信成立:只有 `provider-wechat` 有 `META-INF/services` 声明,`oidc`/`saml` 仅靠 `@Component`,必须改 `server/pom.xml`。

**建议**:把 `LoginMethod` 的映射下沉到 `IdentityProvider` 接口(由提供方自报 `loginMethodCode()`),`@Pattern` 正则改为服务端校验 + 目录查询,前端按接口返回的 `loginMethod` 渲染。这样才是完整的"加一个 jar 就多一种登录方式"。

---

## 六、企业级能力对照

### 已具备(经代码与测试双重确认)

多租户 Realm(独立 issuer/密钥/主题)、OIDC/OAuth2 全端点、SAML 2.0 SP、MFA(TOTP+恢复码+防重放)、口令策略与历史防重用、登录失败锁定与 IP 维度限流、图形验证码、短信验证码登录、LDAP/AD 联合(含 JIT)、微信小程序与扫码、客户端准入策略、应用门户与单点、应用级授权(SCOPED 令牌)、用户权限治理(GRANT/REVOKE+来源诊断)、组/岗位权限继承、审计日志(数据库级只追加 + 留存期 + CSV 导出防公式注入)、登录事件、会话管理(共享 Redis + 巡检 + 分布式锁)、单点登出(前/后通道 + 投递留痕)、密钥轮换、私钥信封加密(AES-256-GCM + PBKDF2 21 万次)、协议映射器、属性定义、客户端作用域、主题化。

### 已知缺口(readme 已如实列出,属新功能而非缺陷)

自助注册 / 找回密码 / 邮箱验证、行级字段级数据权限(ABAC)、客户端密钥自助轮换、告警通道与 SLO、备份与恢复演练、扩展包热重载、小程序与扫码账号合并。

### 建议补充的缺口(readme 未列)

| 缺口 | 影响 |
|---|---|
| 令牌 audience 与令牌类型校验 | 见 4.1 A/B,安全基线 |
| 交付元数据(许可证/版本/变更日志) | 无法界定使用范围与发布边界 |
| API 版本演进与弃用策略 | `/api/v1` 已分版本,但没有弃用公告与并存期约定,存量接入方升级无据可依 |
| 容量与性能基线 | 有 Prometheus 指标但无基线值,无法判断"当前是否正常" |
| 数据库连接池与慢查询阈值 | 未见显式配置,大租户下可能先撞连接数再撞 CPU |

---

## 七、建议的下一步(按投入产出排序)

1. **补 audience + 令牌类型校验**(4.1 A/B)——安全基线,改动集中在定制器、转换器、解码器三处
2. **明确 Realm 权限归属**(4.1 C)——一行策略决策,先定"是否下放 `realm:*`"
3. **客户端删除的引用策略**(4.2 F)——选级联还是拒绝,然后落地
4. **CSRF 判据收紧 + 登录 Origin 校验**(4.2 D/E)
5. **拆 `IamAppBackfillManager`**——唯一一处"业务算法住在存储层",拆完 `persistence` 才名副其实
6. **登录方式去硬编码**(5.4)——决定"热插拔"这块招牌是否名副其实
7. **补交付元数据**(4.2 H)——对外发布前必须
8. 其余上帝类按改动频率渐进拆分,不做一次性重构

---

## 附:本轮验证记录

| 验证项 | 方式 | 结果 |
|---|---|---|
| 编译 | `./mvnw -o -DskipTests test-compile` | 通过 |
| 单元 + 集成测试 | `./mvnw -o -B test`(含 Testcontainers PostgreSQL/Redis) | **274 项全绿** |
| 迁移脚本 | 真实 PostgreSQL 17 顺序执行 23 个 V 脚本 | 全部 OK |
| 回滚脚本 | 执行 `U1.2.2`,复查索引 | 5 个索引全部删除 |
| 回归测试 | 新增 `ClientServiceImplAdmissionTest` 3 个用例覆盖提权路径 | 10 项全绿 |
