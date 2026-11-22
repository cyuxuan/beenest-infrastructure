package club.beenest.iam.authz.mfa;

/**
 * 会话级 MFA 待验证票据内容。
 *
 * @param userId    用户主键
 * @param sid       会话标识(补验后升级这个会话)
 * @param loginType 第一因素认证方式
 */
public record MfaSessionPending(Long userId, String sid, String loginType) {
}
