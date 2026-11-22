package club.beenest.iam.admin.dto;

/**
 * 运营仪表盘统计响应,口径为当前身份域。
 *
 * @param userTotal          用户总数
 * @param userActive         正常状态用户数
 * @param userDisabled       禁用状态用户数
 * @param userLocked         锁定状态用户数
 * @param clientTotal        客户端总数
 * @param groupTotal         用户组总数
 * @param sessionActive      活跃会话数
 * @param mfaEnrolledUsers   已绑定 MFA 用户数
 * @param todayLoginSuccess  今日登录成功次数
 * @param todayLoginFailure  今日登录失败次数(不含 MFA 二步失败)
 * @param todayMfaFailure    今日 MFA 二步失败次数
 * @param todayLogout        今日主动注销次数
 */
public record DashboardStatsResponse(
        long userTotal,
        long userActive,
        long userDisabled,
        long userLocked,
        long clientTotal,
        long groupTotal,
        long sessionActive,
        long mfaEnrolledUsers,
        long todayLoginSuccess,
        long todayLoginFailure,
        long todayMfaFailure,
        long todayLogout
) {
}
