# 部署与高可用

本文说明蜂巢 IAM 的部署前提、配置项与多实例下的行为约定。面向运维与实施人员。

## 1. 依赖

| 依赖 | 版本 | 作用 | 缺省会怎样 |
|---|---|---|---|
| PostgreSQL | 17 | 业务数据(用户/角色/权限/客户端/会话记录/审计) | 启动失败(数据源不可用) |
| Redis | 7 | 会话状态、授权存储、权限缓存、登录失败计数、验证码、定时任务锁 | 启动失败(Redis 连接不可用) |

两者都是**强依赖**:平台不提供降级的本地存储。因为"服务无状态"的前提就是
所有跨请求的运行期状态都在共享存储里,一旦退化到进程内存,多实例部署就会
出现"登录后随机掉线"这类极难排查的问题。

### 1.1 本地一键起全栈(compose)

仓库根目录的 `compose.yaml` 把三件套一起起:

| 服务 | 镜像 | 宿主端口 | 数据卷 | 说明 |
|---|---|---|---|---|
| `iam` | `beenest-iam:1.0.0` | `8080` | — | 应用本体,走 `prod` 配置档 |
| `postgres` | `postgres:17-alpine` | `127.0.0.1:5432` | `pgdata` | 只绑回环 |
| `redis` | `redis:7-alpine` | `127.0.0.1:6379` | `redisdata` | 开 AOF,容器重启不掉会话 |

```bash
# 打包并构建运行镜像(离线可用)
./mvnw -o -B -DskipTests package
mkdir -p .docker-runtime
cp beenest-iam-server/target/beenest-iam-server-*.jar .docker-runtime/app.jar
IAM_DOCKERFILE=Dockerfile.runtime docker compose up -d --build

# 停止:保留数据 / 连数据卷一起删
docker compose down
docker compose down -v
```

启动顺序由健康检查控制:`postgres`、`redis` 经 `pg_isready` / `redis-cli ping`
判定 healthy 之后应用才启动。这一步不能省——数据库进程起来时还没准备好接受连接,
抢跑会让 Flyway 在启动瞬间报连接拒绝,表现为"第一次起必失败、重启才好"。

首次启动会按序应用全部迁移并创建引导管理员,日志里能看到
`Successfully applied 32 migrations` 与 `引导管理员创建完成`。

#### 三个 Dockerfile 的分工

| 文件 | 编译发生在哪 | 适用场景 |
|---|---|---|
| `Dockerfile` | 容器内多阶段(`mvn package`) | CI、能从源码一步出镜像的环境 |
| `Dockerfile.runtime` | 宿主先打包,镜像只做运行层 | 本地、内网离线、网络受限环境 |
| `Dockerfile.release` | 宿主先打包,镜像只做运行层 | **交付/生产发布**,带 OCI 标签与健康检查,显式产出 `linux/amd64` |

三者运行阶段的约束一致(非 root、扩展包目录、`exec` 启动),差别在"谁来编译"
以及是否分层。`Dockerfile.release` 用分层布局(见 1.2),产出约 356MB;
`Dockerfile` / `Dockerfile.runtime` 仍是整包 jar,约 456MB,只用于本地与 CI。

#### 与生产的差异

`compose.yaml` 面向本地验收,有三处刻意放宽,上生产前必须改回:

| 项 | 本地 | 生产 |
|---|---|---|
| `IAM_REALM_KEY_SECRET` | 内置占位值 | 由密钥管理系统注入,`openssl rand -base64 32` |
| `SESSION_COOKIE_SECURE` | `false`(HTTP) | `true`(必须 HTTPS) |
| 数据库/缓存端口 | 绑 `127.0.0.1` 便于本地排查 | 不对宿主发布,只走容器内网 |

其余安全基线已与第 10 节一致:只读根文件系统、`cap-drop=ALL`、`no-new-privileges`、
管理端口 9090 不发布。

#### 常见问题

- **点登录直接跳到「页面出错了 / 无访问权限」(403)**
  这是 CSRF 校验失败,不是权限问题,更不是"应用没初始化"。用 `http://IP:端口` 直连时,
  `SESSION_COOKIE_SECURE=true` 会让 `SESSION` 与 `XSRF-TOKEN` 两个 Cookie 都带 `Secure`,
  浏览器在 HTTP 下直接丢弃,登录表单提交时拿不到令牌。确认方式:
  `curl -sS -D - -o /dev/null http://<地址>/login | grep -i set-cookie`,
  出现 `Secure` 就是踩了这一脚,把 `SESSION_COOKIE_SECURE` 改成 `false` 并重启。
  前置反向代理终结 TLS 时才应保持 `true`。
