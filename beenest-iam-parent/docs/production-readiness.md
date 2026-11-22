# 投产交付清单

> 目标:把平台从"功能完整"推进到"可对外投产交付",再统一跑一次全量测试。
> 基线:`docs/code-review-2026-10.md` 第四节(8 项未收口)+ `docs/keycloak-gap-analysis.md` 第七节(剩余项)。
> 日期:2026-10-27

## 一、清单

图例:`[ ]` 未开始 ｜ `[~]` 进行中 ｜ `[x]` 已完成 ｜ `[-]` 本轮不做(理由见第二节)

### A. 安全基线(交付阻塞)

| 编号 | 项 | 来源 | 状态 |
|---|---|---|---|
| A1 | 令牌类型分流:ID Token 不再携带权限声明,并打 `token_type` 标记 | review 4.1-B | [x] |
| A2 | 管理接口只接受平台类令牌(拒绝应用令牌),堵住 `LEGACY_GLOBAL` 令牌调管理接口 | review 4.1-A | [x] |
| A3 | 令牌受众校验(缺失 `aud` 即拒绝) | review 4.1-A | [x] |
| A4 | 写请求来源校验 + CSRF 豁免判据收紧(必须验签通过) | review 4.2-D/E | [x] |
| A5 | 口令哈希升级为 Argon2id,保留 bcrypt 兼容,移除 `{noop}` | review 4.2-G | [x] |

### B. 多租户与数据完整性

| 编号 | 项 | 来源 | 状态 |
|---|---|---|---|
| B1 | Realm 资源租户隔离:非平台域只能管理自身域 | review 4.1-C | [x] |
| B2 | 客户端删除前的引用检查,不留悬空外键 | review 4.2-F | [x] |

### C. 协议与功能补齐

| 编号 | 项 | 来源 | 状态 |
|---|---|---|---|
| C1 | SCIM 2.0(`/scim/v2/Users`、`/scim/v2/Groups`,含 PATCH 与过滤器) | gap P1-8 | [x] |
| C2 | 登录方式去硬编码 | review 5.4 | [x] 步骤链 |
| C3 | 自助注册 | gap 矩阵 P1 | [-] |
| C4 | `offline_access` 补语义或移除 | gap 六-5 | [x] 移除 |
| C5 | 客户端密钥双密钥并存轮换 | gap 六-2 | [-] |
| C6 | 认证步骤 SPI(四阶段七步骤,扩展包注册 bean 即生效) | gap P1-5 | [x] |

### D. 交付物与运维

| 编号 | 项 | 来源 | 状态 |
|---|---|---|---|
| D1 | pom 补 license/developer/scm,版本定版 `1.0.0` | review 4.2-H | [x] |
| D2 | `CHANGELOG.md` 含行为变更与升级指引 | review 4.2-H | [x] |
| D3 | 事件监听 SPI(投递外部系统) | gap 六-4 | [-] |
| D4 | 连接池与慢查询阈值显式配置 + 容量基线 | review 六 | [x] |
| D5 | API 版本演进与弃用策略文档 | review 六 | [x] |

### E. 验收

| 编号 | 项 | 状态 |
|---|---|---|
| E1 | 全量 `./mvnw -o -B clean test` 全绿 | [x] |
| E2 | 前端 `npm run build` 双入口构建通过 | [x] |
| E3 | 全量迁移在真实 PostgreSQL 上按序执行 + 回滚 | [x] |
| E4 | readme / docs 与实现对齐 | [x] |
| E5 | 迁移与回滚脚本一一配对(32 V / 32 U),支持版本回退 | [x] |

## 二、本轮不做(附理由)

