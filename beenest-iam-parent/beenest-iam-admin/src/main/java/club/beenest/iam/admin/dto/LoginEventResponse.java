package club.beenest.iam.admin.dto;

import java.time.LocalDateTime;

/**
 * 登录事件响应。
 *
 * @param id         主键
 * @param userId     用户主键(账号不存在时为 null)
 * @param username   尝试的登录名
 * @param eventType  事件类型:LOGIN_SUCCESS/LOGIN_FAILURE/MFA_SUCCESS/MFA_FAILURE/LOGOUT
 * @param loginType  登录方式
 * @param ip         来源 IP
 * @param userAgent  浏览器标识
 * @param failReason 失败原因(成功事件为 null)
 * @param occurredTime 发生时间
 */
public record LoginEventResponse(
        Long id,
        Long userId,
        String username,
        String eventType,
        String loginType,
        String ip,
        String userAgent,
        String failReason,
        LocalDateTime occurredTime
) {
}