- **`failed to update builder last activity time: operation not permitted`**
  buildx 无法写 `~/.docker/buildx/activity`。把 buildx 配置目录指到可写位置即可:
  `BUILDX_CONFIG=/tmp/iam-buildx docker compose build`。
- **端口 5432 / 6379 被占用**
  开发用的 `beenest-iam-server/compose.yaml` 会起同端口的 PostgreSQL / Redis。
  先 `docker compose -p beenest-iam-server down`,再起全栈。
- **容器内构建卡在 `dependency:go-offline`**
  多模块工程的 `go-offline` 要解析全部插件依赖,弱网下耗时很长且解析不完整。
  本地直接用 `Dockerfile.runtime` 绕过,完整多阶段构建交给 CI 跑一次。
- **登录成功但下一个请求变未登录**
  HTTP 下会话 Cookie 带了 `Secure`,浏览器不回传。确认 `SESSION_COOKIE_SECURE=false`。
- **登录报 `Invalid CORS request`**
  判定"是否自家页面"时只看主机名,不看协议与端口——经反向代理或端口映射后,容器看到的
  协议与端口常与浏览器地址栏不一致(浏览器 80/https、容器 8080/http),按完整地址比对会把
  自家页面的登录请求判成跨源。若仍出现,说明主机名确实不同:把该前端地址加入
  `IAM_CORS_ALLOWED_ORIGINS`。被拒时应用日志会打一条 `跨源请求被拒` 的 WARN,
  带上真实的 `origin`、`host` 与候选列表,按那条日志配白名单即可。
- **日志里 `origin=null`,但页面就在本站**
  这里的 `null` 是浏览器发出的**字面量不透明来源**。真实浏览器实测:文档响应头为
  `Referrer-Policy: no-referrer` 时,Chrome 对同源表单提交会发送 `Origin: null`
  并且不再发送 `Referer`——表单提交的 `Origin` 由 referrer 推导,referrer 被清空后
  来源就退化成不透明值,服务端两个头都拿不到,同源登录必然被判跨源。
  本平台因此统一下发 `Referrer-Policy: same-origin`(跨源仍不回传 referrer,
  但 `Origin` 保持真实值)。若日志里出现 `origin=null`,先确认响应头没有被改回
  `no-referrer`;不透明来源本身一律拒绝,不会因为"看起来像自家页面"而放行。

### 1.2 生产镜像构建与推送(linux/amd64)

交付给生产环境的是 `Dockerfile.release` 产出的镜像。它不含任何业务配置:
数据库地址、口令、主密钥、域名一律由部署侧注入,镜像内没有可用的默认值,
避免"忘了注入也能跑起来"的隐性事故。

```bash
# 1) 先在本机构建产物(jar 与平台无关,宿主架构不影响产物)
./mvnw -o -B -DskipTests package
mkdir -p .docker-runtime
cp beenest-iam-server/target/beenest-iam-server-1.0.0.jar .docker-runtime/app.jar
cp beenest-iam-provider-wechat/target/beenest-iam-provider-wechat-1.0.0.jar providers/

# 2) 在宿主上把 jar 拆成分层(业务代码 1.7MB / 第三方依赖 85MB)
#    产物是纯字节码与资源、与架构无关,所以不必在 QEMU 里跑 java
java -Djarmode=tools -jar .docker-runtime/app.jar extract --layers --destination .docker-runtime/layers

# 3) 跨架构构建 amd64 镜像(arm64 机器上也能产出 amd64)
#    只模拟运行层,不在容器里跑 Maven,因此比多阶段构建快得多
REPO=registry.cn-hangzhou.aliyuncs.com/beenest/beenest-iam
docker buildx build --platform linux/amd64 -f Dockerfile.release \
    --build-arg VERSION=1.0.0 \
    --build-arg REVISION="$(git rev-parse --short HEAD)" \
    --build-arg BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    -t "${REPO}:1.0.0" -t "${REPO}:latest" --push .

# 4) 核对远程架构(必须输出 os=linux arch=amd64)
docker pull --platform linux/amd64 "${REPO}:1.0.0"
docker image inspect "${REPO}:1.0.0" --format '{{.Os}}/{{.Architecture}}'
```

#### 为什么要把 jar 拆成两层

镜像体积的大头从来不是业务代码,而是基础镜像和依赖。按实测拆开看:

| 组成 | 体积 | 改动频率 |
|---|---|---|
| `eclipse-temurin:21-jre-jammy` 基础镜像 | 286MB | 几乎不变 |
| 第三方依赖(`/app/lib`,171 个 jar) | 85MB | 随依赖升级才变 |
| 业务代码(`/app/app.jar` + `/app/app-lib`,10 个模块) | 1.7MB | **每次改代码都变** |

