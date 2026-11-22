package club.beenest.iam.core.model;

import java.time.LocalDateTime;

/**
 * 用户会话领域模型。
 * <p>对应 iam_user_session,供审计与会话管理列表使用;
 * 运行期状态在 Redis,本模型只描述会话记录本身。</p>
 *
 * @param id             主键
 * @param realmId        所属身份域
 * @param sid            会话标识
 * @param userId         用户主键
 * @param loginType      登录方式(口令/短信/第三方等)
 * @param ip             登录 IP
 * @param userAgent      客户端 User-Agent
 * @param state          会话状态:ACTIVE / LOGGED_OUT / EXPIRED / REPLACED
 * @param createdTime    创建时间
 * @param lastActiveTime 最近活跃时间
 * @param expiredTime    过期时间
 */
public record UserSessionModel(
        Long id,
        Long realmId,
        String sid,
        Long userId,
        String loginType,
        String ip,
        String userAgent,
        String state,
        LocalDateTime createdTime,
        LocalDateTime lastActiveTime,
        LocalDateTime expiredTime
) {

    /** 会话状态:活跃 */
    public static final String STATE_ACTIVE = "ACTIVE";

    /** 会话状态:已注销 */
    public static final String STATE_LOGGED_OUT = "LOGGED_OUT";

    /** 会话状态:已过期 */
    public static final String STATE_EXPIRED = "EXPIRED";

    /** 会话状态:被新登录顶下线(并发会话数超限) */
    public static final String STATE_REPLACED = "REPLACED";
}
