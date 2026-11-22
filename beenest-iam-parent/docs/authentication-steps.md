# 认证步骤链

登录主干由一组固定顺序的步骤组成。本文说明这条链怎么编排、怎么扩展、以及为什么不做成可配置的流程引擎。

## 1. 设计判断:步骤链,不是流程引擎

Keycloak 的认证流程是可配置的树(flow → subflow → authenticator + 条件执行 + 优先级)。它需要这个能力,因为同一个二进制要服务银行(硬件令牌 + 交易签名)、医院(智能卡)、互联网公司(社交登录),无法预设流程。

本平台是组织内 IAM,认证流程在可预见的未来不会变:一个第一因素 + 一个可选的第二因素 + 准入判定。把这段固定逻辑改成可配置的树,换来的是:

- 排障从"读代码"变成"读配置快照"
- 配错 flow 能让全员登不进去,而编译期毫无提示
- 要多维护一个可视化编辑器

所以这里做的是**步骤链**:主干顺序编译期固定,每个步骤可单独实现、单独测试、可被扩展包替换或追加,但**不能重新排列阶段**。

## 2. 四个阶段

| 阶段 | 序号 | 职责 | 失败语义 |
|---|---|---|---|
| `PRE_CHECK` | 10 | 身份域可用性、失败锁定、验证码门禁 | 拒绝,不接触凭据 |
| `FIRST_FACTOR` | 20 | 证明"你是谁":口令、目录 bind | 拒绝,计入失败次数 |
| `POST_CHECK` | 30 | 账号状态、账号类型,清理失败计数 | 拒绝 |
| `SECOND_FACTOR` | 40 | 证明"还是你":二步验证门禁 | 抛 `MfaRequiredException`,转入第二步 |

阶段之间的顺序由 `Stage` 枚举的 `order` 决定,**不可配置**。同阶段内的步骤按 `AuthenticationStep.order()` 升序,再按 `name()` 定序(保证不同 JVM 与启动顺序下行为一致)。

## 3. 内置七个步骤

| 步骤 | 阶段 | order | 作用 |
|---|---|---|---|
| `realm-resolution` | PRE_CHECK | 10 | 解析身份域,加载域登录策略 |
| `account-lock` | PRE_CHECK | 20 | 账号维度 + 来源 IP 维度锁定检查 |
| `captcha` | PRE_CHECK | 30 | 达到失败阈值后要求验证码 |
| `credential` | FIRST_FACTOR | 10 | 本地口令优先,目录 bind 兜底;账号不存在时陪跑哈希 |
| `account-status` | POST_CHECK | 10 | 账号状态、服务账号禁止交互登录 |
| `failure-reset` | POST_CHECK | 20 | 清除账号维度失败计数 |
| `mfa-gate` | SECOND_FACTOR | 10 | 已绑定 TOTP 或域强制 MFA 时转入第二步 |

几个容易踩的位置:

- **状态检查必须在口令校验之后**。账号状态会泄露账号是否存在,先要求持有正确口令,攻击者才无法用状态差异枚举账号。
- **失败计数清理必须在状态校验之后**。账号被禁用时不应清计数,否则"禁用账号被反复尝试"这个信号会被抹掉。
- **陪跑哈希不能省**。账号不存在时若不执行等哈希运算直接返回,响应时间明显短于口令错误,形成可测量的时序侧信道。

## 4. 扩展方式

实现 `AuthenticationStep` 并声明为 Spring bean 即可,无需改动 `IamAuthenticationProvider` 或任何配置类。

```java
@Component
public class CertificatePreCheckStep implements AuthenticationStep {

    @Override
    public Stage stage() {
        return Stage.PRE_CHECK;
    }

    @Override
    public String name() {
        return "certificate-precheck";
    }

    @Override
    public int order() {
        return 15;  // 插在 realm-resolution(10) 与 account-lock(20) 之间
    }

    @Override
    public boolean appliesTo(AuthenticationContext context) {
        return context.getRealm() != null;   // 域未解析时无事可做
    }

    @Override
    public void execute(AuthenticationContext context) throws AuthenticationException {
        // 读取 context.getRequest()、写入自定义状态;失败抛 AuthenticationException
    }
}
```

三个典型场景:

| 目标 | 做法 |
|---|---|
| 加一道前置检查(如客户端证书预检) | `Stage.PRE_CHECK`,`order` 取 15 / 25 等空位 |
| 加一种第一因素 | `Stage.FIRST_FACTOR`,并在 `LoginMethod` 词表加一项;注意与既有口令步骤的短路关系(见下) |
| 加一种第二因素(如 Passkey 作为第二因素) | 新增 `Stage.SECOND_FACTOR` 步骤;`mfa-gate` 只判定"是否需要",具体验证方式由第二步服务承担 |

**第一因素的短路语义要注意**:现有 `credential` 步骤是"本地口令优先、目录兜底",不是"口令失败再试 LDAP"。本地账号口令错误时**不会**转交目录——这是刻意的,否则本地账号口令错但目录有同名账号且口令对时会被放行。新增第一因素时若希望它参与竞争,需要显式判断 `context.isCredentialVerified()` 决定是否跳过,不要假设链路会自动短路。

## 5. 与准入判定的边界

准入判定(`ClientAdmissionPolicy`)**不在**这条链里,而在 `ClientAdmissionFilter` 中、于授权端点请求时执行。原因是联邦登录先建会话后才知道客户端:登录时还不知道用户要去哪个应用,无法在登录链路里判定。

所以"验证码 → 第一因素 → 第二因素 → 准入"这个描述里,前三段在本链,第四段在过滤器。不要把准入挪进登录链——那会破坏"先建会话、后按客户端补验"的既有设计,已登录用户会被踢回登录页。

## 6. 排障

链路装配完成后会输出一条 debug 日志:

```
认证步骤链已装配: [PRE_CHECK/realm-resolution, PRE_CHECK/account-lock, ...]
```

打开 `logging.level.club.beenest.iam.authz.authentication.step=DEBUG` 可看到:

- 装配后的完整顺序(启动时一条)
- 被 `appliesTo` 跳过的步骤(每次认证,`跳过认证步骤: PRE_CHECK/captcha`)

步骤抛出的异常原样上抛,链路不做包装,所以堆栈里看到的第一层就是出错的具体步骤类。

## 7. 已知边界

- **不支持步骤间的条件分支**。需要"满足 A 才执行 B"时,用 `appliesTo` 表达,不要在 `execute` 里做分支后静默返回——静默返回会让链路日志看不出该步骤是否真正生效。
- **不支持异步步骤**。认证链路是同步的,耗时操作(如外部风控调用)需自设超时,否则会拖垮登录接口的响应时间。
- **步骤无状态**。`AuthenticationStep` 是单例 bean,任何跨请求状态必须放 `AuthenticationContext` 或外部存储。