| 项 | 理由 |
|---|---|
| C3 自助注册 | 组织内 IAM 的账号由管理员或 JIT 创建,是安全设计而非缺失(自助注册会让"谁能进系统"的边界从 HR 名册变成任何人)。对外注册场景需另做注册页,readme「已知能力边界」已如实列出 |
| C5 客户端密钥双密钥并存 | 现有轮换会让旧密钥立即失效,换密钥需要一次短暂中断。对内部接入方可接受(改配置 + 重启通常在同一维护窗口);若将来有第三方接入方要求零中断,再补并存期 |
| D3 事件监听 SPI | 审计日志与登录事件已落库且可导出 CSV,接 SIEM 可先用"定时导出"过渡;开放 SPI 属于扩展性投入,不影响投产 |
| `common` 拆成 `common-core` / `common-web` | 纯结构调整,不影响投产;拆分要动 13 个模块的依赖声明,回归面大于收益 |
| 其余 7 个上帝类按职责拆分 | 当前可维护;按改动频率渐进拆,不做一次性重构 |
| Keycloak 式流程引擎、ABAC、UMA、CIBA、DPoP、PAR、DCR、Kerberos | P2,当前定位下投入产出比不成立,见 `docs/keycloak-gap-analysis.md` 第五节 |

> **C2 已从本表移除**:原先判断"登录方式去硬编码"收益只在接入第八种登录方式时体现。
> 后续按 `docs/keycloak-gap-analysis.md` 第 5 节的折中方案落地为认证步骤链,
> 只拆步骤、不重排阶段,回归面控制在一个 Provider 内,因此提前做了。
> `LoginMethod` 仍保留枚举:准入白名单要按存储码精确匹配,枚举的类型安全比字符串码更可靠,
> 新增登录方式时加一项枚举即可。

## 三、验收记录(2026-10-27)

| 项 | 方式 | 结果 |
|---|---|---|
| 全量测试 | `./mvnw -o -B clean test` | **488 项,0 失败 0 错误** |
| 新增测试 | 令牌类型校验 4、令牌类别判定 6、Bearer 匹配器 6、来源校验 7、SCIM 过滤器 6 | 29 项全绿 |
| 认证步骤链 | 步骤链编排 8(阶段顺序/阶段内定序/同名定序/跳过/失败终止/空链/不可变)、上下文 6(默认值与状态流转) | 14 项全绿 |
| 重构一致性 | 认证 Provider 原有 8 项用例(账号枚举防护、陪跑哈希、LDAP 兜底与 JIT 二次登录)在拆分后全部通过 | 行为无变化 |
| 迁移 | 真实 PostgreSQL 17 按序执行 **28 个 V 脚本**(含 `V1.3.0`、`V1.3.1`) | 零报错 |
| 回滚往返 | 28 个 V → 逆序 28 个 U → 库归零(0 表 0 函数)→ 重跑 28 个 V | 三轮全部通过,终态 42 表 / 92 权限码 / SUPER_ADMIN 授予 92 条 |
| 迁移回滚 | `U1.3.1` 删授予 1 行 + 删权限码 1 行;`U1.3.0` 恢复列注释 | 无残留、无孤儿授予 |
| 回滚后重放 | 回滚后重跑 `V1.3.0` / `V1.3.1`,再连续重放 `V1.3.1` 两次 | `INSERT 0 0`,幂等成立 |
| 权限种子 | `scim:manage` 写入 + SUPER_ADMIN 授予 | 各 1 行,计数稳定 |
| 回滚脚本配对 | 补齐 10 个历史缺失的 `U` 脚本(`U1.0.9`、`U1.1.0`~`U1.1.2`、`U1.1.4`~`U1.1.9`) | 28 V / 28 U 全覆盖 |
| 前端 | `npm run build`(双入口,console 含 `vue-tsc --noEmit`) | console `app.js` 146.77 kB、portal `portal.js` 98.20 kB |
| 交付元数据 | `mvn help:effective-pom` 检查 license/developer/scm | 已填,版本 `1.0.0` |

## 四、2026-10-28 能力完善验收

投产验收反馈集中在"运营可见性"与"目录治理"两层,本轮补齐 6 项(细节与取舍见
[`docs/keycloak-gap-analysis.md`](keycloak-gap-analysis.md) 第四节第 10 小节):

