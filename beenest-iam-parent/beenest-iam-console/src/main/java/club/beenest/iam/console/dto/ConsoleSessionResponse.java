package club.beenest.iam.console.dto;

import java.time.LocalDateTime;

/**
 * 会话记录响应。
 *
 * @param sid            会话标识
 * @param loginType      登录方式
 * @param ip             登录来源 IP
 * @param userAgent      浏览器标识
 * @param state          状态:ACTIVE / LOGGED_OUT / EXPIRED
 * @param createdTime    建立时间
 * @param current        是否为当前请求所用的会话(不允许把自己注销掉)
 */
public record ConsoleSessionResponse(
        String sid,
        String loginType,
        String ip,
        String userAgent,
        String state,
        LocalDateTime createdTime,
        boolean current
) {
}
