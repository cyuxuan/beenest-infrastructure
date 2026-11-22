package club.beenest.iam.common.result;

/**
 * 统一响应状态码枚举。
 * <p>错误码分段:0 成功;1xxxx 通用业务错误;2xxxx 认证/权限错误;9xxxx 系统错误。</p>
 */
public enum ErrorCode {

    /** 成功 */
    SUCCESS(0, "操作成功"),

    /** 通用参数校验失败 */
    PARAM_INVALID(10001, "参数校验失败"),
    /** 请求的数据不存在 */
    NOT_FOUND(10002, "资源不存在"),
    /** 数据已存在(唯一性冲突) */
    ALREADY_EXISTS(10003, "资源已存在"),
    /** 数据状态不允许该操作 */
    STATE_CONFLICT(10004, "当前状态不允许该操作"),

    /** 未认证(未登录或令牌缺失/过期) */
    UNAUTHORIZED(20001, "未认证或凭证已失效"),
    /** 已认证但无权限 */
    FORBIDDEN(20003, "无访问权限"),
    /** 账号或密码错误 */
    BAD_CREDENTIALS(20004, "账号或密码错误"),
    /** 账号被锁定 */
    ACCOUNT_LOCKED(20005, "账号已锁定,请稍后再试"),
    /** 账号被禁用 */
    ACCOUNT_DISABLED(20006, "账号已被禁用"),
    /** 令牌已撤销 */
    TOKEN_REVOKED(20007, "令牌已失效"),
    /** 需要多因子认证(口令已通过,尚需第二因子) */
    MFA_REQUIRED(20008, "需要多因子认证"),
    /** 验证码错误 */
    CAPTCHA_INVALID(20009, "验证码错误或已过期"),
    /** 多因子验证码错误 */
    MFA_CODE_INVALID(20010, "动态口令错误"),
    /** 口令不符合安全策略 */
    PASSWORD_POLICY_VIOLATION(20011, "口令不符合安全策略"),
    /** 账号处于锁定状态 */
    ACCOUNT_MFA_LOCKED(20012, "多因子验证失败次数过多,请稍后再试"),
    /** 外部身份提供方不可用(网络故障、端点错误、返回非法响应) */
    IDP_UNAVAILABLE(20013, "第三方登录服务暂时不可用,请改用账号密码登录"),
    /** 短信验证码错误或已过期(与"手机号未注册"不可区分,统一文案) */
    SMS_CODE_INVALID(20014, "验证码错误或已过期,请重新获取"),
    /** 短信发送太频繁(通道侧限流) */
    SMS_SEND_TOO_FREQUENT(20015, "获取验证码过于频繁,请稍后再试"),
    /** 短信通道未配置或配置不可用 */
    SMS_CHANNEL_NOT_CONFIGURED(20016, "短信验证码功能未启用,请联系管理员"),
    /** 短信通道发送失败 */
    SMS_SEND_FAILED(20017, "验证码发送失败,请稍后重试"),
    /** 存在未完成的账户待办事项(改密/验证邮箱/绑定二步验证) */
    REQUIRED_ACTION_PENDING(20018, "请先完成账户待办事项"),
    /** 口令重置票据无效或已过期 */
    RESET_TICKET_INVALID(20019, "重置链接无效或已过期,请重新申请"),
    /** 邮件通道未配置或配置不可用 */
    MAIL_CHANNEL_NOT_CONFIGURED(20020, "邮件服务未启用,请联系管理员"),
    /** 邮件发送失败 */
    MAIL_SEND_FAILED(20021, "邮件发送失败,请稍后重试"),
    /** 账号未配置可用的找回方式(既无已验证邮箱也无已验证手机号) */
    RECOVERY_NOT_AVAILABLE(20022, "该账号未绑定可用的找回方式,请联系管理员"),
    /** Passkey 能力未启用或缺少依赖方配置 */
    WEBAUTHN_NOT_CONFIGURED(20023, "本系统未启用 Passkey 登录"),
    /** 浏览器提交的 WebAuthn 报文格式不合法 */
    WEBAUTHN_PAYLOAD_INVALID(20024, "浏览器返回的凭证数据无法识别,请重试"),
    /** 挑战已过期或已使用 */
    WEBAUTHN_CHALLENGE_INVALID(20025, "本次验证已超时,请重新发起"),
    /** 断言或证明校验未通过 */
    WEBAUTHN_VERIFICATION_FAILED(20026, "Passkey 验证失败,请重试或改用其他方式登录"),
    /** 凭证不存在或不属于当前用户 */
    WEBAUTHN_CREDENTIAL_NOT_FOUND(20027, "凭证不存在"),
    /** 该账号绑定的凭证数量已达上限 */
    WEBAUTHN_CREDENTIAL_LIMIT(20028, "绑定数量已达上限,请先删除不用的凭证"),

    /** 请求过于频繁(限流) */
    TOO_MANY_REQUESTS(10005, "请求过于频繁,请稍后再试"),

    /** 系统内部错误 */
    SYSTEM_ERROR(90000, "系统内部错误");

    /** 状态码 */
    private final int code;
    /** 默认消息(可被 i18n 覆盖) */
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /** 枚举快照:避免每次反查都调用 values() 复制数组。 */
    private static final ErrorCode[] VALUES = values();

    /**
     * 按状态码反查枚举。
     *
     * @param code 业务状态码
     * @return 对应枚举;未知码返回 null
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode value : VALUES) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
