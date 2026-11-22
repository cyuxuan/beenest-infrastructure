package club.beenest.iam.common.event;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录/认证事件。
 * <p>由认证链路(Provider/成功处理器/MFA 第二步)发布,
 * 由 authz 模块的 {@code LoginEventListener} 消费并追加写入 {@code iam_login_event}。
 * 与管理操作审计 {@link AuditLogEvent} 分开记录。</p>
 *
 * @param realmKey   所属身份域标识
 * @param userId     用户主键;账号不存在时为 null
 * @param username   尝试的登录名
 * @param eventType  事件类型: LOGIN_SUCCESS/LOGIN_FAILURE/MFA_SUCCESS/MFA_FAILURE/LOGOUT
 * @param loginType  登录方式: PASSWORD/WECHAT/OIDC 等
 * @param ip         来源 IP
 * @param userAgent  浏览器标识
 * @param failReason 失败原因(成功事件为 null)
 * @param occurredAt 发生时间
 */
public record LoginEventEvent(
        String realmKey,
        Long userId,
        String username,
        String eventType,
        String loginType,
        String ip,
        String userAgent,
        String failReason,
        LocalDateTime occurredAt
) implements Serializable {

    /** 登录成功 */
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    /** 登录失败(口令/状态/验证码任一环节) */
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    /** MFA 第二步成功 */
    public static final String MFA_SUCCESS = "MFA_SUCCESS";
    /** MFA 第二步失败 */
    public static final String MFA_FAILURE = "MFA_FAILURE";
    /** 主动注销 */
    public static final String LOGOUT = "LOGOUT";
    /**
     * 会话超时或异常结束(浏览器关闭、空闲超时、容器回收)。
     * <p>与主动注销分开记录,便于审计区分用户退出与会话失效。</p>
     */
    public static final String SESSION_TIMEOUT = "SESSION_TIMEOUT";

    /** 客户端准入补验发起 */
    public static final String CLIENT_ADMISSION_CHALLENGE = "CLIENT_ADMISSION_CHALLENGE";
    /** 客户端准入补验失败或票据失效 */
    public static final String CLIENT_ADMISSION_DENIED = "CLIENT_ADMISSION_DENIED";
    /** 客户端强制 MFA 补验发起 */
    public static final String CLIENT_ADMISSION_MFA_CHALLENGE = "CLIENT_ADMISSION_MFA_CHALLENGE";
    /** 应用授权拒绝:账号对目标应用不可见 */
    public static final String APPLICATION_ACCESS_DENIED = "APPLICATION_ACCESS_DENIED";
}