| 能力 | 落点 |
|---|---|
| 安全告警邮件 | `SecurityAlertService` 覆盖口令变更、动态口令/Passkey 变更与账号开通,异步投递不阻塞登录 |
| 通知投递管理端可查 | 控制台「通知投递」→ `/api/v1/admin/notification-logs`(`notification:view`) |
| 建号与重置通知闭环 | 管理员建号、重置口令后自动发信;模板 `account-opened` / `security-alert` |
| 并发会话上限 | 登录策略新增 `max_concurrent_sessions`(0=不限);超限记 `REPLACED` |
| 域级 SMTP 与模板覆盖 | 控制台「发信配置」「邮件模板」→ `/api/v1/admin/mail-config/**` |
| LDAP/AD 全量同步 | 控制台「LDAP 目录 → 同步目录用户」→ `POST /api/v1/admin/ldap-config/sync`,支持预演 |

验收方式与结果:

| 项 | 方式 | 结果 |
|---|---|---|
| 后端编译 | `./mvnw -q -o -DskipTests compile` | 13 个模块全部通过 |
| 全量测试 | `./mvnw -o -B test`(13 个模块,含容器集成) | **512 项全绿**:0 失败 0 错误,模块分别为 common 13、federation 9、wechat 7、saml 14、authz 155、web 20、admin 6、server 288 |
| 容器集成测试 | 同上,`beenest-iam-server` 内 15 个容器集成测试类 | **44 项全绿**:Testcontainers 真实 PostgreSQL 17 + Redis 7 |
| 前端 | `npm run build`(双入口,console 含 `vue-tsc --noEmit`) | 通过,新增 3 个管理页 |
| 迁移 | 新增 `V1.3.5` 及配对回滚 | 32 V / 32 U 一一配对 |

512 项中 468 项为单元/切片测试,44 项为容器集成测试;集成测试覆盖用户/组织/用户组、OIDC 全流程、
联邦登录与微信登录、分布式会话、客户端准入、IdP 配置与审计合规等真实协议栈路径。

## 五、遗留项收口(2026-10-28 晚)

上一轮验收列出的遗留项按"能否在仓库内闭环"分开处理:

| 遗留项 | 处置 | 状态 |
|---|---|---|
| 通知投递记录无自动清理 | 新增定时清理(默认保留 90 天、分批物理删除、分布式锁),配置见部署文档 3.2 节 | [x] 代码 + 单测 |
| 缺少"异地/异常登录"告警 | 新增"新 IP 登录"提醒:IPv4 /24、IPv6 /64 网段归并,首次登录不提醒,同一地址只提醒一次 | [x] 代码 + 单测 |
| 告警规则与 SLO 未内置 | 提供 `ops/prometheus/iam-alerts.yml` 与 SLO 基线(部署文档第 9 节) | [x] 交付件 |
| 备份脚本缺失 | 提供 `ops/backup/iam-backup.sh` 与恢复演练流程 | [x] 交付件 |
| 容量验证无脚本 | 提供 `ops/load-test/login.js`(k6),压测口径与注意事项见第 9.3 节 | [x] 交付件 |
| 压测执行、告警接警、备份演练 | 依赖目标环境(真实负载/Alertmanager/演练库),仓库内无法闭环 | [ ] 运维执行 |
| 真实 LDAP/AD、SMTP、第三方 SP 联调 | 依赖外部系统,需在目的环境逐项验证 | [ ] 实施联调 |

## 六、2026-10-28 本地安全演练

演练环境:`prod` 配置档 + Docker 容器(非 root、只读根文件系统、丢弃全部 capability、
禁止提权)+ 独立演练库 + 随机主密钥,覆盖"认证、业务、容器、依赖"四条线。

### 6.1 演练结果

