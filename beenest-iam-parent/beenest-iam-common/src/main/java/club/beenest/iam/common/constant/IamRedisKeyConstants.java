package club.beenest.iam.common.constant;

/**
 * Redis 键名常量。
 * <p>命名规范:{@code iam:{realm}:{domain}:...};不含域维度的键使用 {@code iam:global:...}。
 * 所有模块的缓存键集中定义于此,禁止散落拼接。</p>
 */
public final class IamRedisKeyConstants {

    private IamRedisKeyConstants() {
    }

    /** 键前缀 */
    public static final String PREFIX = "iam";

    // ===== 认证/授权运行期 =====

    /**
     * 权限缓存:iam:perm:{realmId}:{userId}:{epoch} → 权限码集合。
     * <p>键中带权限代次,代次自增即可让整域旧缓存失效。</p>
     */
    public static final String PERMISSION_CACHE = PREFIX + ":perm:%s:%s:%s";

    /** 权限代次:iam:perm:epoch:{realmId} → 自增计数器。 */
    public static final String PERMISSION_EPOCH = PREFIX + ":perm:epoch:%s";

    /** 应用视图权限缓存:iam:permapp:{realmId}:{userId}:{appServiceId}:{epoch}。 */
    public static final String APP_PERMISSION_CACHE = PREFIX + ":permapp:%s:%s:%s:%s";

    /** 用户令牌状态:iam:tokenstate:{userId} → "状态:令牌版本"。 */
    public static final String TOKEN_STATE = PREFIX + ":tokenstate:%s";

    /** 用户会话:iam:session:{sid} → 会话哈希。 */
    public static final String USER_SESSION = PREFIX + ":session:%s";

    /** 登录失败计数:iam:{realmKey}:loginfail:{username} → 次数。 */
    public static final String LOGIN_FAIL_COUNT = PREFIX + ":%s:loginfail:%s";

    /**
     * 登录锁定标记:iam:{realmKey}:loginlock:{username} → 1。
     * <p>与失败计数分开存储,锁定到期不受失败窗口影响。</p>
     */
    public static final String LOGIN_LOCK = PREFIX + ":%s:loginlock:%s";

    /**
     * 登录失败计数(IP 维度):iam:{realmKey}:loginfailip:{ip} → 次数。
     * <p>用于阻断同一 IP 轮换账号的密码喷洒;计数不因单次成功登录清零。</p>
     */
    public static final String LOGIN_FAIL_COUNT_IP = PREFIX + ":%s:loginfailip:%s";

    /** 登录锁定标记(IP 维度):iam:{realmKey}:loginlockip:{ip} → 1。 */
    public static final String LOGIN_LOCK_IP = PREFIX + ":%s:loginlockip:%s";

    /**
     * 上次成功登录 IP:iam:{realmKey}:loginip:{userId} → IP。
     * <p>用于"新 IP 登录"提醒的基线;换 IP 即更新,避免同一地址反复提醒。</p>
     */
    public static final String LOGIN_LAST_IP = PREFIX + ":%s:loginip:%s";

    /** 授权存储:iam:authz:{id} → OAuth2Authorization 投影。 */
    public static final String OAUTH2_AUTHORIZATION = PREFIX + ":authz:%s";

    /**
     * 授权用户维索引:iam:authz:user:{userId} → 授权 id 集合。
     * <p>用于按用户列举已签发令牌。</p>
     */
    public static final String OAUTH2_AUTHORIZATION_USER = PREFIX + ":authz:user:%s";

    /** 授权反向索引:iam:authz:token:{tokenValue} → 授权 id。 */
    public static final String OAUTH2_AUTHORIZATION_TOKEN = PREFIX + ":authz:token:%s";

    /**
     * 授权码换票抢占:iam:authz:code-claim:{codeValue} → 1。
     * <p>授权码只能换一次票。换票流程是"读授权 → 校验未失效 → 标记失效 → 写回",
     * 并发请求会同时读到未失效的码;以本键的原子写入作为抢占标记,只有一个请求能继续。</p>
     */
    public static final String OAUTH2_AUTHORIZATION_CODE_CLAIM = PREFIX + ":authz:code-claim:%s";