不拆分时,改一行代码就要重推 83MB 的整包;拆分后只推 1.7MB 那一层,
构建本身也从"重新 COPY 整个 jar"变成秒级。实测推送耗时从 30 秒降到 19 秒,
后续纯业务改动可降到 2 秒内。

分层后容器里是"普通 jar + 外部 lib"结构,**不能用 `java -jar`**
(manifest 的 `Class-Path` 只认相对 `lib/`,覆盖不到 `app-lib`),
必须用 `-cp` 显式列出三处路径,`Dockerfile.release` 的 `ENTRYPOINT` 已按此写好。

> **不要再往这个镜像里加 `apt-get update && upgrade`。**
> 它曾被当成"打补丁"写进构建,实际效果是多一层约 100MB 的整包替换、
> 构建多花两分钟,而补丁有效期只到下次重建。镜像已经自带运行所需的全部东西:
> DejaVu 字体(验证码画图要用)、fontconfig、时区数据、curl。保持补丁新鲜的正确做法
> 是让 CI 定期重建镜像,不是在 Dockerfile 里升级。

推送前确认仓库与命名空间已在阿里云容器镜像服务控制台创建——个人版**不会**在
首次推送时自动建仓库,未创建时 `docker push` 只会返回
`denied: requested access to the resource is denied`,与口令错误的表现一样,
容易误判成凭据问题。想区分两者,查鉴权服务返回的权限列表:

```bash
# access 为空数组 = 该账号对这个仓库没有权限(命名空间/仓库不对)
# access 含 push,pull = 凭据与仓库都对,可以推送
curl -sS -u "$USER:$PASSWORD" \
  "https://dockerauth.cn-hangzhou.aliyuncs.com/auth?service=<www-authenticate 里的 service>&scope=repository:<ns>/<repo>:push,pull"
```

#### 生产部署配置占位

`deploy/` 目录是生产部署模板,与根目录 `compose.yaml`(本地验收)分开:

| 文件 | 用途 |
|---|---|
| `deploy/.env.example` | 全部可注入配置的占位清单,按必填/按需分组,含生成方式说明 |
| `deploy/docker-compose.yaml` | 只跑应用本体,数据库与 Redis 由外部提供;必填项缺失时 compose 直接拒绝启动 |

```bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env        # 含口令与主密钥,只允许部署账号读
vi deploy/.env               # 逐项填真实值
docker compose -f deploy/docker-compose.yaml --env-file deploy/.env up -d
```

`deploy/.env` 已在 `.gitignore` 与 `.dockerignore` 中排除,不会入库、也不会进入构建上下文。

## 2. 启动必需的环境变量

| 变量 | 用途 | 缺失后果 |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 数据库连接 | 启动失败 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 | 启动失败 |
| `IAM_REALM_KEY_SECRET` | 机密加密主密钥 | 启动失败 |

`IAM_REALM_KEY_SECRET` 保护四类机密:**身份域签名私钥**、**身份提供方的客户端密钥**、
**LDAP/AD 搜索账号口令**与**域级 SMTP 口令**。前两类一旦明文落库被读走,攻击者即可伪造
任意用户的令牌、或冒充本平台去外部 IdP 换票;后两类泄露则等于交出目录只读权限与发件账号。
生成方式:`openssl rand -base64 32`。

> 换主密钥前必须先用旧密钥把已落库的密文重新加密(或轮换签名密钥),否则旧密文解不开,
> 对应身份域将无法签发令牌、对应提供方将无法登录。

## 3. 常用配置项

| 配置项 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `iam.realm-key.master-secret` | `IAM_REALM_KEY_SECRET` | 无 | 机密加密主密钥,生产必填 |
| `iam.session.cookie-secure` | `SESSION_COOKIE_SECURE` | `true` | 会话 Cookie 是否只走 HTTPS;本地 HTTP 调试需设为 `false` |
| `iam.session.sweep-interval-ms` | — | `300000` | 会话巡检间隔(5 分钟):把"Redis 键已消失但数据库行仍活跃"的僵尸会话收尾 |
| `iam.audit.retention-days` | `IAM_AUDIT_RETENTION_DAYS` | `180` | 审计日志留存天数;`<= 0` 表示永久保留、不清理 |
| `iam.audit.cleanup-cron` | `IAM_AUDIT_CLEANUP_CRON` | `0 30 3 * * *` | 审计清理时间(每天 03:30) |
| `iam.cors.allowed-origins` | `IAM_CORS_ALLOWED_ORIGINS` | 空 | 跨源白名单。**本站来源始终放行**(只比主机名,不比协议与端口),此处只需登记"页面部署在别的主机名上"的前端地址(如 `https://admin.example.com`);留空表示只接受本站请求 |
| `iam.authorization.enforce-application-visibility` | `IAM_AUTHORIZATION_ENFORCE_APPLICATION_VISIBILITY` | `true` | 授权端点与凭据登录是否强制应用可见性:客户端绑定了应用服务时,当前账号必须对该应用可见,否则授权端点返回 403、凭据登录返回 403(`20003`)。仅在灰度排查需要临时放开时设为 `false`,长期关闭等于让门户可见性只管展示 |
| `iam.bootstrap.enabled` | `IAM_BOOTSTRAP_ENABLED` | `true` | 是否创建引导管理员账号 |
| `iam.bootstrap.admin-password` | `IAM_BOOTSTRAP_ADMIN_PASSWORD` | 空 | 引导管理员口令,不落在任何配置文件里 |
| `iam.provider.dir` | `IAM_PROVIDER_DIR` | `./providers` | 外置身份提供方扩展包目录:把独立交付的 jar 放进该目录,启动时按服务声明装载,**新增提供方不必改服务端依赖、不必重新打包**(换包需重启) |