| 方向 | 检查项 | 结果 |
|---|---|---|
| 会话 | 登录后 `IAM_SESSION` 为 `Secure; HttpOnly; SameSite=Lax`,登录时重新生成(会话固定防护) | 通过 |
| CSRF | 写接口无令牌 403,带令牌 200,合法令牌 + 伪造 Origin 403 | 通过 |
| 认证 | 未登录管理接口 401;错误口令 401 且提示统一,不可枚举账号 | 通过 |
| 账号安全 | 找回口令对已存在/不存在账号返回同一响应;登录失败计数与账号/IP 锁定生效 | 通过 |
| 信息泄露 | 500 不回显内部信息;Swagger 关闭后 404;TRACE 405;CORS 拒绝未登记来源 | 通过 |
| 注入与 XSS | SQL 注入、IDOR、`lastUsername` 反射与 Thymeleaf 转义探测 | 未发现可利用点 |
| 限流 | 验证码生成按 IP 限流(第 61 次起 429);登录失败与验证码阈值联动 | 通过 |
| 容器基线 | uid 999、只读根、`cap-drop=ALL`、`no-new-privileges`、管理端口未发布 | 通过 |
| 新 IP 提醒 | 换网段触发提醒,同 /24 换地址不打扰;审计表落库正确 | 通过 |
| 协议冒烟 | 发现文档、JWKS、SAML 元数据(证书派生)、口令登录 | 通过 |

### 6.2 本轮修复

| 发现 | 风险 | 处置 |
|---|---|---|
| CSRF Cookie 未带 `Secure` / `SameSite` | 明文信道或跨站请求可携带令牌 | 与 `IAM_SESSION` 对齐为 `Secure; SameSite=Lax` |
| 缺少 Referrer-Policy 与 Permissions-Policy | 跳转时泄露路径与查询串;浏览器高敏能力可被页面调用 | 两条链统一下发 `same-origin` 与能力白名单(不用 `no-referrer`:它会把表单提交的 `Origin` 变成 `null`,同源登录被判跨源) |
| 验证码生成接口无限流 | 可被批量刷图后继续撞库 | 每 IP 每分钟 60 次,超限 429 |
| 镜像含 5 项 CRITICAL、15 项 HIGH 依赖漏洞 | Tomcat FORM 认证绕过、BouncyCastle 名称约束绕过、Jackson 拒绝服务等 | 升级 Tomcat 11.0.25、BouncyCastle 1.85、Jackson 2.21.7 / 3.1.7、httpcore5 5.4.3、Guava 33.7.2、Velocity 2.4.1 |
| 基础镜像 openssl 滞后 | 系统库已知漏洞随镜像上线 | 构建时 `apt-get upgrade` |
| `Dockerfile` 声明 9090 | `docker run -P` 会把管理端口映射到宿主机 | 删除 `EXPOSE 9090` |
| 本地编排数据库/缓存监听全部网卡 | 同网段可直连开发库 | 只绑 `127.0.0.1`,并加 healthcheck |

### 6.3 复验

| 项 | 方式 | 结果 |
|---|---|---|
| 全量测试 | `./mvnw -B test`(13 模块,含容器集成) | **512 项全绿**:0 失败 0 错误 |
| 镜像漏洞 | Trivy 扫镜像(系统库 + Java 依赖),HIGH/CRITICAL,含未修复项 | **0 项** |
| 运行时加固 | `docker inspect` + 容器内写根目录 | 非 root、只读根、cap 全丢、`/tmp` 可写 |
| 协议回归 | 发现文档 / JWKS / SAML 元数据 / 登录 / 限流 | 全部通过 |

> 完整多阶段镜像构建依赖在构建容器内全量下载依赖,本机网络不稳定未跑完;
> 运行阶段指令已用同一 Dockerfile 步骤实际构建验证,完整构建由 CI 执行一次即可。

### 6.4 残余风险(需部署侧落实)