    /**
     * 授权会话标识:iam:authz:sid:{authorizationId} → 会话标识(明文,仅服务端使用)。
     * <p>令牌端点无法获取浏览器会话,签发 ID Token 时按授权取出会话标识并哈希。</p>
     */
    public static final String OAUTH2_AUTHORIZATION_SID = PREFIX + ":authz:sid:%s";

    /** 授权同意:iam:consent:{registeredClientId}:{principalName}。 */
    public static final String OAUTH2_CONSENT = PREFIX + ":consent:%s:%s";

    /**
     * 授权同意的首次授予时间:iam:consent-granted:{registeredClientId}:{principalName} → epoch 毫秒。
     * <p>单独成键而不是塞进同意集合:同意集合的值是 {@code Set<String>},
     * 混入时间会破坏 Spring Authorization Server 的读取契约。前缀用 {@code consent-granted}
     * 而非 {@code consent:granted},避免被按 {@code iam:consent:*} 的扫描误当作同意记录。</p>
     */
    public static final String OAUTH2_CONSENT_GRANTED = PREFIX + ":consent-granted:%s:%s";

    /** 三方登录 state:iam:federation:state:{state} → 关联信息(域/提供方/回跳目标)。 */
    public static final String FEDERATION_STATE = PREFIX + ":federation:state:%s";

    /** 令牌黑名单:iam:blocklist:jti:{jti} → 1。 */
    public static final String TOKEN_BLOCKLIST_JTI = PREFIX + ":blocklist:jti:%s";

    /**
     * 会话吊销标记:iam:blocklist:sid:{sidHash} → 1。
     * <p>{@code sidHash} 是会话标识的 SHA-256 摘要,与令牌里的 {@code sid} 声明同值;
     * 登出时写入,验签时据此拒绝该会话签出的访问令牌。</p>
     */
    public static final String TOKEN_BLOCKLIST_SID = PREFIX + ":blocklist:sid:%s";

    // ===== 缓存 =====

    /** 属性定义缓存:iam:{realmKey}:attrdefs → 属性定义列表。 */
    public static final String ATTRIBUTE_DEFINITIONS = PREFIX + ":%s:attrdefs";

    /** 客户端缓存:iam:{realmKey}:client:{clientId} → 注册客户端投影。 */
    public static final String REGISTERED_CLIENT = PREFIX + ":%s:client:%s";

    /** 启动台应用列表缓存:iam:launchpad:{realmKey}:{userId} → 应用服务列表。 */
    public static final String LAUNCHPAD_APPS = PREFIX + ":launchpad:%s:%s";

    /** 登录策略缓存:iam:{realmKey}:loginpolicy → 登录策略实体。 */
    public static final String LOGIN_POLICY_CACHE = PREFIX + ":%s:loginpolicy";

    /** LDAP 联合配置缓存:iam:{realmKey}:ldapconfig → LDAP 配置实体。 */
    public static final String LDAP_CONFIG_CACHE = PREFIX + ":%s:ldapconfig";

    // ===== 安全能力 =====

    /**
     * 登录页探测限流:iam:ratelimit:{scene}:{ip} → 计数。
     * <p>未登录可达的探测接口按来源地址限速。</p>
     */
    public static final String RATE_LIMIT = PREFIX + ":ratelimit:%s:%s";

    /** 图形验证码:iam:captcha:{captchaId} → 答案(小写)。 */
    public static final String CAPTCHA = PREFIX + ":captcha:%s";

    /** MFA 待验证票据:iam:{realmKey}:mfapending:{token} → 用户主键。 */
    public static final String MFA_PENDING = PREFIX + ":%s:mfapending:%s";