### 3.1 外置扩展包目录

外置扩展包目录是**每个实例各自的一份**:扩展包在启动时装载进本进程的类加载器,实例之间不共享。

- **所有实例必须放同一批 jar**:某个实例缺了扩展包,该实例上的登录就会报「未接入的身份提供方类型」,表现为时好时坏。
- 扩展包升级同样要**所有实例同时换**并重启;滚动升级期间新旧实例的提供方版本可能不一致。
- 目录本身不需要写权限,扩展包由运维用发布流程投递。

容器部署时该目录是镜像内的 `/app/providers`(环境变量 `IAM_PROVIDER_DIR` 已指过去)。运行镜像在构建时
已把本仓库的微信扩展包(`beenest-iam-provider-wechat`)复制进去,容器部署开箱即可用;挂 volume 用于补充
其它第三方扩展包,**挂空目录会遮蔽镜像内置的扩展包**。示例:

```yaml
services:
  iam:
    image: beenest/iam:latest
    volumes:
      - ./providers:/app/providers:ro
```

挂 `:ro` 即可:平台只读这个目录,不需要写权限。

### 3.2 邮件与通知

- **全局 SMTP(部署级兜底)**:`IAM_MAIL_CHANNEL=smtp` + `IAM_MAIL_HOST` / `IAM_MAIL_PORT` /
  `IAM_MAIL_USERNAME` / `IAM_MAIL_PASSWORD` / `IAM_MAIL_FROM`。缺失不影响启动,只是找回口令、
  邮箱验证、建号通知与安全提醒不可用;完整清单见 [`docs/account-self-service.md`](account-self-service.md)。
- **域级 SMTP(库内配置,优先于全局)**:控制台「发信配置」按域填写,SMTP 口令经主密钥信封加密落库、
  管理接口只写不读。未启用或填写不完整的域自动回落到全局配置。因此**换主密钥时域级 SMTP 口令也要
  一并重加密**,否则该域发信会因解不开密文而回落/失败。
- **邮件模板**:内置模板随包发布;控制台「邮件模板」可按域覆盖措辞并随时恢复默认。
  找回口令与邮箱验证模板强制保留 `{{resetUrl}}` / `{{verifyUrl}}`,改坏跳转链接会被保存接口拒绝。
- **投递记录**:每次投递(含失败)写 `iam_notification_log`(只追加),控制台「通知投递」可按渠道、
  结果、收件地址、模板与时间筛选。该表默认**保留 90 天**,由清理任务分批物理删除:

  | 配置项 | 默认 | 说明 |
  |---|---|---|
  | `iam.notification.retention-days` | 90 | 保留天数;`0` 或负数表示永久保留,交由外部归档 |
  | `iam.notification.cleanup-batch-size` | 2000 | 单批删除条数,控制单事务持锁时间 |
  | `iam.notification.cleanup-max-batches` | 50 | 单轮最多删除批数,避免一次跑太久 |
  | `iam.notification.cleanup-interval-ms` | 21600000 | 执行间隔(默认 6 小时);多实例下只有持锁实例执行 |

  投递记录是排障数据,不是审计证据:审计以 `iam_audit_log`、`iam_login_event` 为准,
  两套留存期分别配置,不要互相替代。

## 4. 多实例部署

### 4.1 会话放在哪里

浏览器会话**不存进程内存**,而是:

```
浏览器  ──Cookie: IAM_SESSION=<平台签发的随机令牌>──>  任意实例
                                                          │
                                              Redis: iam:session:{sid}
                                              （主体快照 + 会话时长策略）
                                                          │
                                                   任意实例都能还原登录态
```

- 登录态的权威副本在 Redis,Cookie 里只有一个不可推导的随机令牌;
- 令牌是 `HttpOnly` + `SameSite=Lax` + `Secure`(生产),脚本读不到,跨站跳转仍会带上;
- **因此不需要负载均衡做会话亲和**:请求落到任何实例都能接着服务同一个会话。

