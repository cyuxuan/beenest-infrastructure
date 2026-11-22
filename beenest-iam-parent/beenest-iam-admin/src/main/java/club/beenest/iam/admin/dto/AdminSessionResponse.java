package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 管理端会话记录响应。
 *
 * @param id            记录主键
 * @param sid           会话标识
 * @param userId        用户主键
 * @param loginType     登录方式
 * @param ip            来源 IP
 * @param userAgent     浏览器标识
 * @param state         会话状态
 * @param createdTime   创建时间
 * @param lastActiveTime 最近活跃时间
 */
public record AdminSessionResponse(
        Long id,
        String sid,
        Long userId,
        String loginType,
        String ip,
        String userAgent,
        String state,
        LocalDateTime createdTime,
        LocalDateTime lastActiveTime
) {
}