    /**
     * 登录失败消息票据:iam:loginerror:{ticket} → 失败原因。
     * <p>消息正文不经 URL 传递,仅携带一次性票据。</p>
     */
    public static final String LOGIN_ERROR = PREFIX + ":loginerror:%s";

    /** MFA 已使用的一次性码(防重放):iam:{realmKey}:mfaused:{userId}:{code} → 1。 */
    public static final String MFA_USED_CODE = PREFIX + ":%s:mfaused:%s:%s";

    /**
     * 恢复码消费互斥锁:iam:mfalock:recovery:{userId} → 1。
     * <p>恢复码读取与移除需在分布式锁内完成,避免同一枚码被并发兑换。</p>
     */
    public static final String MFA_RECOVERY_LOCK = PREFIX + ":mfalock:recovery:%s";

    /**
     * 短信验证码:iam:{realmKey}:smscode:{phone} → 验证码(明文,一次性消费)。
     * <p>通过 GETDEL 原子取走。</p>
     */
    public static final String SMS_CODE = PREFIX + ":%s:smscode:%s";

    /** 短信验证码校验失败计数:iam:{realmKey}:smscode:attempts:{phone} → 次数。 */
    public static final String SMS_CODE_ATTEMPTS = PREFIX + ":%s:smscode:attempts:%s";

    /**
     * 短信发送计数(每小时窗口):iam:{realmKey}:sms:senthour:{phone} → 次数。
     * <p>阈值取登录策略 {@code sms_max_send_per_hour},0 表示不限制。</p>
     */
    public static final String SMS_SEND_HOUR_COUNT = PREFIX + ":%s:sms:senthour:%s";

    // ===== 账户自助 =====

    /**
     * 口令重置票据:iam:{realmKey}:pwreset:{ticket} → "userId:方式"。
     * <p>票据随机生成且一次性消费,链接中不携带用户标识。</p>
     */
    public static final String PASSWORD_RESET_TICKET = PREFIX + ":%s:pwreset:%s";

    /** 口令重置申请限流:iam:{realmKey}:pwreset:req:{subject} → 计数(按邮箱/手机号)。 */
    public static final String PASSWORD_RESET_REQUEST = PREFIX + ":%s:pwreset:req:%s";

    /** 邮箱验证票据:iam:{realmKey}:emailverify:{ticket} → "userId"。 */
    public static final String EMAIL_VERIFY_TICKET = PREFIX + ":%s:emailverify:%s";

    // ===== Passkey / WebAuthn =====

    /**
     * 注册挑战:iam:{realmKey}:webauthn:reg:{userId} → 挑战原文(Base64URL)。
     * <p>按用户索引,同一用户只保留最新一次注册意图。</p>
     */
    public static final String WEBAUTHN_REG_CHALLENGE = PREFIX + ":%s:webauthn:reg:%s";

    /**
     * 登录挑战:iam:{realmKey}:webauthn:auth:{challenge} → 绑定的用户主键(空串表示未绑定)。
     * <p>按挑战索引,挑战一次性消费后失效。</p>
     */
    public static final String WEBAUTHN_AUTH_CHALLENGE = PREFIX + ":%s:webauthn:auth:%s";

    // ===== 客户端准入 =====

    /** 客户端准入挑战票据(按会话标识索引):iam:{realm}:admission:sid:{sid}。 */
    public static final String ADMISSION_CHALLENGE_SID = PREFIX + ":%s:admission:sid:%s";

    /** 客户端准入挑战票据(按一次性令牌索引):iam:{realm}:admission:token:{token}。 */
    public static final String ADMISSION_CHALLENGE_TOKEN = PREFIX + ":%s:admission:token:%s";

    // ===== SAML 身份提供方 =====

    /**
     * 已消费的 AuthnRequest:iam:{realmKey}:saml:req:{requestId} → 1。
     * <p>同一个请求 ID 只允许签发一次断言;值写入后到断言有效期结束前都不得复用。</p>
     */
    public static final String SAML_CONSUMED_REQUEST = PREFIX + ":%s:saml:req:%s";
}