| 风险 | 说明 | 要求 |
|---|---|---|
| 转发头信任 | 私网/回环对端可伪造 `X-Forwarded-For`,影响新 IP 提醒与 IP 限流 | 应用端口只对代理开放;代理清理并追加转发头(部署文档第 7 节) |
| 管理端口无认证 | 9090 的健康详情与指标无鉴权 | 不发布到宿主机/公网,仅运维网段可达 |
| 无 CSP | 页面含内联脚本,暂未下发 | 前端改造后启用;现阶段依赖输出转义与 `X-Frame-Options: DENY` |
| HSTS | 应用不生成,纯 HTTP 环境不能加 | 由 TLS 终结处下发(部署文档第 7 节) |
| 预置口令策略偏松 | 初始化脚本为"≥ 8 位含数字" | 生产域通过管理端提高到本单位基线 |
| 主密钥错误不易发现 | 换错主密钥时 JWKS 500 而健康检查仍 UP | 上线检查清单包含一次 JWKS 实际请求 |
| 时区不一致 | 容器默认 UTC,事件时间落库不带时区 | 所有实例统一 `TZ`,审计排序才可信 |

## 七、2026-10-28 本地容器化部署

把此前"演练时手工执行的 docker 命令"固化成可重复的编排文件,让交付物具备
"拿到仓库就能起一套完整环境"的能力。

### 7.1 新增交付件

| 文件 | 作用 |
|---|---|
| `compose.yaml` | 三件套编排:PostgreSQL + Redis + 应用,健康检查控制启动顺序,数据卷持久化 |
| `Dockerfile.runtime` | 复用宿主产物的运行镜像,离线环境可用;与 `Dockerfile` 运行阶段指令一致 |
| `.dockerignore` | 排除 `target/`、`node_modules/`、`docs/`,避免宿主旧字节码混入镜像 |

`Dockerfile` 同时补装 `curl`:健康检查要读 HTTP 状态码,基础镜像不带它。

### 7.2 部署验收

| 项 | 方式 | 结果 |
|---|---|---|
| 镜像构建 | `IAM_DOCKERFILE=Dockerfile.runtime docker compose build` | 通过,440MB |
| 启动 | `docker compose up -d` | 三容器 healthy,应用 7.7 秒启动完成 |
| 数据库迁移 | 启动日志 | `Successfully applied 32 migrations`,终态 `v1.3.5` |
| 引导管理员 | 启动日志 + 接口 | `admin` 创建成功,登录返回 95 个权限码 |
| 就绪探针 | 容器内 `curl 127.0.0.1:9090/actuator/health/readiness` | `{"status":"UP"}` |
| 健康详情 | 同上 `/actuator/health` | db / redis / diskSpace / ssl 全 UP |
| 登录页 | `GET /login` | 200(38 KB) |
| 控制台 | 登录后 `GET /console` | 200,含 `app.js` / `console.css` / `id="app"` |
| 静态资源 | `GET /console-static/app.js`、`console.css` | 200(147.8 KB / 113.9 KB) |
| OIDC 发现 | `GET /.well-known/openid-configuration` | issuer `http://localhost:8080`,端点齐全 |
| JWKS | `GET /oauth2/jwks` | 返回 RSA 公钥,说明主密钥能解开域签名私钥 |
| 认证 | `POST /api/v1/auth/login` | 成功建立会话 |
| 授权 | 带会话 `GET /api/v1/admin/realms` | 返回 default 域 |
| 未认证拒绝 | 无会话 `GET /api/v1/admin/realms` | 401 |
| 数据落地 | psql 查询 | 45 表 / 95 权限码 / `admin` 1 条 |
| 安全基线 | `docker inspect` | `ReadonlyRootfs=true`、`CapDrop=[ALL]`、`User=iam`、`no-new-privileges` |

### 7.3 一处与文档的偏差已修正

`compose.yaml` 初稿发布了管理端口 `127.0.0.1:9090:9090`,与第 10 节"管理端口不发布"
冲突。健康检查在容器内自探,本就不需要宿主映射,已删除该映射。需要看指标时用
`docker compose exec iam curl 127.0.0.1:9090/actuator/prometheus`。

### 7.4 完整多阶段构建的已知限制