> 改造前的实现把登录态放在容器会话里,容器会话只存在于创建它的进程——
> 横向扩容后请求落到另一台实例就找不到会话,用户表现为"刚登录就被登出"。
> 这类问题只在多实例下才出现,单实例环境永远测不出来。

### 4.2 哪些状态在 Redis

| 内容 | 键 | 说明 |
|---|---|---|
| 浏览器会话 | `iam:session:{sid}` | 主体快照 + 最大存续时长 + 空闲超时 |
| 授权记录 | `iam:authz:*` | OAuth2 授权、用户维索引、令牌索引 |
| 权限缓存 | `iam:perm:*` | 键里带权限代次,任一权限变更让整域缓存失效 |
| 登录失败计数与锁定 | `iam:*:loginfail*` / `iam:*:loginlock*` | 账号维度与 IP 维度各一套 |
| 验证码 / MFA 待验证票据 | `iam:captcha:*` / `iam:*:mfapending:*` | 一次性凭证 |
| 三方登录 state | `iam:federation:state:*` | 一次性防伪票据 |
| 定时任务锁 | `iam:lock:*` | 多实例下保证只有一个实例执行 |

**唯一例外**:审计日志与业务数据在 PostgreSQL(审计表的清理按域执行,见
`docs/runbook.md`)。

### 4.3 定时任务在多实例下

平台有三个定时任务,都由 **Redis 分布式锁**保证同一时刻只有一个实例执行:

| 任务 | 默认节奏 | 作用 |
|---|---|---|
| 会话巡检 | 每 5 分钟 | 收尾"Redis 键已消失但数据库行仍活跃"的僵尸会话 |
| 审计留存期清理 | 每天 03:30 | 删除超期审计记录,按域留痕 |
| 通知投递记录清理 | 每 6 小时 | 分批删除超过 `iam.notification.retention-days`(默认 90 天)的投递记录 |

锁基于 `SET key token NX PX ttl`,释放时用 Lua 做"比较并删除",不会误删别人持有的锁。
任务抢不到锁时直接跳过,不影响业务。

### 4.4 扩容步骤

1. 起新的实例,环境变量与既有实例保持一致(尤其 `IAM_REALM_KEY_SECRET` 与数据库、Redis 地址);
2. 新实例通过 Flyway 自动执行未应用的迁移(迁移有版本号,多实例同时启动时由 Flyway 的锁保证只执行一次);
3. 把新实例挂到负载均衡上——**不需要任何会话亲和配置**;
4. 观察指标:`/actuator/metrics` 与 `/actuator/prometheus`(管理端口默认 9090,
   **不得对公网开放**,由网络策略限制在运维网段内)。

### 4.5 排障

| 现象 | 排查方向 |
|---|---|
| 用户频繁掉登录 | Redis 是否可达;`iam:session:*` 键是否在生成后立刻消失;实例是否读同一个 Redis |
| 定时任务重复执行 | 分布式锁是否失效(检查 `iam:lock:*` 键);Redis 是否为共享实例而非各自本地 |
| 权限撤销不生效 | 权限代次 `iam:perm:epoch:{realmId}` 是否自增;各实例是否连同一个 Redis |
| 登录失败锁定不生效 | 计数键 `iam:*:loginfail*` 是否在同一个 Redis |

## 5. 备份与恢复

平台不代管备份,但恢复路径需要事先想清楚——尤其是主密钥。

| 对象 | 内容 | 丢了会怎样 | 备份要求 |
|---|---|---|---|
| PostgreSQL | 用户、角色、权限、客户端、**身份域签名私钥密文**、审计日志 | 业务数据全丢 | 每日全量 + WAL 归档;保留 ≥ 审计留存期 |
| Redis | 会话、授权、权限缓存、限流计数 | 用户被登出、令牌校验失败(重登即可恢复) | 可不备份;需要不中断重启时用 RDB/AOF |
| `IAM_REALM_KEY_SECRET` | 机密加密主密钥 | **签名私钥与 IdP 密钥全部解不开** | 单独加密保存,与数据库备份分开存放 |

仓库内的 [`ops/backup/iam-backup.sh`](../ops/backup/iam-backup.sh) 是可挂 cron 的备份起点:
`pg_dump -Fc` + 可读性校验 + 按 `IAM_BACKUP_RETENTION_DAYS` 清理旧文件,脚本会在缺少
`IAM_REALM_KEY_SECRET` 时给出警告。恢复演练流程见本节第 4 步,演练过才算交付。

**恢复顺序**（演练过才算数）:

1. 恢复 PostgreSQL;
2. 起 Redis;
3. 用**同一个** `IAM_REALM_KEY_SECRET` 启动平台——用错主密钥,私钥与 IdP 密钥
   都会解密失败,对应身份域无法签发令牌;
4. 验证:`/actuator/health` 为 `UP`;拉 JWKS 确认公钥正常;用一个账号走完登录与换令牌;
5. Redis 若是空实例,所有会话与已签发令牌失效,需通知用户重新登录(令牌本身在数据库里,
   但签名私钥在数据库中不受影响)。

## 6. 版本升级与回退

### 6.1 升级

1. 按第 5 节做一次数据库全量备份（回退的唯一兜底）；
2. 停旧实例 → 起新实例。Flyway 在启动时按版本号顺序应用 `db/migration/V*.sql`，
   已应用过的版本记录在 `flyway_schema_history`，不会重复执行；
3. 启动日志出现 `Successfully applied N migration` 即为完成；若出现
   `Schema has a version newer than the latest available migration`，说明本地仓库里的
   迁移脚本比数据库记录的旧，**不要继续启动**，换成对应版本再起。

迁移脚本是向前兼容的追加式变更（加列带默认值、加索引 `IF NOT EXISTS`、种子数据带
`NOT EXISTS` 判重），因此升级过程中旧实例仍可继续服务，支持滚动升级。

### 6.2 回退

Flyway 社区版不提供 undo，回退靠手工执行配套的 `db/rollback/U*.sql`。每个 `V` 脚本
都有一份同版本号的 `U` 脚本，**按版本号逆序**执行：

```bash
# 例:从 1.3.1 回退到 1.0.9 之前
for f in $(ls db/rollback/U*.sql | sort -V -r); do
  psql "$DB_URL" -v ON_ERROR_STOP=1 -f "$f"
done
```

顺序不能颠倒：后一个版本的脚本可能依赖前一个版本引入的列或表，正序回退会因
对象不存在而失败。`-v ON_ERROR_STOP=1` 必须带上，否则 psql 遇错继续执行，会留下
半回退状态。

回退到空库是可行的：28 个 `V` 全量正向 → 28 个 `U` 逆序回退后，`public` schema 下
0 张表、0 个函数；再跑一遍 28 个 `V` 可恢复到 42 张表 / 92 个权限码的完整状态。
这条往返链路已在 PostgreSQL 17 上验证过。

### 6.3 不可逆的迁移

下列迁移含数据清理或回填，`U` 脚本只能撤销结构，**数据本身无法还原**：

| 迁移 | 不可逆的部分 | 回退后的实际状态 |
|---|---|---|
| `V1.0.9` | 存量 `CLIENT` 角色的 `app_service_id` 回填 | 删列后归属丢失，重新升级会再回填一次 |
| `V1.1.1` | 重复的用户权限特批记录被逻辑删除（只留最后更新的一条） | 被删的行不恢复，需从备份还原 |
| `V1.3.0` | 客户端与同意里已登记的 `offline_access` 被清除 | 原值不恢复，需管理员重新登记 |

这类回退前必须先确认数据能否接受丢失。不能接受时，走"恢复备份"而不是"执行 U 脚本"。

## 7. 反向代理

平台按转发头取真实协议与地址(issuer 推导依赖它),代理侧需要透传:

```
X-Forwarded-Proto
X-Forwarded-Host
X-Forwarded-Port
X-Forwarded-For
```

`X-Forwarded-For` 关系到按来源地址的限流:平台按它取真实客户端地址。没透传时,
所有请求在限流上会被算成同一个来源(通常是代理自身),表现为正常用户被限流拦下;
透传时必须由代理**追加**而不是原样转发客户端送来的值,否则限流可以被伪造来源绕过。

**信任边界(重要)**:`server.forward-headers-strategy: framework` 让 Tomcat 采信来自
**回环地址与私网网段**的转发头。这意味着任何能与应用端口直接通信的对端(同宿主机进程、
同 Docker 网络的其他容器、内网其他主机)都能伪造来源 IP,进而影响"新 IP 登录提醒"与
IP 维度限流。因此部署时必须同时满足:

1. 应用端口只对反向代理开放(安全组 / 网络策略 / 只绑回环),不直接暴露给终端用户;
2. 代理侧先清除客户端自带的转发头再追加(`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`
   在 nginx 中会追加,但要在入口层丢弃不可信的同名头);
3. 若代理与应用不在同一网段,按实际网段收紧 Tomcat 的内部代理列表,不要保留过宽的私网信任。

HSTS 由 TLS 终结处下发(`Strict-Transport-Security: max-age=31536000; includeSubDomains`),
应用本身不生成该头,避免在纯 HTTP 的内网环境里把浏览器锁死。

**`iam.issuer-base-url` 在生产必须显式配置。** 留空时签发方、回调地址、口令重置链接都由
请求推导,取值来源是 Host 头:多域名访问会得到不同 issuer,反向代理场景下还可能把内网地址
写进对外链接,Host 头被伪造时链接会指向攻击者域名。它同时是令牌验签时比对签发方的基准,
留空则按当前请求比对——能自洽,但不具备跨域名一致性。只有在单机开发、直接访问
`http://localhost:8080` 时才可留空。

## 8. 容量基线与调优

平台不内置 APM,下列基线值用于判断"当前是否正常"。指标从
`/actuator/prometheus`(管理端口)拉取,阈值按单实例 2 vCPU / 4 GiB 给出,
扩容优先加副本而不是加 CPU——服务无状态,加副本是线性的。

| 指标 | 正常区间 | 偏高时的处置 |
|---|---|---|
| `hikaricp_connections_pending` | 持续 > 0 | 先看慢查询,再调 `DB_POOL_MAX_SIZE`;连接数不是越多越好,超过数据库 `max_connections` 的 80% 会开始互相挤 |
| `hikaricp_connections_usage_seconds_max` | < 100 ms | 有长事务或慢查询,查 `default-statement-timeout` 触发的 WARN 日志 |
| 登录接口 P95 延迟 | < 300 ms(不含 MFA) | 检查口令哈希算法:Argon2id 本身约 50–100 ms,若 P95 到 1 s 说明连接池或数据库在排队 |
| 令牌端点 P95 延迟 | < 200 ms | 权限求值走缓存,偏慢通常意味着权限代次频繁自增导致缓存一直不命中 |
| Redis 命令 P95 | < 5 ms | 会话读写是每个请求的必经路径,Redis 慢会放大到所有接口 |
| 单实例 QPS | 登录 20–50 / 令牌 100–200 | 到上限加副本 |

### 7.1 连接池

`application.yml` 已显式配置 Hikari(不再依赖默认 10 连接):

| 配置项 | 默认 | 说明 |
|---|---|---|
| `DB_POOL_MAX_SIZE` | 20 | 单实例最大连接数。多副本时 `副本数 × 该值` 必须小于数据库 `max_connections` |
| `DB_POOL_MIN_IDLE` | 5 | 常驻空闲连接,避免流量突增时集中建连 |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | 3000 | 取连接超时。3 秒还没拿到连接,继续等只会把线程池拖死,不如快速失败 |
| `DB_POOL_LEAK_DETECTION_MS` | 60000 | 连接持有超过该时长打印持有栈,用于定位忘记提交/回滚的事务 |

### 7.2 慢查询

`mybatis.configuration.default-statement-timeout` 默认 30 秒(可用
`DB_STATEMENT_TIMEOUT_SECONDS` 调整):单条语句超过即中断并抛异常,避免一个慢查询
把连接池占满。需要更细的 SQL 耗时画像时,临时把
`logging.level.club.beenest.iam.persistence` 调到 `DEBUG`(MyBatis 会打印语句与参数),
排障后立刻调回——参数里可能含敏感值。

### 7.3 扩容判断顺序

1. 看 `hikaricp_connections_pending` 是否持续大于 0:是 → 数据库侧瓶颈,加副本无用;
2. 看 Redis P95:是 → 先扩 Redis,会话链路是全站瓶颈;
3. 看 CPU 使用率:长期 > 70% 且前两项正常 → 加副本;
4. 加副本后仍无改善 → 检查是否所有实例共用同一个 `IAM_REALM_KEY_SECRET` 与同一个 Redis,
   以及负载均衡是否真的在分发(会话不粘性,不需要会话亲和)。

## 9. 监控告警、SLO 与压测

### 9.1 告警规则模板

[`ops/prometheus/iam-alerts.yml`](../ops/prometheus/iam-alerts.yml) 提供可被 Prometheus
`rule_files` 直接引用的规则:实例探活、5xx 比例、P95 延迟、连接池占用与排队、堆与 GC 停顿。
抓取目标指向管理端口的 `/actuator/prometheus`,job 名与 `application` 标签按环境替换。

业务侧信号没有现成指标,用日志告警补:

| 信号 | 来源 | 建议阈值 |
|---|---|---|
| 邮件投递失败 | 日志含「通知投递失败」 | 5 分钟内 ≥ 5 条 |
| 登录失败激增 | `iam_login_event` 按 `event_type='LOGIN_FAILURE'` 统计 | 较昨日同时段 +3σ 或 10 分钟内 ≥ 200 条 |
| 账号锁定 | `iam_login_event` 失败原因含锁定,或审计 `LOCK_*` | 出现即通知安全值班 |
| 凭证变更 | 审计 `PASSWORD_CHANGE` / `MFA_*` 变更事件 | 可仅记录,不逐条告警 |