`Dockerfile` 的 `dependency:go-offline` 在多模块工程下要解析全部插件依赖,弱网环境
会长时间无输出(本次实测超过 12 分钟无进展)。本地部署改用 `Dockerfile.runtime`,
完整构建交给 CI 在稳定网络下执行。这不是缺陷,是"把编译放在哪"的取舍——
两条路径的运行阶段完全一致。

### 7.5 部署后发现并修复:CORS 把本站请求判成跨源

**现象**:浏览器登录返回 `Invalid CORS request`(403)。

**根因**:`corsConfigurationSource` 注册了一份 `allowedOrigins` 为空的全路径配置。
框架判定"是否跨源"时比较 Origin 与请求的 scheme/host/port;经反向代理或端口映射后
容器看到的协议/端口与浏览器发出的地址不一致(典型:浏览器 `https`、容器 `http`),
自家页面的登录请求被判成跨源,而白名单为空就一律拒绝。

更要紧的是**两套判定口径不一致**:`IamOriginGuardFilter` 只比对 host:port,
CORS 却按 scheme 一起比。同一个请求会出现"来源校验放行、CORS 拒绝"。

**修复**:

| 改动 | 内容 |
|---|---|
| 新增 `OriginAuthorities` | 抽出 authority 归一化与本站判定:Host 头、`X-Forwarded-Host`、`getServerName()/getServerPort()` 三个候选,只比 host:port 不比协议 |
| `IamOriginGuardFilter` | 改用同一工具,删除重复实现,判定范围扩展到代理转发的原始 Host |
| `corsConfigurationSource` | 改为按请求动态生成:本站来源加入允许列表,再叠加白名单;无 `Origin` 头返回 null 不参与跨源处理 |

**验证**(容器内实测):

| 场景 | 结果 |
|---|---|
| 同源 `http://localhost:8080` | 200 |
| 协议不同 `https://localhost:8080` | 200(修复前 403) |
| 端口被改写 `http://localhost` → 容器 8080 | 200(修复前 403) |
| 前端 dev server `http://localhost:5175` | 200(修复前 403) |
| 域名访问 `http://id.example.com:8080` | 200 |
| 局域网 IP `http://192.168.1.100:8080` | 200 |
| 代理改写 Host + `X-Forwarded-Host` | 200(修复前 403) |
| 外部来源 `https://evil.example` | 403(安全边界未削弱) |
| 其他主机 `http://other.example:8080` | 403 |
| 预检 `OPTIONS` | 200,`Access-Control-Allow-Origin` 回显本站、`Allow-Credentials: true` |

新增 `OriginAuthoritiesTest`(11 项)、`IamCorsConfigurationTest`(8 项),
`IamOriginGuardFilterTest` 补 2 项,全量测试 **532 项全绿**。

### 7.6 第二轮修复:端口被改写与两层口径不一致

7.5 的修复上线后仍复现,定位到两个遗留点:

| 问题 | 表现 | 处置 |
|---|---|---|
| 只比 host:port 仍不够 | 浏览器访问 80、容器监听 8080 时,`localhost` 与 `localhost:8080` 不相等 | 判定收紧为**只比主机名** |
| 两层判定仍不一致 | CORS 已按 host 放行,来源校验过滤器仍按完整 authority 比对,返回 `Access denied` JSON 而非 CORS 错误 | `IamOriginGuardFilter` 改用同一 `isSameHost` |

**为什么放宽到只比主机名**:端口经代理转发后必然与浏览器地址栏不同,按端口比对必然误伤;
服务端判断"是不是自家页面"时主机名才是稳定信号,真正要拦的是完全不同的域名。
代价是同主机不同端口的页面也能跨源调用接口(如 `localhost:3000` 调 `localhost:8080`),
这在本地开发是需要的(Vite dev server),生产环境 IAM 通常独占域名,影响可忽略。

**诊断手段**:跨源拒绝时打一条 WARN,带真实的 `origin`、`host`、`X-Forwarded-Host`、
服务端名与候选列表。只看得到浏览器 403 时,这条日志是唯一能说明"为什么判成跨源"的线索。