### 9.2 SLO 基线

| 指标 | 目标 | 计算口径 |
|---|---|---|
| 可用性 | 99.9%/月 | 2xx/3xx 请求占比;登录页与发现文档计入,健康检查不计 |
| 登录 P95 | < 300 ms(不含 MFA) | `http_server_requests_seconds` 按 URI 过滤 |
| 令牌 P95 | < 200 ms | 同上,`/oauth2/token` |
| 5xx 比例 | < 1% | 滚动 5 分钟 |
| 告警响应 | P1 15 分钟、P2 4 小时 | 值班表另行约定 |

阈值是起点:上线两周后按真实分布回调,避免规则长期静默或长期 noisy。

### 9.3 登录链路压测

[`ops/load-test/login.js`](../ops/load-test/login.js) 是 k6 脚本,默认压匿名读路径
(发现文档、JWKS、登录页);设置 `IAM_USERNAME` / `IAM_PASSWORD` 后追加账号口令登录。

```bash
k6 run -e IAM_BASE_URL=https://iam.example.com -e IAM_REALM=default \
       -e IAM_USERNAME=loadtest@example.com -e IAM_PASSWORD='***' \
       -e VUS=20 -e DURATION=2m ops/load-test/login.js
```

注意事项:

- 用**独立域与专用压测账号**,压测账号关闭 MFA,否则登录会停在质询步;
- 每次登录都会创建会话,压测结束用 `logout-all` 或等待会话过期,避免挤占会话容量;
- 不要用错误口令制造压力:失败计数与 IP 锁定会先于容量瓶颈触发;
- 压测结果对照第 8 节基线,并同时观察 `hikaricp_connections_pending` 与 Redis 延迟——
  只看客户端 P95 会漏掉"数据库排队、连接池顶住"的中间态。

### 9.4 交付件清单

| 交付件 | 路径 | 验收方式 |
|---|---|---|
| 告警规则 | `ops/prometheus/iam-alerts.yml` | `promtool check rules` 通过,规则在测试环境触发一次 |
| 备份脚本 | `ops/backup/iam-backup.sh` | 在演练库执行一次恢复并走通登录 |
| 压测脚本 | `ops/load-test/login.js` | `k6 inspect` 通过,在预发环境跑满目标 VU |
| 主密钥托管 | 部署方密钥管理系统 | 与数据库备份异地保存,定期轮换演练 |

## 10. 容器与部署安全基线

仓库根目录的 `Dockerfile` 已按下列基线构建,编排时不要把这些约束"优化"掉:

| 项 | 基线 | 目的 |
|---|---|---|
| 运行用户 | 非 root(`iam`,uid 999) | 容器逃逸时拿到的是无特权账号 |
| 文件系统 | `--read-only` + `--tmpfs /tmp` | 阻止落地 webshell、篡改程序与依赖 |
| 能力集 | `--cap-drop=ALL` | 进程不需要任何 Linux capability |
| 提权 | `--security-opt no-new-privileges` | 阻断 setuid 二进制提权路径 |
| 端口 | 只发布 8080;管理端口 9090 **不写 EXPOSE、不发布** | 指标与健康详情不对公网暴露 |
| 系统补丁 | 构建时 `apt-get upgrade` | 吸收基础镜像滞后的 openssl 等系统库补丁 |

参考启动命令:

```bash
docker run -d --name iam \
  --read-only --tmpfs /tmp --cap-drop=ALL --security-opt no-new-privileges \
  -p 127.0.0.1:8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL=jdbc:postgresql://db:5432/beenest_iam \
  -e DB_USERNAME=beenest -e DB_PASSWORD='***' \
  -e REDIS_HOST=redis \
  -e IAM_REALM_KEY_SECRET='***' \
  -e TZ=Asia/Shanghai \
  beenest-iam:1.0.0
```

补充约定:

- **时区**:容器默认 UTC。审计与登录事件按应用时区落库,多实例务必统一 `TZ`,
  否则同一张表里混着两种时间,审计排序与留存期计算都会失真;
- **密钥**:`IAM_REALM_KEY_SECRET` 只经密钥管理系统注入,不写进镜像、编排文件或仓库;
  换错主密钥的表现是 JWKS 返回 500 而健康检查仍为 UP,上线前必须实际请求一次 JWKS 验证;
- **口令策略**:初始化脚本预置的是"≥ 8 位且含数字"的最低策略,生产域应通过管理端
  调高到符合本单位口令基线(长度、复杂度、历史口令、有效期);
- **依赖漏洞**:镜像构建后执行一次高危/严重漏洞扫描(Trivy 或同类),把"无未修复
  CRITICAL"作为发布门槛;系统库补丁不见于代码变更,需在 CI 中定期重建以持续吸收。
